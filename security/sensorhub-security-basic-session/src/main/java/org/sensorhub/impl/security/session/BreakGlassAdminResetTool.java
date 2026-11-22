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

package org.sensorhub.impl.security.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Host break-glass administrator password recovery helper invoked by
 * {@code oscar.sh reset-admin-password} and {@code oscar.bat reset-admin-password}.
 *
 * <p>Reads the new password strictly from {@code stdin} (never command-line arguments),
 * validates password policy, computes a PBKDF2 hash, and either stages an in-memory
 * zero-downtime reset inside the running OSCAR JVM ({@code apply-live}) or updates
 * {@code config.json} and {@code oscar-identity.json} atomically when OSCAR is stopped
 * ({@code apply-offline}), writing an indelible {@code BREAK_GLASS_PASSWORD_RESET}
 * record to {@code .moduledata/security-break-glass-audit.log}.</p>
 */
public final class BreakGlassAdminResetTool {

    public static final String REQUEST_FILENAME = ".break-glass-reset.req.json";
    public static final String ACK_FILENAME = ".break-glass-reset.ack.json";
    public static final String AUDIT_LOG_FILENAME = "security-break-glass-audit.log";
    private static final String REALM_CLASS = "org.sensorhub.impl.security.BasicSecurityRealmConfig";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Gson COMPACT_GSON = new GsonBuilder().disableHtmlEscaping().create();

    private BreakGlassAdminResetTool() {
    }

