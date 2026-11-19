/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package org.sensorhub.impl.utils.rad.webid;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.sensorhub.impl.utils.rad.model.IsotopeAnalysis;
import org.sensorhub.impl.utils.rad.model.WebIdAnalysis;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java offline gamma photopeak nuclide classifier driven by a schema-validated
 * {@code isotope-library.json} data pack.
 *
 * <p>Evaluates calibrated ANSI N42.42 {@code <ChannelData>} spectra using local trapezoid
 * continuum subtraction around each configured photopeak energy, computing net peak counts,
 * Poisson signal-to-noise ratio (SNR), and confidence scores without external network calls.</p>
 */
public final class IsotopeLibraryClassifier implements SpectroscopyIdentificationAlgorithm {

    private static final Pattern COEFF_TAG = Pattern.compile(
            "<(?:[A-Za-z0-9_]+:)?Coefficients[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9_]+:)?Coefficients>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANNEL_ENERGIES_TAG = Pattern.compile(
            "<(?:[A-Za-z0-9_]+:)?ChannelEnergies[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9_]+:)?ChannelEnergies>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANNEL_DATA_TAG = Pattern.compile(
            "<(?:[A-Za-z0-9_]+:)?ChannelData[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9_]+:)?ChannelData>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIVE_TIME_TAG = Pattern.compile(
            "<(?:[A-Za-z0-9_]+:)?LiveTime[^>]*>\\s*PT?([0-9.]+)S\\s*</(?:[A-Za-z0-9_]+:)?LiveTime>",
            Pattern.CASE_INSENSITIVE);
    private static final Gson GSON = new Gson();

    public record IsotopeDefinition(
            String name,
            String category,
            List<Double> energiesKeV,
            double windowKeV,
            double minSnr
    ) {}

    private final String libraryId;
    private final String version;
    private final String defaultExecutionMode;
    private final List<IsotopeDefinition> isotopes;

    public IsotopeLibraryClassifier(
            String libraryId,
            String version,
            String defaultExecutionMode,
            List<IsotopeDefinition> isotopes
    ) {
        if (isotopes == null || isotopes.isEmpty()) {
            throw new IllegalArgumentException("Isotope library must define at least one isotope entry");
        }
        this.libraryId = (libraryId == null || libraryId.isBlank()) ? "custom-isotope-library" : libraryId.trim();
        this.version = (version == null || version.isBlank()) ? "1.0.0" : version.trim();
        this.defaultExecutionMode = normalizeExecutionMode(defaultExecutionMode);
        this.isotopes = List.copyOf(isotopes);
    }

