/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.update;

import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zero-login GitHub Releases discovery for {@code ORNL/oscar}.
 *
 * <p>Operates 100% anonymously without any GitHub account, OAuth flow, or
 * Personal Access Token, and avoids {@code api.github.com}'s 60 requests/hour
 * unauthenticated per-IP rate limit by resolving:
 * <ol>
 *   <li>{@code GET /ORNL/oscar/releases/latest} with redirects disabled
 *       ({@code HTTP 302 Location: .../releases/tag/v<version>});</li>
 *   <li>{@code GET /ORNL/oscar/releases.atom} (public web Atom feed) for
 *       release timestamp, title, and plain-text summary;</li>
 *   <li>{@code GET /ORNL/oscar/releases/download/v<ver>/oscar-<ver>.zip.sha256}
 *       for the authoritative SHA-256 digest; and</li>
 *   <li>{@code HEAD /ORNL/oscar/releases/download/v<ver>/oscar-<ver>.zip} for
 *       asset verification and byte size.</li>
 * </ol>
 */
public final class SystemUpdateReleaseChecker {

    public static final String PROP_GITHUB_WEB_BASE_URL = "oscar.update.githubWebBaseUrl";
    public static final String DEFAULT_GITHUB_WEB_BASE_URL = "https://github.com/ORNL/oscar";
    public static final Pattern SEMVER_PATTERN = Pattern.compile(
            "^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$");

    private static final Pattern TAG_LOCATION_PATTERN = Pattern.compile(
            "/releases/tag/v([0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?)(?:[/?#]|$)");
    private static final Pattern ATOM_ENTRY_PATTERN = Pattern.compile(
            "<entry>([\\s\\S]*?)</entry>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATOM_UPDATED_PATTERN = Pattern.compile(
            "<updated>([^<]+)</updated>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATOM_TITLE_PATTERN = Pattern.compile(
            "<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATOM_CONTENT_PATTERN = Pattern.compile(
            "<content[^>]*>([\\s\\S]*?)</content>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile(
            "(?i)\\b([0-9a-f]{64})\\b");

    private static final long CACHE_TTL_MS = 10L * 60L * 1_000L;
    private static final String USER_AGENT = "OSCAR-SystemUpdater/3.11 (+https://github.com/ORNL/oscar)";

    private final String baseUrl;
    private final HttpClient noRedirectClient;
    private final HttpClient followRedirectClient;
    private final Logger log;

    private volatile JsonObject cachedRelease;
    private volatile long cachedAtEpochMs;

    public SystemUpdateReleaseChecker(Logger log) {
        this(resolveConfiguredBaseUrl(), log);
    }

