package io.github.liliangxu.phoneagent.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import io.github.liliangxu.phoneagent.codex.CodexPhoneBridgeService;

@Component
public class BlfStartupSyncRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BlfStartupSyncRunner.class);

    private final TaskService taskService;
    private final CodexPhoneBridgeService bridgeService;

    public BlfStartupSyncRunner(TaskService taskService, CodexPhoneBridgeService bridgeService) {
        this.taskService = taskService;
        this.bridgeService = bridgeService;
    }

    /**
     * Restores phone task state and refreshes GXP1630 Eventlist BLF state after
     * the application is ready. A failed startup pass should not prevent the API
     * from booting; operators can retry BLF refresh through /internal/admin/blf/sync
     * after AMI recovers.
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            taskService.recoverLoadedState();
            bridgeService.recoverIncompleteBridges();
        } catch (RuntimeException e) {
            log.warn("Startup phone state recovery failed; queued state remains in MySQL for manual recovery", e);
        }
        try {
            taskService.syncAllSlots("startup");
        } catch (RuntimeException e) {
            log.warn("Startup BLF sync failed; retry with /internal/admin/blf/sync after AMI is available", e);
        }
    }
}
