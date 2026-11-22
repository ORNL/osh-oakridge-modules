/*******************************************************************************
 * Copyright (c) 2026 Botts Innovative Research, Inc.
 * All rights reserved.
 *******************************************************************************/
package com.botts.impl.service.oscar.sandbox;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SandboxAuthConfigSeederTest {

    @Test
    public void extractsAndAppliesUsersSsoAndTunnelSettingsWithoutMutatingSource() throws Exception {
        Path dir = Files.createTempDirectory("sandbox-seeder-test");
        try {
            String prodJson = "[\n"
                    + "  {\n"
                    + "    \"objClass\": \"org.sensorhub.impl.security.session.BasicSessionConfig\",\n"
                    + "    \"cookieName\": \"OSCAR_SESSION\",\n"
                    + "    \"webAuthnRpId\": \"hub.example.org\",\n"
                    + "    \"oidcEnabled\": true,\n"
                    + "    \"oidcIssuerUrl\": \"https://auth.hub.example.org/application/o/oscar-federation-sso/\",\n"
                    + "    \"oidcClientId\": \"oscar-federation-hub\",\n"
                    + "    \"oidcClientSecret\": \"test-oidc-secret\",\n"
                    + "    \"trustedTunnelSsoEnabled\": true,\n"
                    + "    \"trustedTunnelSharedSecret\": \"test-tunnel-secret\",\n"
                    + "    \"id\": \"5bb44b9e-e2d7-4a43-9a39-60abdf28e2f8\"\n"
                    + "  },\n"
                    + "  {\n"
                    + "    \"objClass\": \"org.sensorhub.impl.security.BasicSecurityRealmConfig\",\n"
                    + "    \"users\": [\n"
                    + "      {\"id\": \"admin\", \"password\": \"PBKDF2WithHmacSHA256:hash1\", \"roles\": [\"admin\"]},\n"
                    + "      {\"id\": \"operator1\", \"password\": \"PBKDF2WithHmacSHA256:hash2\", \"roles\": [\"operator\"]}\n"
                    + "    ],\n"
                    + "    \"roles\": [{\"id\": \"admin\"}, {\"id\": \"operator\"}],\n"
                    + "    \"id\": \"bd112969-8838-4f62-8d10-1edf1baa6669\"\n"
                    + "  }\n"
                    + "]\n";

            String sandboxJson = "[\n"
                    + "  {\n"
                    + "    \"objClass\": \"org.sensorhub.impl.security.session.BasicSessionConfig\",\n"
                    + "    \"cookieName\": \"OSCAR_SESSION\",\n"
                    + "    \"id\": \"5bb44b9e-e2d7-4a43-9a39-60abdf28e2f8\"\n"
                    + "  },\n"
                    + "  {\n"
                    + "    \"objClass\": \"org.sensorhub.impl.security.BasicSecurityRealmConfig\",\n"
                    + "    \"users\": [{\"id\": \"admin\", \"password\": \"PBKDF2WithHmacSHA256:default\", \"roles\": [\"admin\"]}],\n"
                    + "    \"roles\": [{\"id\": \"admin\"}],\n"
                    + "    \"id\": \"bd112969-8838-4f62-8d10-1edf1baa6669\"\n"
                    + "  }\n"
                    + "]\n";

            Path prodFile = dir.resolve("prod-config.json");
            Path seedFile = dir.resolve("seed.json");
            Path sbFile = dir.resolve("sandbox-config.json");
            Files.writeString(prodFile, prodJson, StandardCharsets.UTF_8);
            Files.writeString(sbFile, sandboxJson, StandardCharsets.UTF_8);

            assertTrue(SandboxAuthConfigSeeder.extractFromProdConfig(prodFile, seedFile));
            SandboxAuthConfigSeeder.applyToSandboxConfig(seedFile, sbFile);

            assertEquals(prodJson, Files.readString(prodFile, StandardCharsets.UTF_8));

            JsonArray updated = JsonParser.parseString(Files.readString(sbFile, StandardCharsets.UTF_8)).getAsJsonArray();
            JsonObject session = updated.get(0).getAsJsonObject();
            assertEquals("hub.example.org", session.get("webAuthnRpId").getAsString());
            assertTrue(session.get("oidcEnabled").getAsBoolean());
            assertEquals("test-oidc-secret", session.get("oidcClientSecret").getAsString());
            assertTrue(session.get("trustedTunnelSsoEnabled").getAsBoolean());
            assertEquals("test-tunnel-secret", session.get("trustedTunnelSharedSecret").getAsString());

            JsonObject realm = updated.get(1).getAsJsonObject();
            assertEquals(2, realm.getAsJsonArray("users").size());
            assertEquals("operator1", realm.getAsJsonArray("users").get(1).getAsJsonObject().get("id").getAsString());
            assertFalse(Files.readString(sbFile, StandardCharsets.UTF_8).contains("PBKDF2WithHmacSHA256:default"));
        } finally {
            Files.walk(dir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        }
    }
}
