/*******************************************************************************

  The contents of this file are subject to the Mozilla Public License, v. 2.0.
  If a copy of the MPL was not distributed with this file, You can obtain one
  at http://mozilla.org/MPL/2.0/.

  Software distributed under the License is distributed on an "AS IS" basis,
  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
  for the specific language governing rights and limitations under the License.

  The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
  Developer are Copyright (C) 2026 the Initial Developer. All Rights Reserved.

 ******************************************************************************/

package com.botts.impl.system.lane.helpers.webrelay;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP/1.1 XML client for ControlByWeb 4-relay modules ({@code WebRelay-Quad}
 * {@code X-WR-4R3-E} / {@code X-WR-4R3-I} serving {@code /stateFull.xml} and
 * {@code X-400} series modules serving {@code /state.xml}).
 *
 * <p>Uses strict {@code HTTP/1.1} without {@code h2c} upgrade headers so embedded
 * microcontroller TCP stacks respond reliably, and parses {@code <relay1state>}
 * through {@code <relay4state>} directly without XML external entity resolution.</p>
 */
public final class WebRelayQuadClient {

    public static final String PATH_STATE_FULL_XML = "/stateFull.xml";
    public static final String PATH_STATE_XML = "/state.xml";
    private static final List<String> CANDIDATE_PATHS = List.of(PATH_STATE_FULL_XML, PATH_STATE_XML);

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofMillis(2500);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMillis(3500);

