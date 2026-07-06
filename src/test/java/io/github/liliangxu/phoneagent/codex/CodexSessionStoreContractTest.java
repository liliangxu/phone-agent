package io.github.liliangxu.phoneagent.codex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.liliangxu.phoneagent.codex.CodexConsoleContractSupport.constructAny;
import static io.github.liliangxu.phoneagent.codex.CodexConsoleContractSupport.invokeAny;
import static io.github.liliangxu.phoneagent.codex.CodexConsoleContractSupport.newRecordLike;
import static io.github.liliangxu.phoneagent.codex.CodexConsoleContractSupport.property;

class CodexSessionStoreContractTest {
    @TempDir
    Path registryDir;

    @Test
    void loadIgnoresTmpAndBadJsonFiles() throws Exception {
        Files.createDirectories(registryDir);
        Files.writeString(registryDir.resolve("ignored.json.tmp"), "{\"id\":\"tmp\"}", StandardCharsets.UTF_8);
        Files.writeString(registryDir.resolve("broken.json"), "{bad json", StandardCharsets.UTF_8);

        Object store = newStore(registryDir);
        Object sessions = invokeList(store);

        assertTrue(((List<?>) sessions).isEmpty(),
                "Store startup load must ignore .tmp and bad JSON instead of adding corrupt records to memory");
    }

    @Test
    void saveUsesStoreSnapshotWithoutRewritingLegacyJsonFiles() throws Exception {
        Object store = newStore(registryDir);
        Object record = newRecordLike("CodexSessionRecord", Map.of(
                "id", "cs-20260611-000001",
                "title", "phone-agent",
                "threadId", "019e1234"
        ));

        invokeAny(store, new String[]{"save", "create", "upsert", "put"}, record);

        Path json = registryDir.resolve("cs-20260611-000001.json");
        assertFalse(Files.exists(json), "MySQL-backed store must not write new JSON registry files");
        assertFalse(Files.exists(registryDir.resolve("cs-20260611-000001.json.tmp")),
                "Store must not leave same-session .tmp files behind");
        List<?> sessions = (List<?>) invokeList(store);
        assertEquals(1, sessions.size());
        assertEquals("cs-20260611-000001", property(sessions.getFirst(), "id"));
    }

    @Test
    void mergeUpdatePreservesFieldsNotOwnedByTheUpdater() {
        Object store = newStore(registryDir);
        Map<String, Object> baseOverrides = new LinkedHashMap<>();
        baseOverrides.put("id", "cs-20260611-000002");
        baseOverrides.put("title", "create-flow-title");
        baseOverrides.put("ttydUrl", "http://127.0.0.1:49152/");
        baseOverrides.put("threadId", null);
        Object base = newRecordLike("CodexSessionRecord", baseOverrides);
        invokeAny(store, new String[]{"save", "create", "upsert", "put"}, base);

        Object merged = invokeMerge(store, "cs-20260611-000002", Map.of(
                "threadId", "019e-merged",
                "lastAssistantMessage", "Waiting for input"
        ));

        assertEquals("create-flow-title", property(merged, "title"),
                "Poller merge must not overwrite create-flow fields with null or stale values");
        assertEquals("http://127.0.0.1:49152/", property(merged, "ttydUrl"));
        assertEquals("019e-merged", property(merged, "threadId"));
        assertEquals("Waiting for input", property(merged, "lastAssistantMessage"));
    }

    private static Object newStore(Path registryDir) {
        Object objectMapper = optionalObjectMapper();
        if (objectMapper != null) {
            try {
                return constructAny("CodexSessionStore", registryDir, objectMapper);
            } catch (AssertionError ignored) {
                try {
                    return constructAny("CodexSessionStore", objectMapper, registryDir);
                } catch (AssertionError ignoredAgain) {
                    // Fall through to the no-ObjectMapper constructor shapes.
                }
            }
        }
        return constructAny("CodexSessionStore", registryDir);
    }

    private static Object optionalObjectMapper() {
        try {
            Class<?> objectMapperType = Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
            return objectMapperType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static Object invokeList(Object store) {
        Object sessions = invokeAny(store, new String[]{"list", "listSessions", "findAll", "all"});
        assertNotNull(sessions, "Store list method must return an immutable snapshot, not null");
        return sessions;
    }

    private static Object invokeMerge(Object store, String id, Map<String, Object> changes) {
        try {
            return invokeAny(store, new String[]{"merge", "update", "updateSession"}, id, changes);
        } catch (AssertionError ignored) {
            Object patch = newRecordLike("CodexSessionRecord", Map.of(
                    "id", id,
                    "threadId", changes.get("threadId"),
                    "lastAssistantMessage", changes.get("lastAssistantMessage")
            ));
            try {
                return invokeAny(store, new String[]{"merge", "update", "updateSession"}, patch);
            } catch (AssertionError ignoredAgain) {
                invokeAny(store, new String[]{"save", "create", "upsert", "put"}, patch);
                return invokeAny(store, new String[]{"get", "find", "findById"}, id);
            }
        }
    }
}
