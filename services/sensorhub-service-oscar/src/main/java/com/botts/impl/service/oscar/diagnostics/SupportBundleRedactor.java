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

package com.botts.impl.service.oscar.diagnostics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automated edge redactor for OSCAR troubleshooting support bundles.
 *
 * <p>Scrubs credentials, password hashes, secret file paths, session identifiers,
 * Authorization headers, query-string tokens, PEM private keys, and embedded URI
 * userinfo (such as {@code rtsp://user:pass@camera}) from configuration JSON and
 * diagnostic text before serialization into a support archive.</p>
 */
public final class SupportBundleRedactor {
    public static final String REDACTED = "[REDACTED]";
    public static final String REDACTED_SECRET_FILE = "[REDACTED_SECRET_FILE]";
    public static final String REDACTED_PRIVATE_KEY = "[REDACTED_PRIVATE_KEY]";

    private static final Set<String> SAFE_KEY_ALLOWLIST = Set.of(
            "cookiename",
            "keyalias",
            "idprovidertype",
            "authmethod",
            "securecookie"
    );

    private static final Set<String> SECRET_FILE_KEYS = Set.of(
            "passwordfile",
            "secretfile",
            "privatekeyfile",
            "keystorepath",
            "truststorepath"
    );

    private static final Set<String> EXACT_SENSITIVE_KEYS = Set.of(
            "password",
            "passwd",
            "pwd",
            "secret",
            "token",
            "accesstoken",
            "access_token",
            "refreshtoken",
            "refresh_token",
            "idtoken",
            "id_token",
            "apikey",
            "api_key",
            "authorization",
            "cookie",
            "cookies",
            "set-cookie",
            "sessioncookie",
            "oscar_session",
            "jsessionid",
            "privatekey",
            "private_key",
            "passphrase",
            "credentials",
            "clientsecret",
            "client_secret",
            "keystorepassword",
            "truststorepassword",
            "hmac"
    );

    private static final Pattern PEM_PRIVATE_KEY_PATTERN = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----");

    private static final Pattern URI_USERINFO_PATTERN = Pattern.compile(
            "((?:jdbc:)?[a-zA-Z][a-zA-Z0-9+.-]*://)([^/@\\s\"'<>]+)@");

    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile(
            "(?i)(\\bAuthorization\\s*[:=]\\s*(?:Basic|Bearer|Digest|Token)\\s+)([^\\s,;\"']+)");

    private static final Pattern STANDALONE_AUTH_SCHEME_PATTERN = Pattern.compile(
            "(?i)\\b(Bearer|Basic)\\s+([A-Za-z0-9._~+/=-]{12,})");

    private static final Pattern COOKIE_VALUE_PATTERN = Pattern.compile(
            "(?i)\\b(OSCAR_SESSION|JSESSIONID)\\s*=\\s*([^;\\s\"',]+)");

    private static final Pattern COOKIE_HEADER_PATTERN = Pattern.compile(
            "(?i)(\\b(?:Cookie|Set-Cookie)\\s*:\\s*)([A-Za-z0-9._-]+)=([^;\\s\"',]+)((?:\\s*;\\s*[A-Za-z0-9._-]+=[^;\\s\"',]+)*)");
    private static final Pattern COOKIE_PAIR_PATTERN = Pattern.compile(
            "([A-Za-z0-9._-]+)=([^;\\s\"',]+)");

    private static final Pattern QUERY_SECRET_PATTERN = Pattern.compile(
            "(?i)([?&](?:password|passwd|pwd|token|access_token|refresh_token|api_key|apikey|secret|auth|session|sig|signature)=)([^&\\s\"'#]+)");

    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
            "(?i)(\"?\\b(?:password|passwd|pwd|secret|api[_-]?key|(?:(?:access|refresh|id|auth|session|csrf|api)[_-]?)?token|private[_-]?key|passphrase|db_password|admin_password)\\b\"?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;}\\]\"']+)");

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b");

    private SupportBundleRedactor() {
    }

    public static final class RedactionStats {
        private int jsonFieldsRedacted;
        private int uriCredentialsRedacted;
        private int authHeadersRedacted;
        private int cookiesRedacted;
        private int querySecretsRedacted;
        private int keyValueSecretsRedacted;
        private int pemBlocksRedacted;
        private int emailsMasked;

        public int jsonFieldsRedacted() {
            return jsonFieldsRedacted;
        }

        public int uriCredentialsRedacted() {
            return uriCredentialsRedacted;
        }

        public int authHeadersRedacted() {
            return authHeadersRedacted;
        }

        public int cookiesRedacted() {
            return cookiesRedacted;
        }

        public int querySecretsRedacted() {
            return querySecretsRedacted;
        }

        public int keyValueSecretsRedacted() {
            return keyValueSecretsRedacted;
        }

        public int pemBlocksRedacted() {
            return pemBlocksRedacted;
        }

        public int emailsMasked() {
            return emailsMasked;
        }

        public int totalRedactions() {
            return jsonFieldsRedacted
                    + uriCredentialsRedacted
                    + authHeadersRedacted
                    + cookiesRedacted
                    + querySecretsRedacted
                    + keyValueSecretsRedacted
                    + pemBlocksRedacted
                    + emailsMasked;
        }
    }

    public static JsonElement redactJson(JsonElement element, RedactionStats stats) {
        if (element == null || element.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (element.isJsonArray()) {
            JsonArray input = element.getAsJsonArray();
            JsonArray out = new JsonArray(input.size());
            for (JsonElement item : input) {
                out.add(redactJson(item, stats));
            }
            return out;
        }
        if (element.isJsonObject()) {
            JsonObject input = element.getAsJsonObject();
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : input.entrySet()) {
                String key = entry.getKey();
                JsonElement val = entry.getValue();
                if (isSecretFileKey(key)) {
                    if (val != null && val.isJsonPrimitive() && val.getAsJsonPrimitive().isString()) {
                        String raw = val.getAsString();
                        if (!raw.isBlank()) {
                            if (stats != null)
                                stats.jsonFieldsRedacted++;
                            out.addProperty(key, REDACTED_SECRET_FILE);
                        } else {
                            out.addProperty(key, "");
                        }
                    } else {
                        out.add(key, redactJson(val, stats));
                    }
                } else if (isSensitiveKey(key)) {
                    if (val == null || val.isJsonNull()) {
                        out.add(key, JsonNull.INSTANCE);
                    } else if (val.isJsonPrimitive() && val.getAsJsonPrimitive().isString()
                            && val.getAsString().isEmpty()) {
                        out.addProperty(key, "");
                    } else {
                        if (stats != null)
                            stats.jsonFieldsRedacted++;
                        out.addProperty(key, REDACTED);
                    }
                } else {
                    out.add(key, redactJson(val, stats));
                }
            }
            return out;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive prim = element.getAsJsonPrimitive();
            if (prim.isString()) {
                return new JsonPrimitive(redactText(prim.getAsString(), stats));
            }
            return prim;
        }
        return element;
    }

    public static String redactText(String raw, RedactionStats stats) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? "" : raw;
        }
        String result = raw;

        result = replaceCounting(PEM_PRIVATE_KEY_PATTERN, result,
                matcher -> REDACTED_PRIVATE_KEY,
                count -> {
                    if (stats != null) stats.pemBlocksRedacted += count;
                });

        result = replaceCounting(URI_USERINFO_PATTERN, result,
                matcher -> matcher.group(1) + REDACTED + "@",
                count -> {
                    if (stats != null) stats.uriCredentialsRedacted += count;
                });

        result = replaceCounting(AUTH_HEADER_PATTERN, result,
                matcher -> matcher.group(1) + REDACTED,
                count -> {
                    if (stats != null) stats.authHeadersRedacted += count;
                });

        result = replaceCounting(STANDALONE_AUTH_SCHEME_PATTERN, result,
                matcher -> matcher.group(1) + " " + REDACTED,
                count -> {
                    if (stats != null) stats.authHeadersRedacted += count;
                });

        result = replaceCounting(COOKIE_HEADER_PATTERN, result,
                matcher -> {
                    String prefix = matcher.group(1) + matcher.group(2) + "=" + REDACTED;
                    String rest = matcher.group(4);
                    if (rest == null || rest.isEmpty()) {
                        return prefix;
                    }
                    return prefix + COOKIE_PAIR_PATTERN.matcher(rest).replaceAll("$1=" + REDACTED);
                },
                count -> {
                    if (stats != null) stats.cookiesRedacted += count;
                });

        result = replaceCounting(COOKIE_VALUE_PATTERN, result,
                matcher -> matcher.group(1) + "=" + REDACTED,
                count -> {
                    if (stats != null) stats.cookiesRedacted += count;
                });

        result = replaceCounting(QUERY_SECRET_PATTERN, result,
                matcher -> matcher.group(1) + REDACTED,
                count -> {
                    if (stats != null) stats.querySecretsRedacted += count;
                });

        result = replaceCounting(KEY_VALUE_SECRET_PATTERN, result,
                matcher -> {
                    String valueToken = matcher.group(2);
                    if (valueToken == null || valueToken.isEmpty()
                            || "\"\"".equals(valueToken) || "''".equals(valueToken)
                            || valueToken.contains(REDACTED)) {
                        return null;
                    }
                    if (valueToken.startsWith("\"") && valueToken.endsWith("\"") && valueToken.length() >= 2) {
                        return matcher.group(1) + "\"" + REDACTED + "\"";
                    }
                    if (valueToken.startsWith("'") && valueToken.endsWith("'") && valueToken.length() >= 2) {
                        return matcher.group(1) + "'" + REDACTED + "'";
                    }
                    return matcher.group(1) + REDACTED;
                },
                count -> {
                    if (stats != null) stats.keyValueSecretsRedacted += count;
                });

        result = replaceCounting(EMAIL_PATTERN, result,
                matcher -> matcher.group(1) + "***@" + matcher.group(2),
                count -> {
                    if (stats != null) stats.emailsMasked += count;
                });

        return result;
    }

    static boolean isSecretFileKey(String key) {
        if (key == null || key.isBlank())
            return false;
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return SECRET_FILE_KEYS.contains(normalized);
    }

    static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank())
            return false;
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (SAFE_KEY_ALLOWLIST.contains(normalized))
            return false;
        if (EXACT_SENSITIVE_KEYS.contains(normalized))
            return true;
        return normalized.endsWith("password")
                || normalized.endsWith("passwd")
                || normalized.endsWith("secret")
                || normalized.endsWith("token")
                || normalized.endsWith("apikey")
                || normalized.endsWith("api_key")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("private_key")
                || normalized.endsWith("passphrase");
    }

    @FunctionalInterface
    private interface MatchReplacer {
        /** Return null to leave the matched span unchanged without counting a redaction. */
        String replacement(Matcher matcher);
    }

    @FunctionalInterface
    private interface CountConsumer {
        void accept(int count);
    }

    private static String replaceCounting(Pattern pattern,
                                          String input,
                                          MatchReplacer replacer,
                                          CountConsumer countConsumer) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        StringBuilder sb = new StringBuilder(input.length());
        int count = 0;
        do {
            String replacement = replacer.replacement(matcher);
            if (replacement == null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                count++;
            }
        } while (matcher.find());
        matcher.appendTail(sb);
        if (count > 0) {
            countConsumer.accept(count);
        }
        return sb.toString();
    }
}