    private static final Pattern SAFE_HOST_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._:-]{1,253}$"
    );
    private static final Pattern RELAY_TAG_PATTERN_1 = Pattern.compile("<relay1state>\\s*([01])\\s*</relay1state>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELAY_TAG_PATTERN_2 = Pattern.compile("<relay2state>\\s*([01])\\s*</relay2state>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELAY_TAG_PATTERN_3 = Pattern.compile("<relay3state>\\s*([01])\\s*</relay3state>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELAY_TAG_PATTERN_4 = Pattern.compile("<relay4state>\\s*([01])\\s*</relay4state>", Pattern.CASE_INSENSITIVE);

    public record EndpointSpec(
            String host,
            int port,
            String username,
            String password
    ) {
        public EndpointSpec {
            host = sanitizeHost(host);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("WebRelay HTTP port must be between 1 and 65535: " + port);
            }
            username = username != null ? username.trim() : "";
            password = password != null ? password : "";
        }

        public String cacheKey() {
            return host.toLowerCase(Locale.ROOT) + ":" + port;
        }

        public String baseUrl() {
            String hostPart = (host.contains(":") && !host.startsWith("[")) ? "[" + host + "]" : host;
            return port == 80 ? "http://" + hostPart : "http://" + hostPart + ":" + port;
        }
    }

    public record RelayStateSnapshot(
            boolean online,
            int relay1,
            int relay2,
            int relay3,
            int relay4,
            long latencyMs,
            String endpointPath,
            Instant sampledAt,
            String error
    ) {
        public static RelayStateSnapshot offline(String error, long latencyMs) {
            return new RelayStateSnapshot(false, 0, 0, 0, 0, latencyMs, "", Instant.now(),
                    error != null ? error : "Unreachable");
        }

        public boolean isArmedIntercept() {
            return online && relay1 == 1 && relay2 == 1;
        }

        public boolean isPassThrough() {
            return online && relay1 == 0 && relay2 == 0 && relay3 == 0 && relay4 == 0;
        }

        public boolean isGammaIndicatorActive() {
            return online && relay3 == 1;
        }

        public boolean isNeutronIndicatorActive() {
            return online && relay4 == 1;
        }
    }

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Map<String, String> preferredPathByEndpoint = new ConcurrentHashMap<>();

    public WebRelayQuadClient() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    public WebRelayQuadClient(Duration connectTimeout, Duration requestTimeout) {
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static String sanitizeHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            throw new IllegalArgumentException("WebRelay host address or IPv4 is required.");
        }
        String trimmed = rawHost.trim();
        if (trimmed.startsWith("http://")) {
            trimmed = trimmed.substring("http://".length());
        } else if (trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("ControlByWeb WebRelay-Quad uses plain HTTP on the sensor VLAN; omit https://.");
        }
        int slashIdx = trimmed.indexOf('/');
        if (slashIdx >= 0) {
            trimmed = trimmed.substring(0, slashIdx);
        }
        if (trimmed.isEmpty() || !SAFE_HOST_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid WebRelay host or IP address: " + rawHost);
        }
        return trimmed;
    }

    /**
     * Reads current relay coil states without modifying any relay outputs.
     */
    public RelayStateSnapshot readState(EndpointSpec endpoint) {
        return executeQuery(endpoint, null);
    }

    /**
     * Releases all 4 relays ({@code 0,0,0,0}) so normally-closed contacts pass native
     * RPM hardware alarm outputs straight through to the pillar lights, sounder, and
     * auxiliary dry contacts.
     */
    public RelayStateSnapshot setPassThrough(EndpointSpec endpoint) {
        return executeQuery(endpoint, "relay1State=0&relay2State=0&relay3State=0&relay4State=0");
    }

    /**
     * Energizes intercept relays ({@code Relay 1 = 1, Relay 2 = 1}) and clears active
     * drive relays ({@code Relay 3 = 0, Relay 4 = 0}) so raw controller threshold
     * crossings remain silenced while awaiting OSCAR / EML occupancy evaluation.
     */
    public RelayStateSnapshot setArmedIntercept(EndpointSpec endpoint) {
        return executeQuery(endpoint, "relay1State=1&relay2State=1&relay3State=0&relay4State=0");
    }

    /**
     * Triggers a hardware-timed pulse on Channel 1 ({@code Relay 3}: Gamma Light &amp; Sounder)
     * and/or Channel 2 ({@code Relay 4}: Neutron Light) while keeping {@code Relay 1 = 1}
     * and {@code Relay 2 = 1} energized so drive voltage is isolated from controller outputs.
     */
    public RelayStateSnapshot pulseIndicators(
            EndpointSpec endpoint,
            boolean gammaAndSound,
            boolean neutronLight,
            int pulseSeconds
    ) {
        int clampedSec = Math.max(1, Math.min(300, pulseSeconds));
        StringBuilder qs = new StringBuilder("relay1State=1&relay2State=1");
        if (gammaAndSound) {
            qs.append("&relay3State=2&pulseTime3=").append(clampedSec);
        } else {
            qs.append("&relay3State=0");
        }
        if (neutronLight) {
            qs.append("&relay4State=2&pulseTime4=").append(clampedSec);
        } else {
            qs.append("&relay4State=0");
        }
        return executeQuery(endpoint, qs.toString());
    }

    /**
     * Latches Channel 1 ({@code Relay 3}) and/or Channel 2 ({@code Relay 4}) ON
     * continuously for administrator walk-around testing while keeping {@code Relay 1 = 1}
     * and {@code Relay 2 = 1} energized.
     */
    public RelayStateSnapshot latchIndicators(
            EndpointSpec endpoint,
            boolean gammaAndSoundOn,
            boolean neutronLightOn
    ) {
        String qs = "relay1State=1&relay2State=1"
                + "&relay3State=" + (gammaAndSoundOn ? "1" : "0")
                + "&relay4State=" + (neutronLightOn ? "1" : "0");
        return executeQuery(endpoint, qs);
    }

    private RelayStateSnapshot executeQuery(EndpointSpec endpoint, String queryParamsOrNull) {
        Objects.requireNonNull(endpoint, "endpoint");
        long t0 = System.nanoTime();
        String cacheKey = endpoint.cacheKey();
        String preferred = preferredPathByEndpoint.get(cacheKey);

        List<String> order = preferred != null
                ? (PATH_STATE_FULL_XML.equals(preferred)
                    ? List.of(PATH_STATE_FULL_XML, PATH_STATE_XML)
                    : List.of(PATH_STATE_XML, PATH_STATE_FULL_XML))
                : CANDIDATE_PATHS;

        String lastError = "No XML response from WebRelay";
        for (String path : order) {
            try {
                RelayStateSnapshot snap = sendSingleRequest(endpoint, path, queryParamsOrNull, t0);
                if (snap.online()) {
                    preferredPathByEndpoint.put(cacheKey, path);
                    return snap;
                }
                lastError = snap.error();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                long elapsed = nanosToMillis(System.nanoTime() - t0);
                return RelayStateSnapshot.offline("Interrupted contacting WebRelay", elapsed);
            } catch (IOException | IllegalArgumentException ex) {
                lastError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                if (ex instanceof java.net.ConnectException
                        || ex instanceof java.net.UnknownHostException
                        || ex instanceof java.net.http.HttpConnectTimeoutException
                        || ex instanceof IllegalArgumentException) {
                    break;
                }
            }
        }
        long elapsed = nanosToMillis(System.nanoTime() - t0);
        return RelayStateSnapshot.offline(lastError, elapsed);
    }

    private RelayStateSnapshot sendSingleRequest(
            EndpointSpec endpoint,
            String path,
            String queryParamsOrNull,
            long startNanos
    ) throws IOException, InterruptedException {
        String url = endpoint.baseUrl() + path
                + (queryParamsOrNull != null && !queryParamsOrNull.isEmpty() ? "?" + queryParamsOrNull : "");
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "text/xml, application/xml, */*")
                .header("Cache-Control", "no-cache")
                .GET();

        if (!endpoint.password().isEmpty()) {
            String effectiveUser = endpoint.username().isEmpty() ? "admin" : endpoint.username();
            String token = Base64.getEncoder().encodeToString(
                    (effectiveUser + ":" + endpoint.password()).getBytes(StandardCharsets.UTF_8));
            reqBuilder.header("Authorization", "Basic " + token);
        }

        HttpResponse<String> resp = httpClient.send(
                reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long elapsed = nanosToMillis(System.nanoTime() - startNanos);

        int code = resp.statusCode();
        if (code == 401 || code == 403) {
            return RelayStateSnapshot.offline(
                    "WebRelay authentication rejected (HTTP " + code + "); check HTTP Control Username/Password.",
                    elapsed);
        }
        if (code != 200) {
            return RelayStateSnapshot.offline("HTTP " + code + " on " + path, elapsed);
        }

        String body = resp.body();
        return parseXmlSnapshot(body, path, elapsed);
    }

    public static RelayStateSnapshot parseXmlSnapshot(String xmlBody, String endpointPath, long latencyMs) {
        if (xmlBody == null || xmlBody.isBlank()) {
            return RelayStateSnapshot.offline("Empty XML response on " + endpointPath, latencyMs);
        }
        Integer r1 = extractRelayBit(RELAY_TAG_PATTERN_1, xmlBody);
        Integer r2 = extractRelayBit(RELAY_TAG_PATTERN_2, xmlBody);
        Integer r3 = extractRelayBit(RELAY_TAG_PATTERN_3, xmlBody);
        Integer r4 = extractRelayBit(RELAY_TAG_PATTERN_4, xmlBody);
        if (r1 == null || r2 == null || r3 == null || r4 == null) {
            return RelayStateSnapshot.offline(
                    "Unrecognized WebRelay XML payload on " + endpointPath,
                    latencyMs);
        }
        return new RelayStateSnapshot(
                true,
                r1,
                r2,
                r3,
                r4,
                latencyMs,
                endpointPath,
                Instant.now(),
                ""
        );
    }

    private static Integer extractRelayBit(Pattern p, String xml) {
        Matcher m = p.matcher(xml);
        if (!m.find()) {
            return null;
        }
        return "1".equals(m.group(1)) ? 1 : 0;
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }
}
