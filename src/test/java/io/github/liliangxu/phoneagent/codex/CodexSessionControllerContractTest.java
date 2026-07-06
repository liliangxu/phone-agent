package io.github.liliangxu.phoneagent.codex;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.liliangxu.phoneagent.codex.CodexConsoleContractSupport.codexClass;
import static io.github.liliangxu.phoneagent.codex.CodexConsoleContractSupport.exposedProperties;
import static io.github.liliangxu.phoneagent.codex.CodexConsoleContractSupport.mappingMethods;
import static io.github.liliangxu.phoneagent.codex.CodexConsoleContractSupport.mappingPaths;

class CodexSessionControllerContractTest {
    @Test
    void controllerPublishesDesignedSessionApiRoutes() {
        Class<?> controllerType = codexClass("CodexSessionController");

        assertTrue(controllerType.isAnnotationPresent(RestController.class),
                "CodexSessionController must be a REST controller");
        assertTrue(mappingPaths(controllerType).contains("/api/codex-sessions"),
                "GET list and POST create must share /api/codex-sessions");
        assertTrue(mappingPaths(controllerType).contains("/api/codex-sessions/{id}"),
                "GET detail must use /api/codex-sessions/{id}");
        assertTrue(mappingMethods(controllerType, "/api/codex-sessions").contains("GET"),
                "Session list endpoint must be GET /api/codex-sessions");
        assertTrue(mappingMethods(controllerType, "/api/codex-sessions").contains("POST"),
                "Session create endpoint must be POST /api/codex-sessions");
        assertEquals(Set.of("GET"), mappingMethods(controllerType, "/api/codex-sessions/{id}"),
                "Session detail endpoint must be GET-only");
    }

    @Test
    void consoleControllerPublishesStableConsoleEntrypoints() {
        Class<?> controllerType = codexClass("CodexConsoleController");

        assertTrue(controllerType.isAnnotationPresent(Controller.class),
                "CodexConsoleController must be a Spring MVC controller");
        assertTrue(mappingPaths(controllerType).containsAll(Set.of("/", "/console", "/console/")),
                "Console must support / as the default page plus /console and /console/ entrypoints");
        assertEquals(Set.of("GET"), mappingMethods(controllerType, "/"),
                "Default root page must be GET-only");
        assertEquals(Set.of("GET"), mappingMethods(controllerType, "/console"),
                "Console entrypoint must be GET-only");
        assertEquals(Set.of("GET"), mappingMethods(controllerType, "/console/"),
                "Console slash entrypoint must be GET-only");
    }

    @Test
    void createRequestOnlyAcceptsUserEditableFields() {
        Class<?> requestType = codexClass("CreateCodexSessionRequest");

        assertEquals(Set.of("title", "cwd", "initialPrompt"), exposedProperties(requestType),
                "Create request must not accept session id, tmux name, ttyd port, or file paths from users");
    }

    @Test
    void sessionViewExposesRegistryAndDetectionFieldsWithoutCommandSecrets() {
        Class<?> viewType = codexClass("CodexSessionView");

        assertTrue(exposedProperties(viewType).containsAll(Set.of(
                "id",
                "title",
                "cwd",
                "status",
                "tmuxName",
                "ttydUrl",
                "threadId",
                "threadShortId",
                "jsonlPath",
                "lastAssistantMessage",
                "errorMessage",
                "createdAt",
                "updatedAt"
        )));
        assertTrue(exposedProperties(viewType).stream().noneMatch(name -> name.toLowerCase().contains("environment")),
                "API view must not expose environment variables or command environment details");
        assertTrue(exposedProperties(viewType).stream().noneMatch(name -> name.equals("command") || name.equals("commandLine")),
                "API view must not expose full command lines");
    }
}
