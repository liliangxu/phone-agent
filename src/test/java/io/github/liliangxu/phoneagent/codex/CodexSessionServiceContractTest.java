package io.github.liliangxu.phoneagent.codex;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.liliangxu.phoneagent.codex.CodexConsoleContractSupport.codexClass;

class CodexSessionServiceContractTest {
    @Test
    void statusEnumMatchesPrdAndDesignNames() {
        Class<?> statusType = codexClass("CodexSessionStatus");

        assertTrue(statusType.isEnum(), "CodexSessionStatus must be an enum");
        assertEquals(Set.of("CREATING", "CREATE_FAILED", "IDLE", "RUNNING", "WAITING_USER", "COMPLETED"),
                Arrays.stream(statusType.getEnumConstants())
                        .map(value -> ((Enum<?>) value).name())
                        .collect(Collectors.toSet()));
    }

    @Test
    void statusEnumMapsLegacyPersistedValues() {
        assertEquals(CodexSessionStatus.CREATING, CodexSessionStatus.fromPersisted("STARTING"));
        assertEquals(CodexSessionStatus.CREATE_FAILED, CodexSessionStatus.fromPersisted("FAILED"));
        assertEquals(CodexSessionStatus.IDLE, CodexSessionStatus.fromPersisted("TERMINAL_UNAVAILABLE"));
        assertEquals(CodexSessionStatus.IDLE, CodexSessionStatus.fromPersisted("STOPPED"));
        assertEquals(CodexSessionStatus.COMPLETED, CodexSessionStatus.fromPersisted("HANDLED"));
        assertEquals(CodexSessionStatus.IDLE, CodexSessionStatus.fromPersisted("UNKNOWN_FROM_OLD_BUILD"));
        assertEquals(CodexSessionStatus.RUNNING, CodexSessionStatus.fromPersisted("RUNNING"));
    }

    @Test
    void serviceKeepsValidationInsideBackendBoundary() {
        Class<?> serviceType = codexClass("CodexSessionService");
        Class<?> requestType = codexClass("CreateCodexSessionRequest");

        assertTrue(hasMethodAccepting(serviceType, requestType),
                "CodexSessionService must expose a create method accepting CreateCodexSessionRequest");
        assertTrue(hasPathReturningOrAcceptingMethod(serviceType),
                "CodexSessionService must expose canonical Path based validation so cwd and allowed roots use real paths");
        assertTrue(Arrays.stream(serviceType.getDeclaredMethods()).anyMatch(method ->
                        method.getName().toLowerCase().contains("validate")
                                || method.getName().toLowerCase().contains("canonical")),
                "Service should keep cwd/title/prompt validation explicit and testable");
    }

    @Test
    void processGatewayExposesLivenessChecksRequiredByTerminalSafetyRules() {
        Class<?> gatewayType = codexClass("CodexProcessGateway");
        Set<String> methodNames = Arrays.stream(gatewayType.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertTrue(methodNames.stream().anyMatch(name -> name.toLowerCase().contains("tmux")),
                "CodexProcessGateway must let the poller check whether the tmux session still exists");
        assertTrue(methodNames.stream().anyMatch(name -> name.toLowerCase().contains("ttyd")),
                "CodexProcessGateway must let the poller validate the ttyd process");
        assertTrue(methodNames.stream().anyMatch(name -> name.toLowerCase().contains("command")),
                "CodexProcessGateway must verify tmux/ttyd/codex command availability before create");
    }

    @Test
    void pollerDocumentsJsonlRefreshThroughFocusedEntryPoint() {
        Class<?> pollerType = codexClass("CodexSessionPoller");

        assertTrue(Arrays.stream(pollerType.getDeclaredMethods()).anyMatch(method ->
                        method.getName().toLowerCase().contains("poll")
                                || method.getName().toLowerCase().contains("refresh")),
                "CodexSessionPoller must have a focused entry point for JSONL state refresh");
    }

    private static boolean hasMethodAccepting(Class<?> type, Class<?> parameterType) {
        return Arrays.stream(type.getMethods())
                .anyMatch(method -> Arrays.asList(method.getParameterTypes()).contains(parameterType));
    }

    private static boolean hasPathReturningOrAcceptingMethod(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .anyMatch(method -> method.getReturnType() == Path.class
                        || Arrays.asList(method.getParameterTypes()).contains(Path.class));
    }
}