    public SystemUpdateReleaseChecker(String baseUrl, Logger log) {
        String normalized = (baseUrl == null || baseUrl.isBlank())
                ? DEFAULT_GITHUB_WEB_BASE_URL
                : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.baseUrl = normalized;
        this.log = log;
        this.noRedirectClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        this.followRedirectClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static String resolveConfiguredBaseUrl() {
        String prop = System.getProperty(PROP_GITHUB_WEB_BASE_URL);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv("OSCAR_UPDATE_GITHUB_BASE_URL");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return DEFAULT_GITHUB_WEB_BASE_URL;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public JsonObject getCachedReleaseIfFresh(String currentVersion) {
        JsonObject cached = this.cachedRelease;
        long age = System.currentTimeMillis() - this.cachedAtEpochMs;
        if (cached == null || age < 0 || age > CACHE_TTL_MS) {
            return null;
        }
        return attachComparison(cached.deepCopy(), currentVersion, true);
    }

    public synchronized JsonObject checkLatestRelease(String currentVersion, boolean forceRefresh) throws IOException {
        long now = System.currentTimeMillis();
        if (!forceRefresh && cachedRelease != null && (now - cachedAtEpochMs) < CACHE_TTL_MS) {
            return attachComparison(cachedRelease.deepCopy(), currentVersion, true);
        }

        String atomXml = fetchAtomFeedSafely();
        String latestVersion = discoverLatestTagViaRedirect();
        if (latestVersion == null || latestVersion.isBlank()) {
            latestVersion = extractFirstVersionFromAtom(atomXml);
        }
        if (latestVersion == null || !isValidSemVer(latestVersion)) {
            throw new IOException("Unable to reach GitHub Releases at " + baseUrl
                    + " (this portal may be air-gapped or offline; use Offline Release .zip Upload instead).");
        }

        String tag = "v" + latestVersion;
        String releasePageUrl = baseUrl + "/releases/tag/" + tag;
        String assetFileName = "oscar-" + latestVersion + ".zip";
        String downloadUrl = baseUrl + "/releases/download/" + tag + "/" + assetFileName;
        String sha256Url = downloadUrl + ".sha256";

        String sha256 = fetchExpectedSha256(sha256Url);
        long downloadBytes = probeAssetContentLength(downloadUrl);

        AtomEntryMeta meta = extractAtomEntryMeta(atomXml, latestVersion);

        JsonObject info = new JsonObject();
        info.addProperty("checkedAt", Instant.now().toString());
        info.addProperty("requiresGitHubLogin", false);
        info.addProperty("latestVersion", latestVersion);
        info.addProperty("tagName", tag);
        info.addProperty("releaseTitle", meta.title.isBlank() ? ("Release " + tag) : meta.title);
        if (!meta.publishedAt.isBlank()) {
            info.addProperty("publishedAt", meta.publishedAt);
        }
        if (!meta.notesSummary.isBlank()) {
            info.addProperty("notesSummary", meta.notesSummary);
        }
        info.addProperty("releasePageUrl", releasePageUrl);
        info.addProperty("assetFileName", assetFileName);
        info.addProperty("downloadUrl", downloadUrl);
        info.addProperty("assetAvailable", !sha256.isBlank() || downloadBytes > 0);
        if (!sha256.isBlank()) {
            info.addProperty("expectedSha256", sha256);
        }
        if (downloadBytes > 0) {
            info.addProperty("downloadBytes", downloadBytes);
        }

        this.cachedRelease = info;
        this.cachedAtEpochMs = System.currentTimeMillis();
        return attachComparison(info.deepCopy(), currentVersion, false);
    }

    private JsonObject attachComparison(JsonObject info, String currentVersion, boolean fromCache) {
        String latest = info.has("latestVersion") ? info.get("latestVersion").getAsString() : "";
        String cur = (currentVersion == null || currentVersion.isBlank()) ? "0.0.0" : currentVersion.trim();
        int cmp = compareSemVer(latest, cur);
        boolean compatible = isInPlaceCompatible311Plus(latest);
        boolean assetReady = info.has("assetAvailable") && info.get("assetAvailable").getAsBoolean();

        info.addProperty("currentVersion", cur);
        info.addProperty("fromCache", fromCache);
        info.addProperty("comparison", cmp);
        info.addProperty("updateAvailable", cmp > 0 && compatible && assetReady);
        info.addProperty("upToDate", cmp <= 0);
        info.addProperty("inPlaceCompatible", compatible);
        return info;
    }

    private String discoverLatestTagViaRedirect() {
        URI uri = URI.create(baseUrl + "/releases/latest");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .build();
        try {
            HttpResponse<Void> resp = noRedirectClient.send(req, HttpResponse.BodyHandlers.discarding());
            int sc = resp.statusCode();
            if (sc >= 300 && sc < 400) {
                String loc = resp.headers().firstValue("location").orElse("");
                Matcher m = TAG_LOCATION_PATTERN.matcher(loc);
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (log != null) {
                log.debug("GitHub /releases/latest redirect probe failed: {}", e.getMessage());
            }
        }
        return null;
    }

    private String fetchAtomFeedSafely() {
        URI uri = URI.create(baseUrl + "/releases.atom");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/atom+xml,application/xml,text/xml,*/*")
                .build();
        try {
            HttpResponse<String> resp = followRedirectClient.send(
                    req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200 && resp.body() != null) {
                return resp.body();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (log != null) {
                log.debug("GitHub /releases.atom fetch failed: {}", e.getMessage());
            }
        }
        return "";
    }

    private String fetchExpectedSha256(String sha256Url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(sha256Url))
                .GET()
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", USER_AGENT)
                .build();
        try {
            HttpResponse<String> resp = followRedirectClient.send(
                    req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200 && resp.body() != null) {
                Matcher m = SHA256_HEX_PATTERN.matcher(resp.body());
                if (m.find()) {
                    return m.group(1).toLowerCase(Locale.ROOT);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (log != null) {
                log.debug("GitHub .sha256 asset fetch failed for {}: {}", sha256Url, e.getMessage());
            }
        }
        return "";
    }

    private long probeAssetContentLength(String downloadUrl) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(downloadUrl))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", USER_AGENT)
                .build();
        try {
            HttpResponse<Void> resp = followRedirectClient.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return resp.headers().firstValueAsLong("content-length").orElse(0L);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (log != null) {
                log.debug("GitHub release zip HEAD probe failed for {}: {}", downloadUrl, e.getMessage());
            }
        }
        return 0L;
    }

    static String extractFirstVersionFromAtom(String atomXml) {
        if (atomXml == null || atomXml.isBlank()) {
            return null;
        }
        Matcher entryMatcher = ATOM_ENTRY_PATTERN.matcher(atomXml);
        while (entryMatcher.find()) {
            String entry = entryMatcher.group(1);
            Matcher tagMatcher = TAG_LOCATION_PATTERN.matcher(entry);
            if (tagMatcher.find()) {
                String candidate = tagMatcher.group(1);
                if (isValidSemVer(candidate) && !candidate.contains("-")) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private record AtomEntryMeta(String title, String publishedAt, String notesSummary) {
    }

    private static AtomEntryMeta extractAtomEntryMeta(String atomXml, String targetVersion) {
        if (atomXml == null || atomXml.isBlank() || targetVersion == null) {
            return new AtomEntryMeta("", "", "");
        }
        String needle = "/releases/tag/v" + targetVersion;
        Matcher entryMatcher = ATOM_ENTRY_PATTERN.matcher(atomXml);
        while (entryMatcher.find()) {
            String entry = entryMatcher.group(1);
            if (!entry.contains(needle)) {
                continue;
            }
            String title = group1OrEmpty(ATOM_TITLE_PATTERN, entry);
            String updated = group1OrEmpty(ATOM_UPDATED_PATTERN, entry);
            String rawContent = group1OrEmpty(ATOM_CONTENT_PATTERN, entry);
            String cleanTitle = decodeXmlEntities(title).trim();
            String cleanNotes = stripHtmlToSummary(decodeXmlEntities(rawContent), 2400);
            return new AtomEntryMeta(cleanTitle, updated.trim(), cleanNotes);
        }
        return new AtomEntryMeta("", "", "");
    }

    private static String group1OrEmpty(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : "";
    }

    private static String decodeXmlEntities(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static String stripHtmlToSummary(String html, int maxChars) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = decodeXmlEntities(html
                .replaceAll("(?i)<li[^>]*>", "\n- ")
                .replaceAll("(?i)</(p|div|h1|h2|h3|h4|li|tr)>", "\n")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", ""))
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n")
                .trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars).trim() + "…";
    }

    public static boolean isValidSemVer(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        return SEMVER_PATTERN.matcher(version.trim()).matches();
    }

    public static boolean isInPlaceCompatible311Plus(String version) {
        if (!isValidSemVer(version)) {
            return false;
        }
        Matcher m = SEMVER_PATTERN.matcher(version.trim());
        if (!m.matches()) {
            return false;
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        return major > 3 || (major == 3 && minor >= 11);
    }

    public static int compareSemVer(String a, String b) {
        Matcher ma = SEMVER_PATTERN.matcher(a == null ? "" : a.trim());
        Matcher mb = SEMVER_PATTERN.matcher(b == null ? "" : b.trim());
        if (!ma.matches() && !mb.matches()) {
            return 0;
        }
        if (!ma.matches()) {
            return -1;
        }
        if (!mb.matches()) {
            return 1;
        }
        for (int i = 1; i <= 3; i++) {
            int va = Integer.parseInt(ma.group(i));
            int vb = Integer.parseInt(mb.group(i));
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        String preA = ma.group(4);
        String preB = mb.group(4);
        if (preA == null && preB == null) {
            return 0;
        }
        if (preA == null) {
            return 1; // stable > prerelease
        }
        if (preB == null) {
            return -1;
        }
        return preA.compareTo(preB);
    }
}