    public static String normalizeExecutionMode(String raw) {
        if (raw == null) {
            return "fallback";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        return "override".equals(lower) ? "override" : "fallback";
    }

    public static IsotopeLibraryClassifier fromJson(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            throw new IllegalArgumentException("Isotope library JSON is empty");
        }
        JsonElement parsed = JsonParser.parseString(jsonText);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Isotope library root must be a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        String libId = getString(root, "libraryId", "custom-isotope-library");
        String ver = getString(root, "version", "1.0.0");
        String mode = getString(root, "executionMode", "fallback");
        double defaultWindow = getDouble(root, "defaultWindowKeV", 22.0);
        double defaultMinSnr = getDouble(root, "defaultMinSnr", 3.5);

        if (!root.has("isotopes") || !root.get("isotopes").isJsonArray()) {
            throw new IllegalArgumentException("Isotope library JSON must include an 'isotopes' array");
        }
        JsonArray arr = root.getAsJsonArray("isotopes");
        List<IsotopeDefinition> defs = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject iso = el.getAsJsonObject();
            String name = getString(iso, "name", "");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Each isotope in 'isotopes' must specify a non-empty 'name'");
            }
            String category = getString(iso, "category", "Industrial");
            double win = getDouble(iso, "windowKeV", defaultWindow);
            double minSnr = getDouble(iso, "minSnr", defaultMinSnr);
            if (win <= 1.0 || win > 250.0) {
                throw new IllegalArgumentException("Invalid windowKeV (" + win + ") for isotope " + name);
            }
            List<Double> energies = new ArrayList<>();
            if (iso.has("energiesKeV") && iso.get("energiesKeV").isJsonArray()) {
                for (JsonElement eVal : iso.getAsJsonArray("energiesKeV")) {
                    double kev = eVal.getAsDouble();
                    if (kev <= 5.0 || kev > 10_000.0) {
                        throw new IllegalArgumentException("Energy keV out of range (5..10000) for isotope " + name);
                    }
                    energies.add(kev);
                }
            }
            if (energies.isEmpty()) {
                throw new IllegalArgumentException("Isotope '" + name + "' must declare at least one photopeak in energiesKeV");
            }
            defs.add(new IsotopeDefinition(name, category, energies, win, minSnr));
        }
        if (defs.isEmpty()) {
            throw new IllegalArgumentException("Isotope library contains zero valid isotope entries");
        }
        return new IsotopeLibraryClassifier(libId, ver, mode, defs);
    }

    @Override
    public String getAlgorithmId() {
        return libraryId;
    }

    @Override
    public String getVersionId() {
        return version;
    }

    public String getDefaultExecutionMode() {
        return defaultExecutionMode;
    }

    public int getIsotopeCount() {
        return isotopes.size();
    }

    @Override
    public WebIdAnalysis analyze(SpectroscopyAnalysisInput input) {
        String xml = input.enrichedN42Xml();
        if ((xml == null || xml.isBlank()) && input.foregroundBytes() != null) {
            xml = new String(input.foregroundBytes(), StandardCharsets.UTF_8);
        }
        if (xml == null || xml.isBlank()) {
            return buildErrorAnalysis("Empty spectroscopic payload");
        }

        double[] counts = parseFirstDoubleSeries(CHANNEL_DATA_TAG, xml);
        if (counts.length < 16) {
            return buildErrorAnalysis("Spectrum contains fewer than 16 channels");
        }
        double[] channelKeV = resolveChannelEnergies(xml, counts.length);
        double liveTimeSec = parseLiveTimeSeconds(xml);

        List<IsotopeAnalysis> matches = new ArrayList<>();
        for (IsotopeDefinition def : isotopes) {
            double matchedPeakSnrSum = 0.0;
            double matchedNetCounts = 0.0;
            int matchedLines = 0;

            for (double targetKeV : def.energiesKeV()) {
                PeakResult pr = evaluatePhotopeak(counts, channelKeV, targetKeV, def.windowKeV());
                if (pr != null && pr.snr >= def.minSnr()) {
                    matchedLines++;
                    matchedPeakSnrSum += pr.snr;
                    matchedNetCounts += pr.netCounts;
                }
            }

            int requiredLines = def.energiesKeV().size() == 1 ? 1 : Math.max(1, (int) Math.ceil(def.energiesKeV().size() * 0.6));
            if (matchedLines >= requiredLines) {
                double avgSnr = matchedPeakSnrSum / matchedLines;
                double lineFraction = (double) matchedLines / def.energiesKeV().size();
                float confidence = (float) Math.min(9.9, Math.max(1.0, (avgSnr / 2.0) * (0.6 + 0.4 * lineFraction)));
                String confStr = confidence >= 7.5f ? "H" : (confidence >= 4.5f ? "M" : "L");
                float countRate = (float) (matchedNetCounts / Math.max(1.0, liveTimeSec));
                matches.add(new IsotopeAnalysis.Builder()
                        .name(def.name())
                        .type(def.category())
                        .confidence(confidence)
                        .confidenceStr(confStr)
                        .countRate(countRate)
                        .build());
            }
        }

        matches.sort(Comparator.comparingDouble(IsotopeAnalysis::getConfidence).reversed());
        StringBuilder isoSummary = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) {
                isoSummary.append(", ");
            }
            IsotopeAnalysis m = matches.get(i);
            isoSummary.append(m.getName()).append(" (").append(m.getConfidenceStr()).append(')');
        }
        String drf = (input.requestedDrf() != null && !input.requestedDrf().isBlank())
                ? input.requestedDrf()
                : "LocalIsotopeLibrary";
        List<String> warnings = matches.isEmpty()
                ? List.of("No photopeaks exceeded threshold in local library '" + libraryId + "'")
                : List.of();

        Map<String, Object> rawObj = new LinkedHashMap<>();
        rawObj.put("analysisError", 0);
        rawObj.put("analysisType", "LocalIsotopeLibrary");
        rawObj.put("libraryId", libraryId);
        rawObj.put("version", version);
        rawObj.put("drf", drf);
        rawObj.put("isotopeString", isoSummary.toString());
        rawObj.put("isotopes", matches);
        rawObj.put("analysisWarnings", warnings);

        WebIdAnalysis built = new WebIdAnalysis.Builder()
                .sampleTime(Instant.now())
                .isotopes(matches)
                .isotopeString(isoSummary.toString())
                .drf(drf)
                .estimatedDose(0.0)
                .chi2(1.0)
                .analysisError(0)
                .errorMessage("")
                .analysisWarnings(warnings)
                .occupancyObsId("")
                .build();
        built.setRawJson(GSON.toJson(rawObj));
        return built;
    }

    private record PeakResult(double netCounts, double snr) {}

    private static PeakResult evaluatePhotopeak(double[] counts, double[] keV, double targetKeV, double halfWindowKeV) {
        int lo = -1;
        int hi = -1;
        for (int i = 0; i < keV.length; i++) {
            if (keV[i] >= targetKeV - halfWindowKeV && lo < 0) {
                lo = i;
            }
            if (keV[i] <= targetKeV + halfWindowKeV) {
                hi = i;
            }
        }
        if (lo < 2 || hi < lo || hi >= counts.length - 2) {
            return null;
        }
        int roiWidth = hi - lo + 1;
        int sideBins = Math.max(2, Math.min(6, roiWidth / 2));
        int leftStart = Math.max(0, lo - sideBins);
        int rightEnd = Math.min(counts.length - 1, hi + sideBins);

        double leftSum = 0.0;
        int leftN = 0;
        for (int i = leftStart; i < lo; i++) {
            leftSum += counts[i];
            leftN++;
        }
        double rightSum = 0.0;
        int rightN = 0;
        for (int i = hi + 1; i <= rightEnd; i++) {
            rightSum += counts[i];
            rightN++;
        }
        double avgSidePerBin = ((leftN > 0 ? leftSum / leftN : 0.0) + (rightN > 0 ? rightSum / rightN : 0.0)) / 2.0;
        double continuum = avgSidePerBin * roiWidth;

        double gross = 0.0;
        for (int i = lo; i <= hi; i++) {
            gross += counts[i];
        }
        double net = gross - continuum;
        if (net <= 0.0) {
            return new PeakResult(0.0, 0.0);
        }
        double variance = Math.max(1.0, gross + continuum);
        double snr = net / Math.sqrt(variance);
        return new PeakResult(net, snr);
    }

    private static double[] resolveChannelEnergies(String xml, int nChannels) {
        double[] explicit = parseFirstDoubleSeries(CHANNEL_ENERGIES_TAG, xml);
        if (explicit.length >= nChannels) {
            return explicit;
        }
        double[] coeffs = parseFirstDoubleSeries(COEFF_TAG, xml);
        double c0 = coeffs.length >= 1 ? coeffs[0] : 0.0;
        double c1 = coeffs.length >= 2 && coeffs[1] > 0.0 ? coeffs[1] : (3000.0 / nChannels);
        double c2 = coeffs.length >= 3 ? coeffs[2] : 0.0;

        double[] energies = new double[nChannels];
        for (int ch = 0; ch < nChannels; ch++) {
            energies[ch] = c0 + c1 * ch + c2 * ch * ch;
        }
        return energies;
    }

    private static double parseLiveTimeSeconds(String xml) {
        Matcher m = LIVE_TIME_TAG.matcher(xml);
        if (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                if (v > 0.0) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 60.0;
    }

    private static double[] parseFirstDoubleSeries(Pattern tagPattern, String xml) {
        Matcher m = tagPattern.matcher(xml);
        double[] best = new double[0];
        while (m.find()) {
            String body = m.group(1).trim();
            if (body.isEmpty()) {
                continue;
            }
            String[] tokens = body.split("[\\s,;]+");
            List<Double> vals = new ArrayList<>(tokens.length);
            for (String tok : tokens) {
                if (tok.isEmpty()) {
                    continue;
                }
                try {
                    vals.add(Double.parseDouble(tok));
                } catch (NumberFormatException ignored) {
                }
            }
            if (vals.size() > best.length) {
                best = new double[vals.size()];
                for (int i = 0; i < vals.size(); i++) {
                    best[i] = vals.get(i);
                }
            }
        }
        return best;
    }

    private WebIdAnalysis buildErrorAnalysis(String message) {
        WebIdAnalysis err = new WebIdAnalysis.Builder()
                .sampleTime(Instant.now())
                .isotopes(List.of())
                .isotopeString("")
                .drf("LocalIsotopeLibrary")
                .estimatedDose(0.0)
                .chi2(0.0)
                .analysisError(1)
                .errorMessage(message)
                .analysisWarnings(List.of(message))
                .occupancyObsId("")
                .build();
        err.setRawJson("{\"analysisError\":1,\"errorMessage\":" + GSON.toJson(message) + "}");
        return err;
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            String val = obj.get(key).getAsString().trim();
            if (!val.isEmpty()) {
                return val;
            }
        }
        return fallback;
    }

    private static double getDouble(JsonObject obj, String key, double fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsDouble();
        }
        return fallback;
    }
}
