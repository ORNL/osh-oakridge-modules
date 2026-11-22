package org.sensorhub.impl.security.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sensorhub.api.security.IPermissionPath;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.api.security.IUserInfo;
import org.sensorhub.api.security.IUserRegistry;
import org.sensorhub.impl.security.BasicSecurityRealmConfig.UserConfig;
import org.slf4j.LoggerFactory;

public class BreakGlassAdminResetToolTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void offlineResetUpdatesConfigJsonRevokesAdminSessionsPreservesPasskeysAndAudits() throws Exception {
        Path stateDir = tempFolder.newFolder("oscar-state").toPath();
        Path moduleData = stateDir.resolve(".moduledata");
        Files.createDirectories(moduleData);

        String oldHash = PasswordHasher.hashPassword("OldForgottenPassword-123");
        String configJson = """
                [
                  {
                    "objClass": "org.sensorhub.impl.security.BasicSecurityRealmConfig",
                    "users": [
                      {
                        "objClass": "org.sensorhub.impl.security.BasicSecurityRealmConfig$UserConfig",
                        "userID": "admin",
                        "name": "Administrator",
                        "password": "%s",
                        "roles": ["admin"],
                        "allow": [],
                        "deny": []
                      }
                    ],
                    "roles": []
                  }
                ]
                """.formatted(oldHash);
        Files.writeString(stateDir.resolve("config.json"), configJson, StandardCharsets.UTF_8);

        String identityJson = """
                {
                  "users": {
                    "admin": {
                      "userId": "admin",
                      "displayName": "Administrator",
                      "enabled": false,
                      "mustChangePassword": false,
                      "passkeyUserId": "YWRtaW4tcGFzc2tleS11c2VyLWlk",
                      "passkeys": [
                        {
                          "id": "Y3JlZC1tYWNib29rLTAx",
                          "name": "Gatehouse YubiKey",
                          "rpId": "old-portal.local"
                        }
                      ]
                    },
                    "operator1": {
                      "userId": "operator1",
                      "displayName": "Operator One",
                      "enabled": true
                    }
                  },
                  "sessions": {
                    "hash_admin_1": { "tokenHash": "hash_admin_1", "username": "admin" },
                    "hash_op_1": { "tokenHash": "hash_op_1", "username": "operator1" }
                  }
                }
                """;
        Files.writeString(moduleData.resolve("oscar-identity.json"), identityJson, StandardCharsets.UTF_8);

        BreakGlassAdminResetTool.ResetReceipt receipt = BreakGlassAdminResetTool.applyOffline(
                stateDir,
                "admin",
                "NewRecoveryPass-2026!",
                true,
                "sudo:sysadmin");

        assertEquals("admin", receipt.username());
        assertEquals(1, receipt.revokedSessions());
        assertEquals("offline", receipt.mode());

