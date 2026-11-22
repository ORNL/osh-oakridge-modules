/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One-way read-only authentication configuration seeder for {@code oscar-sandbox}.
 *
 * <p>Extracts {@code BasicSecurityRealmConfig} (local accounts and role definitions)
 * and {@code BasicSessionConfig} (WebAuthn {@code rpId}, OIDC SSO, and Verified Tunnel
 * Header SSO) from the operational {@code config.json} into an ephemeral seed file and
 * applies them to the disposable sandbox {@code config.json} at startup — never writing
 * to production state.
 */
public final class SandboxAuthConfigSeeder {

    private static final String REALM_CLASS = "org.sensorhub.impl.security.BasicSecurityRealmConfig";
    private static final String SESSION_CLASS = "org.sensorhub.impl.security.session.BasicSessionConfig";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final String[] COPIED_SESSION_FIELDS = {
            "allowKeepSignedIn",
            "persistentCookieMaxAgeSeconds",
            "idleTimeoutSeconds",
            "absoluteTimeoutSeconds",
            "sameSite",
            "webAuthnRpId",
            "webAuthnRpName",
            "oidcEnabled",
            "oidcProviderName",
            "oidcIssuerUrl",
            "oidcClientId",
            "oidcClientSecret",
            "oidcRedirectUri",
            "oidcScopes",
            "oidcAdminGroup",
            "oidcOperatorGroup",
            "oidcDefaultRole",
            "oidcTrustedCaCertificatePath",
            "externalIdentityConsoleUrl",
            "trustedTunnelSsoEnabled",
            "trustedTunnelUserHeader",
            "trustedTunnelGroupsHeader",
            "trustedTunnelSharedSecret",
            "trustedTunnelAutoProvisionViewer"
    };

    private SandboxAuthConfigSeeder() {
    }

    public static boolean extractFromProdConfig(Path prodConfigPath, Path seedOutPath) throws IOException {
        if (prodConfigPath == null || !Files.isRegularFile(prodConfigPath)) {
            return false;
        }
        JsonElement parsed = JsonParser.parseString(Files.readString(prodConfigPath, StandardCharsets.UTF_8));
        if (parsed == null || !parsed.isJsonArray()) {
            return false;
        }
        JsonArray modules = parsed.getAsJsonArray();
        JsonObject seed = new JsonObject();
        for (JsonElement el : modules) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            String cls = optString(obj, "objClass");
            if (REALM_CLASS.equals(cls)) {
                if (!containsUnconsumedBootstrapPlaceholder(obj)) {
                    seed.add("realm", obj.deepCopy());
                }
            } else if (SESSION_CLASS.equals(cls)) {
                JsonObject sessionSubset = new JsonObject();
                for (String key : COPIED_SESSION_FIELDS) {
                    if (obj.has(key) && !obj.get(key).isJsonNull()) {
                        sessionSubset.add(key, obj.get(key).deepCopy());
                    }
                }
                seed.add("session", sessionSubset);
            }
        }
        if (seed.size() == 0) {
            return false;
        }
        if (seedOutPath.getParent() != null) {
            Files.createDirectories(seedOutPath.getParent());
        }
        Files.writeString(seedOutPath, GSON.toJson(seed), StandardCharsets.UTF_8);
        return true;
    }

    public static void applyToSandboxConfig(Path seedPath, Path sandboxConfigPath) throws IOException {
        if (seedPath == null || !Files.isRegularFile(seedPath)
                || sandboxConfigPath == null || !Files.isRegularFile(sandboxConfigPath)) {
            return;
        }
        JsonElement seedEl = JsonParser.parseString(Files.readString(seedPath, StandardCharsets.UTF_8));
        JsonElement cfgEl = JsonParser.parseString(Files.readString(sandboxConfigPath, StandardCharsets.UTF_8));
        if (seedEl == null || !seedEl.isJsonObject() || cfgEl == null || !cfgEl.isJsonArray()) {
            return;
        }
        JsonObject seed = seedEl.getAsJsonObject();
        JsonArray modules = cfgEl.getAsJsonArray();

        for (int i = 0; i < modules.size(); i++) {
            JsonElement el = modules.get(i);
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject mod = el.getAsJsonObject();
            String cls = optString(mod, "objClass");
            if (REALM_CLASS.equals(cls) && seed.has("realm") && seed.get("realm").isJsonObject()) {
                JsonObject seededRealm = seed.getAsJsonObject("realm");
                if (seededRealm.has("users") && seededRealm.get("users").isJsonArray()
                        && seededRealm.getAsJsonArray("users").size() > 0) {
                    mod.add("users", seededRealm.get("users").deepCopy());
                }
                if (seededRealm.has("roles") && seededRealm.get("roles").isJsonArray()
                        && seededRealm.getAsJsonArray("roles").size() > 0) {
                    mod.add("roles", seededRealm.get("roles").deepCopy());
                }
            } else if (SESSION_CLASS.equals(cls) && seed.has("session") && seed.get("session").isJsonObject()) {
                JsonObject seededSession = seed.getAsJsonObject("session");
                for (String key : COPIED_SESSION_FIELDS) {
                    if (seededSession.has(key)) {
                        mod.add(key, seededSession.get(key).deepCopy());
                    }
                }
            }
        }
        Files.writeString(sandboxConfigPath, GSON.toJson(modules), StandardCharsets.UTF_8);
    }

    private static boolean containsUnconsumedBootstrapPlaceholder(JsonObject realm) {
        if (!realm.has("users") || !realm.get("users").isJsonArray()) {
            return true;
        }
        for (JsonElement u : realm.getAsJsonArray("users")) {
            if (u.isJsonObject()) {
                String pw = optString(u.getAsJsonObject(), "password");
                if ("__INITIAL_ADMIN_PASSWORD__".equals(pw)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        return obj.get(key).getAsString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: SandboxAuthConfigSeeder <extract|apply> <inputJson> <outputJson>");
            System.exit(2);
        }
        String mode = args[0];
        Path in = Paths.get(args[1]);
        Path out = Paths.get(args[2]);
        if ("extract".equalsIgnoreCase(mode)) {
            extractFromProdConfig(in, out);
        } else if ("apply".equalsIgnoreCase(mode)) {
            applyToSandboxConfig(in, out);
        } else {
            System.err.println("Unsupported mode: " + mode);
            System.exit(2);
        }
    }
}