    public record ResetReceipt(
            String requestId,
            String username,
            int revokedSessions,
            String mode,
            String timestamp
    ) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: BreakGlassAdminResetTool <apply-live|apply-offline> "
                    + "--state-dir <dir> [--user admin] [--actor host-root] [--must-change] [--login-url url]");
            System.exit(2);
        }
        String subcmd = args[0].trim().toLowerCase(Locale.ROOT);
        Path stateDir = Path.of("/var/lib/oscar");
        String username = "admin";
        String actor = "host-admin";
        boolean mustChange = false;
        String loginUrl = "http://127.0.0.1:8282/login";

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--state-dir" -> stateDir = Path.of(args[++i]);
                case "--user" -> username = args[++i].trim();
                case "--actor" -> actor = args[++i].trim();
                case "--must-change" -> mustChange = true;
                case "--login-url" -> loginUrl = args[++i].trim();
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        String rawPassword = readPasswordFromStdin();
        validateTargetUsername(username);
        OscarIdentityStore.validateNewPassword(rawPassword);

        ResetReceipt receipt = switch (subcmd) {
            case "apply-live" -> applyLive(stateDir, username, rawPassword, mustChange, actor, loginUrl);
            case "apply-offline" -> applyOffline(stateDir, username, rawPassword, mustChange, actor);
            default -> throw new IllegalArgumentException("Unsupported mode: " + subcmd);
        };

        System.out.println(COMPACT_GSON.toJson(receipt));
    }

    static void validateTargetUsername(String username) {
        if (username == null || !username.matches("^[A-Za-z0-9._@-]{2,64}$")) {
            throw new IllegalArgumentException("Invalid target username: " + username);
        }
        if (username.toLowerCase(Locale.ROOT).startsWith(OscarIdentityStore.FED_SERVICE_USER_PREFIX)) {
            throw new IllegalArgumentException("Cannot reset password on federation service user: " + username);
        }
    }

    private static String readPasswordFromStdin() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                throw new IllegalArgumentException("No password supplied on standard input.");
            }
            return line.stripTrailing();
        }
    }

    public static ResetReceipt applyLive(Path stateDir,
                                         String username,
                                         String rawPassword,
                                         boolean mustChangePassword,
                                         String hostActor,
                                         String loginPingUrl) throws IOException, InterruptedException {
        validateTargetUsername(username);
        OscarIdentityStore.validateNewPassword(rawPassword);
        String passwordHash = PasswordHasher.hashPassword(rawPassword);

        Path moduleData = stateDir.resolve(".moduledata");
        Files.createDirectories(moduleData);
        Path reqFile = moduleData.resolve(REQUEST_FILENAME);
        Path ackFile = moduleData.resolve(ACK_FILENAME);
        Files.deleteIfExists(ackFile);

        String requestId = UUID.randomUUID().toString();
        String nowIso = Instant.now().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("username", username);
        payload.put("passwordHash", passwordHash);
        payload.put("mustChangePassword", mustChangePassword);
        payload.put("hostActor", hostActor == null || hostActor.isBlank() ? "host-admin" : hostActor);
        payload.put("requestedAt", nowIso);
        writeAtomic0600(reqFile, COMPACT_GSON.toJson(payload));

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest ping = HttpRequest.newBuilder(URI.create(loginPingUrl))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            client.send(ping, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Fall through to poll ackFile briefly; if JVM is unreachable, fall back to offline apply.
        }

        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(ackFile)) {
                String rawAck = Files.readString(ackFile, StandardCharsets.UTF_8);
                Files.deleteIfExists(ackFile);
                JsonObject ack = JsonParser.parseString(rawAck).getAsJsonObject();
                if (requestId.equals(optStr(ack, "requestId"))) {
                    if (ack.has("error") && !optStr(ack, "error").isEmpty()) {
                        throw new IllegalStateException("Live break-glass reset failed: " + optStr(ack, "error"));
                    }
                    int revoked = ack.has("revokedSessions") ? ack.get("revokedSessions").getAsInt() : 0;
                    return new ResetReceipt(requestId, username, revoked, "live", optStr(ack, "appliedAt"));
                }
            }
            Thread.sleep(100L);
        }

        Files.deleteIfExists(reqFile);
        throw new IOException("Timed out waiting for running OSCAR JVM to acknowledge break-glass password reset.");
    }

    public static ResetReceipt applyOffline(Path stateDir,
                                            String username,
                                            String rawPassword,
                                            boolean mustChangePassword,
                                            String hostActor) throws IOException {
        validateTargetUsername(username);
        OscarIdentityStore.validateNewPassword(rawPassword);

        Path configFile = stateDir.resolve("config.json");
        if (!Files.isRegularFile(configFile)) {
            throw new IOException("OSCAR config.json not found at " + configFile + " (initialize OSCAR first).");
        }

        String passwordHash = PasswordHasher.hashPassword(rawPassword);
        updateConfigJsonRealmPassword(configFile, username, passwordHash);

        Path moduleData = stateDir.resolve(".moduledata");
        Files.createDirectories(moduleData);
        Path identityFile = moduleData.resolve("oscar-identity.json");
        int revoked = updateIdentityStoreOffline(identityFile, username, mustChangePassword);

        String nowIso = Instant.now().toString();
        String reqId = UUID.randomUUID().toString();
        appendAuditRecord(moduleData.resolve(AUDIT_LOG_FILENAME), reqId, username, hostActor, revoked, "offline", nowIso);
        return new ResetReceipt(reqId, username, revoked, "offline", nowIso);
    }

    static void updateConfigJsonRealmPassword(Path configFile, String username, String passwordHash) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(configFile, StandardCharsets.UTF_8));
        if (!parsed.isJsonArray()) {
            throw new IOException("Invalid OSCAR config.json root (expected JSON array).");
        }
        JsonArray modules = parsed.getAsJsonArray();
        JsonObject realm = null;
        for (JsonElement el : modules) {
            if (el.isJsonObject() && REALM_CLASS.equals(optStr(el.getAsJsonObject(), "objClass"))) {
                realm = el.getAsJsonObject();
                break;
            }
        }
        if (realm == null) {
            throw new IOException("BasicSecurityRealmConfig module not found in config.json.");
        }

        JsonArray users = realm.has("users") && realm.get("users").isJsonArray()
                ? realm.getAsJsonArray("users")
                : new JsonArray();
        realm.add("users", users);

        JsonObject matchedUser = null;
        for (JsonElement el : users) {
            if (el.isJsonObject() && username.equals(optStr(el.getAsJsonObject(), "userID"))) {
                matchedUser = el.getAsJsonObject();
                break;
            }
        }
        if (matchedUser == null) {
            matchedUser = new JsonObject();
            matchedUser.addProperty("objClass", "org.sensorhub.impl.security.BasicSecurityRealmConfig$UserConfig");
            matchedUser.addProperty("userID", username);
            matchedUser.addProperty("name", "admin".equals(username) ? "Administrator" : username);
            matchedUser.addProperty("password", passwordHash);
            JsonArray roles = new JsonArray();
            roles.add(OscarIdentityStore.ROLE_ADMIN);
            matchedUser.add("roles", roles);
            matchedUser.add("allow", new JsonArray());
            matchedUser.add("deny", new JsonArray());
            users.add(matchedUser);
        } else {
            matchedUser.addProperty("password", passwordHash);
            if ("admin".equals(username)) {
                JsonArray roles = matchedUser.has("roles") && matchedUser.get("roles").isJsonArray()
                        ? matchedUser.getAsJsonArray("roles")
                        : new JsonArray();
                boolean hasAdmin = false;
                for (JsonElement r : roles) {
                    if (r.isJsonPrimitive() && OscarIdentityStore.ROLE_ADMIN.equals(r.getAsString())) {
                        hasAdmin = true;
                        break;
                    }
                }
                if (!hasAdmin) {
                    roles.add(OscarIdentityStore.ROLE_ADMIN);
                }
                matchedUser.add("roles", roles);
            }
        }

        writeAtomic0600(configFile, GSON.toJson(modules));
    }

    private static int updateIdentityStoreOffline(Path identityFile,
                                                  String username,
                                                  boolean mustChangePassword) throws IOException {
        JsonObject root = Files.isRegularFile(identityFile)
                ? JsonParser.parseString(Files.readString(identityFile, StandardCharsets.UTF_8)).getAsJsonObject()
                : new JsonObject();
        JsonObject users = root.has("users") && root.get("users").isJsonObject()
                ? root.getAsJsonObject("users")
                : new JsonObject();
        root.add("users", users);

        JsonObject profile = users.has(username) && users.get(username).isJsonObject()
                ? users.getAsJsonObject(username)
                : new JsonObject();
        if (!profile.has("userId")) {
            profile.addProperty("userId", username);
        }
        if (!profile.has("displayName")) {
            profile.addProperty("displayName", "admin".equals(username) ? "Administrator" : username);
        }
        if (!profile.has("passkeyUserId")) {
            profile.addProperty("passkeyUserId", PasswordHasher.randomBase64Url(32));
        }
        if (!profile.has("createdAt")) {
            profile.addProperty("createdAt", System.currentTimeMillis());
        }
        profile.addProperty("enabled", true);
        profile.addProperty("mustChangePassword", mustChangePassword);
        profile.addProperty("authSource", "local");
        users.add(username, profile);

        int revoked = 0;
        if (root.has("sessions") && root.get("sessions").isJsonObject()) {
            JsonObject sessions = root.getAsJsonObject("sessions");
            var toRemove = new java.util.ArrayList<String>();
            for (Map.Entry<String, JsonElement> entry : sessions.entrySet()) {
                if (entry.getValue().isJsonObject()
                        && username.equals(optStr(entry.getValue().getAsJsonObject(), "username"))) {
                    toRemove.add(entry.getKey());
                }
            }
            for (String k : toRemove) {
                sessions.remove(k);
                revoked++;
            }
        }

        writeAtomic0600(identityFile, GSON.toJson(root));
        return revoked;
    }

    static void appendAuditRecord(Path auditFile,
                                  String requestId,
                                  String username,
                                  String hostActor,
                                  int revokedSessions,
                                  String mode,
                                  String timestampIso) throws IOException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", "BREAK_GLASS_PASSWORD_RESET");
        record.put("timestamp", timestampIso);
        record.put("requestId", requestId);
        record.put("username", username);
        record.put("hostActor", hostActor == null || hostActor.isBlank() ? "host-admin" : hostActor);
        record.put("revokedSessions", revokedSessions);
        record.put("mode", mode);
        String line = COMPACT_GSON.toJson(record) + "\n";
        Files.writeString(
                auditFile,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        setPermissions0600Quietly(auditFile);
    }

    static void writeAtomic0600(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp." + UUID.randomUUID());
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        setPermissions0600Quietly(tmp);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        setPermissions0600Quietly(target);
    }

    private static void setPermissions0600Quietly(Path p) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    static String optStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        return obj.get(key).getAsString();
    }
}
