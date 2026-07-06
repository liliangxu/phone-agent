package io.github.liliangxu.phoneagent.task;

import org.junit.jupiter.api.Test;
import io.github.liliangxu.phoneagent.codex.CodexPhoneBridgeService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BlfStartupSyncRunnerTest {
    @Test
    void invokesStartupSyncWithoutFailingApplicationWhenAmiIsUnavailable() {
        TaskService taskService = mock(TaskService.class);
        CodexPhoneBridgeService bridgeService = mock(CodexPhoneBridgeService.class);
        doThrow(new AsteriskControlException("AMI unavailable")).when(taskService).syncAllSlots("startup");
        BlfStartupSyncRunner runner = new BlfStartupSyncRunner(taskService, bridgeService);

        assertDoesNotThrow(() -> runner.run(null));

        verify(taskService).recoverLoadedState();
        verify(bridgeService).recoverIncompleteBridges();
        verify(taskService).syncAllSlots("startup");
    }
}
