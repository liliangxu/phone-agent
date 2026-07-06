package io.github.liliangxu.phoneagent.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskServiceBlfSyncContractTest {
    @TempDir
    Path runtimeDir;

    @Test
    void syncAllSlotsRefreshesIdleSlotsWithInUseThenNotInUse() throws Exception {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                amiClient,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );

        invokeSyncAllSlots(service, "manual");

        assertEquals(List.of(
                "INUSE:1", "NOT_INUSE:1",
                "INUSE:2", "NOT_INUSE:2",
                "INUSE:3", "NOT_INUSE:3",
                "INUSE:4", "NOT_INUSE:4",
                "INUSE:5", "NOT_INUSE:5",
                "INUSE:6", "NOT_INUSE:6",
                "INUSE:7", "NOT_INUSE:7",
                "INUSE:8", "NOT_INUSE:8"
        ), amiClient.commands);
    }

    @Test
    void syncAllSlotsRefreshesOccupiedSlotsWithNotInUseThenInUseWithoutMutatingState() throws Exception {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                amiClient,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        for (int i = 1; i <= 9; i++) {
            service.createTask("通知 " + i);
        }
        TaskView firstBefore = service.getTask("task-20260609-000001").orElseThrow();
        TaskView ninthBefore = service.getTask("task-20260609-000009").orElseThrow();
        amiClient.commands.clear();

        invokeSyncAllSlots(service, "manual");

        assertEquals("NOT_INUSE:1", amiClient.commands.get(0));
        assertEquals("INUSE:1", amiClient.commands.get(1));
        assertEquals("NOT_INUSE:8", amiClient.commands.get(14));
        assertEquals("INUSE:8", amiClient.commands.get(15));

        TaskView firstAfter = service.getTask(firstBefore.taskId()).orElseThrow();
        assertEquals(TaskStatus.NOTIFIED, firstAfter.status());
        assertEquals(1, firstAfter.slot());

        TaskView ninthAfter = service.getTask(ninthBefore.taskId()).orElseThrow();
        assertEquals(TaskStatus.QUEUED, ninthAfter.status());
        assertNull(ninthAfter.slot());
    }

    @Test
    void syncAllSlotsRejectsConcurrentSyncWithoutMutatingTaskState() throws Exception {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        amiClient.blockInUseCommand(1);
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                amiClient,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstSync = executor.submit(() -> service.syncAllSlots("first"));
            amiClient.awaitBlockedInUse();

            assertThrows(BlfSyncInProgressException.class, () -> service.syncAllSlots("second"));
            assertEquals(1, amiClient.count("INUSE:1"));
            assertEquals(0, amiClient.count("NOT_INUSE:1"));

            amiClient.unblockInUse();
            firstSync.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(List.of(
                "INUSE:1", "NOT_INUSE:1",
                "INUSE:2", "NOT_INUSE:2",
                "INUSE:3", "NOT_INUSE:3",
                "INUSE:4", "NOT_INUSE:4",
                "INUSE:5", "NOT_INUSE:5",
                "INUSE:6", "NOT_INUSE:6",
                "INUSE:7", "NOT_INUSE:7",
                "INUSE:8", "NOT_INUSE:8"
        ), amiClient.commands);
    }

    private static void invokeSyncAllSlots(TaskService service, String reason) throws Exception {
        Method syncAllSlots = TaskService.class.getDeclaredMethod("syncAllSlots", String.class);
        syncAllSlots.setAccessible(true);
        syncAllSlots.invoke(service, reason);
    }
}