        JsonArray updatedModules = JsonParser.parseString(
                Files.readString(stateDir.resolve("config.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        String savedPwHash = updatedModules.get(0).getAsJsonObject()
                .getAsJsonArray("users").get(0).getAsJsonObject()
                .get("password").getAsString();
        assertTrue(PasswordHasher.verifyPassword(savedPwHash, "NewRecoveryPass-2026!"));
        assertFalse(PasswordHasher.verifyPassword(savedPwHash, "OldForgottenPassword-123"));

        JsonObject updatedIdentity = JsonParser.parseString(
                Files.readString(moduleData.resolve("oscar-identity.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject adminProfile = updatedIdentity.getAsJsonObject("users").getAsJsonObject("admin");
        assertTrue(adminProfile.get("enabled").getAsBoolean());
        assertTrue(adminProfile.get("mustChangePassword").getAsBoolean());
        assertEquals(1, adminProfile.getAsJsonArray("passkeys").size());
        assertFalse(updatedIdentity.getAsJsonObject("sessions").has("hash_admin_1"));
        assertTrue(updatedIdentity.getAsJsonObject("sessions").has("hash_op_1"));

        String auditLog = Files.readString(
                moduleData.resolve(BreakGlassAdminResetTool.AUDIT_LOG_FILENAME), StandardCharsets.UTF_8);
        assertTrue(auditLog.contains("\"event\":\"BREAK_GLASS_PASSWORD_RESET\""));
        assertTrue(auditLog.contains("\"hostActor\":\"sudo:sysadmin\""));
    }

    @Test
    public void liveResetConsumesRequestUpdatesStoreRevokesSessionsAndWritesAck() throws Exception {
        Path stateDir = tempFolder.newFolder("live-state").toPath();
        Path moduleData = stateDir.resolve(".moduledata");
        Files.createDirectories(moduleData);

        SimpleUserRegistry registry = new SimpleUserRegistry();
        UserConfig adminCfg = new UserConfig();
        adminCfg.userID = "admin";
        adminCfg.name = "Administrator";
        adminCfg.password = PasswordHasher.hashPassword("InitialPass-12345");
        adminCfg.roles.add("admin");
        registry.put("admin", adminCfg);

        OscarIdentityStore store = new OscarIdentityStore(
                null,
                createStubSecurityManager(registry),
                moduleData.resolve("oscar-identity.json"),
                true,
                LoggerFactory.getLogger(BreakGlassAdminResetToolTest.class));

        String oldToken = store.createSession(null, "admin", true);
        assertNotNull(store.validateAndTouchSession(oldToken, 0, 0));

        for (int i = 0; i < 12; i++) {
            store.recordAuthFailure("127.0.0.1", "admin");
        }
        assertTrue(store.isRateLimited("127.0.0.1", "admin"));

        String stagedHash = PasswordHasher.hashPassword("LiveBreakGlass-2026!");
        String reqJson = """
                {
                  "requestId": "req-live-001",
                  "username": "admin",
                  "passwordHash": "%s",
                  "mustChangePassword": false,
                  "hostActor": "WINDOWS\\\\Administrator"
                }
                """.formatted(stagedHash);
        Files.writeString(moduleData.resolve(BreakGlassAdminResetTool.REQUEST_FILENAME), reqJson, StandardCharsets.UTF_8);

        store.consumePendingBreakGlassReset();

        assertFalse(Files.exists(moduleData.resolve(BreakGlassAdminResetTool.REQUEST_FILENAME)));
        assertNull(store.validateAndTouchSession(oldToken, 0, 0));
        assertFalse(store.isRateLimited("127.0.0.1", "admin"));
        assertTrue(PasswordHasher.verifyPassword(
                ((UserConfig) registry.get("admin")).password, "LiveBreakGlass-2026!"));

        Path ackPath = moduleData.resolve(BreakGlassAdminResetTool.ACK_FILENAME);
        assertTrue(Files.isRegularFile(ackPath));
        JsonObject ack = JsonParser.parseString(Files.readString(ackPath, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("req-live-001", ack.get("requestId").getAsString());
        assertEquals(1, ack.get("revokedSessions").getAsInt());

        String audit = Files.readString(
                moduleData.resolve(BreakGlassAdminResetTool.AUDIT_LOG_FILENAME), StandardCharsets.UTF_8);
        assertTrue(audit.contains("\"mode\":\"live\""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShortBreakGlassPasswords() throws Exception {
        Path stateDir = tempFolder.newFolder("short-pw").toPath();
        BreakGlassAdminResetTool.applyOffline(stateDir, "admin", "too-short", false, "root");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFederationServiceAccounts() throws Exception {
        Path stateDir = tempFolder.newFolder("svc-fed").toPath();
        BreakGlassAdminResetTool.applyOffline(stateDir, "svc-fed-1234", "ValidLongPassword-2026!", false, "root");
    }

    private static final class SimpleUserRegistry extends ConcurrentSkipListMap<String, IUserInfo>
            implements IUserRegistry {
        @Override
        public void addAllowPermissions(String id, Collection<IPermissionPath> permissions) {}

        @Override
        public void addDenyPermissions(String id, Collection<IPermissionPath> permissions) {}

        @Override
        public void removeAllowPermissions(String id, Collection<IPermissionPath> permissions) {}

        @Override
        public void removeDenyPermissions(String id, Collection<IPermissionPath> permissions) {}
    }

    private static ISecurityManager createStubSecurityManager(IUserRegistry registry) {
        return (ISecurityManager) Proxy.newProxyInstance(
                ISecurityManager.class.getClassLoader(),
                new Class<?>[] { ISecurityManager.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUserRegistry" -> registry;
                    case "getUserInfo" -> registry.get(args[0]);
                    case "isAccessControlEnabled", "isAuthorized" -> true;
                    default -> null;
                });
    }
}
