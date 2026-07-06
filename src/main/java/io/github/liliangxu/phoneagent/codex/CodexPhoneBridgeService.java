package io.github.liliangxu.phoneagent.codex;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;
import io.github.liliangxu.phoneagent.task.TaskCreationException;
import io.github.liliangxu.phoneagent.task.TaskService;
import io.github.liliangxu.phoneagent.task.TaskStatus;
import io.github.liliangxu.phoneagent.task.TaskView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable bridge between managed Codex completed turns and phone task
 * reminders. The service owns dedupe, session-level bridge errors, manual
 * cancel/renotify, and tmux reply after ASR.
 */
@Service
public class CodexPhoneBridgeService {
    private final JdbcCodexPhoneBridgeRepository bridgeRepository;
    private final CodexSessionStore sessionStore;
    private final TaskService taskService;
    private final CodexProcessGateway processGateway;
    private final CodexPromptFormatter promptFormatter;
    private final PhoneAgentProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public CodexPhoneBridgeService(
            JdbcCodexPhoneBridgeRepository bridgeRepository,
            @Lazy CodexSessionStore sessionStore,
            @Lazy TaskService taskService,
            CodexProcessGateway processGateway,
            CodexPromptFormatter promptFormatter,
            PhoneAgentProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.bridgeRepository = bridgeRepository;
        this.sessionStore = sessionStore;
        this.taskService = taskService;
        this.processGateway = processGateway;
        this.promptFormatter = promptFormatter;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void reconcileWaitingSession(CodexSessionRecord session) {
        if (session == null || session.getStatus() != CodexSessionStatus.COMPLETED) {
            clearPhoneErrorIfNeeded(session);
            return;
        }
        if (blank(session.getThreadId())) {
            setPhoneError(session.getId(), "WAITING_EVENT_THREAD_MISSING", "Codex thread id has not been detected yet.");
            return;
        }
        String waitingEventKey = waitingEventKey(session);
        if (waitingEventKey == null) {
            setPhoneError(session.getId(), "WAITING_EVENT_KEY_MISSING", "Codex waiting event key cannot be computed yet.");
            return;
        }
        clearPhoneError(session.getId());
        CodexPhoneBridgeRecord bridge = transactionTemplate.execute(status -> bridgeRepository
                .findByWaitingEventForUpdate(session.getId(), session.getThreadId(), waitingEventKey)
                .orElseGet(() -> createBridge(session, waitingEventKey)));
        if (bridge != null && bridge.taskId() == null && bridge.status() == BridgeStatus.WAITING_DETECTED) {
            claimBridgeForTask(bridge.bridgeId()).ifPresent(this::createTaskForBridge);
        }
    }

    public void onBridgeTaskCreated(String bridgeId, String taskId) {
        Boolean bound = transactionTemplate.execute(status -> {
            Optional<CodexPhoneBridgeRecord> found = bridgeRepository.findByIdForUpdate(bridgeId);
            if (found.isEmpty()) {
                return false;
            }
            CodexPhoneBridgeRecord bridge = found.get();
            if (taskId.equals(bridge.taskId())) {
                return true;
            }
            if (bridge.taskId() == null && bridge.status() == BridgeStatus.TASK_CREATED && bridge.cancelledAt() == null) {
                bridgeRepository.update(copy(bridge, BridgeStatus.TASK_CREATED, taskId, bridge.replacedTaskId(), bridge.slot(),
                        bridge.replyText(), null, null, bridge.cancelledAt(), now()));
                return true;
            }
            return false;
        });
        if (!Boolean.TRUE.equals(bound)) {
            throw new IllegalStateException("Bridge task ownership could not be bound before phone side effects");
        }
    }

    public void onTaskNotified(String taskId, Integer slot) {
        updateByTask(taskId, BridgeStatus.NOTIFIED, slot, null, null);
    }

    public void onTaskPickedUp(String taskId) {
        updateByTask(taskId, BridgeStatus.PICKED_UP, null, null, null);
    }

    public void onTaskRecording(String taskId) {
        updateByTask(taskId, BridgeStatus.RECORDING, null, null, null);
    }

    public void onTaskRecorded(String taskId) {
        updateByTask(taskId, BridgeStatus.TRANSCRIBING, null, null, null);
    }

    public void onTaskFailure(String taskId, TaskStatus status, String message) {
        BridgeStatus bridgeStatus = switch (status) {
            case FAILED_RECORDING -> BridgeStatus.FAILED_RECORDING;
            case FAILED_ASR -> BridgeStatus.FAILED_ASR;
            case FAILED_BLF_NOTIFY -> BridgeStatus.FAILED_BLF_NOTIFY;
            case FAILED_TASK_CREATE -> BridgeStatus.FAILED_TASK_CREATE;
            case FAILED_CODEX_SESSION_STOPPED -> BridgeStatus.FAILED_CODEX_SESSION_STOPPED;
            case FAILED_REPLY_TO_CODEX -> BridgeStatus.FAILED_REPLY_TO_CODEX;
            default -> null;
        };
        if (bridgeStatus != null) {
            updateByTask(taskId, bridgeStatus, null, bridgeStatus.name(), message)
                    .filter(CodexPhoneBridgeRecord::terminal)
                    .ifPresent(this::markSessionCompletedIfCurrent);
        }
    }

    public void onTaskAsrSuccess(String taskId, String replyText) {
        CodexPhoneBridgeRecord bridge = transactionTemplate.execute(status -> {
            Optional<CodexPhoneBridgeRecord> found = bridgeRepository.findByTaskIdForUpdate(taskId);
            if (found.isEmpty()) {
                return null;
            }
            CodexPhoneBridgeRecord foundBridge = found.get();
            if (foundBridge.terminal()) {
                return null;
            }
            CodexPhoneBridgeRecord updated = copy(foundBridge, BridgeStatus.ASR_DONE, foundBridge.taskId(), foundBridge.replacedTaskId(),
                    foundBridge.slot(), replyText, null, null, foundBridge.cancelledAt(), now());
            bridgeRepository.update(updated);
            return updated;
        });
        if (bridge == null) {
            return;
        }
        replyToCodex(bridge);
    }

    /**
     * Records that the phone call produced no usable speech. This is terminal
     * for the current call attempt, but unlike ASR failure it is operator
     * retryable and must not paste anything back into Codex.
     */
    public void onTaskNoReply(String taskId) {
        CodexPhoneBridgeRecord updated = transactionTemplate.execute(status -> {
            Optional<CodexPhoneBridgeRecord> found = bridgeRepository.findByTaskIdForUpdate(taskId);
            if (found.isEmpty()) {
                return null;
            }
            CodexPhoneBridgeRecord bridge = found.get();
            if (bridge.terminal()) {
                return bridge;
            }
            CodexPhoneBridgeRecord noReply = copy(bridge, BridgeStatus.NO_REPLY, bridge.taskId(), bridge.replacedTaskId(),
                    bridge.slot(), null, null, null, bridge.cancelledAt(), now());
            bridgeRepository.update(noReply);
            return noReply;
        });
        if (updated != null && updated.status() == BridgeStatus.NO_REPLY) {
            markSessionCompletedIfCurrent(updated);
        }
    }

    public List<BridgeSummary> latestSummaries(String sessionId) {
        return bridgeRepository.findLatestForSession(sessionId, 5).stream()
                .map(BridgeSummary::from)
                .toList();
    }

    public Optional<BridgeSummary> activeBridge(String sessionId) {
        return bridgeRepository.findLatestForSession(sessionId, 10).stream()
                .filter(bridge -> !bridge.terminal())
                .findFirst()
                .map(BridgeSummary::from);
    }

    /**
     * Returns true once a phone reply has started resolving a Codex waiting
     * event. Codex JSONL may still expose the old waiting marker for a short
     * period after tmux paste, so polling must not re-open the same event.
     */
    boolean waitingEventReplyInProgressOrDone(String sessionId, String threadId, String waitingEventKey) {
        if (blank(sessionId) || blank(threadId) || blank(waitingEventKey)) {
            return false;
        }
        return bridgeRepository.findByWaitingEvent(sessionId, threadId, waitingEventKey)
                .map(bridge -> switch (bridge.status()) {
                    case ASR_DONE, REPLYING_TO_CODEX, REPLIED_TO_CODEX -> true;
                    default -> false;
                })
                .orElse(false);
    }

    boolean waitingEventCompleted(String sessionId, String threadId, String waitingEventKey) {
        if (blank(sessionId) || blank(threadId) || blank(waitingEventKey)) {
            return false;
        }
        return bridgeRepository.findByWaitingEvent(sessionId, threadId, waitingEventKey)
                .map(bridge -> bridge.status() == BridgeStatus.NO_REPLY || bridge.status() == BridgeStatus.CANCELLED)
                .orElse(false);
    }

    public BridgeSummary cancel(String bridgeId) {
        BridgeCancelResult result = transactionTemplate.execute(status -> {
            CodexPhoneBridgeRecord bridge = bridgeRepository.findByIdForUpdate(bridgeId).orElseThrow(() -> new CodexSessionException(
                    "BRIDGE_NOT_FOUND", "Bridge not found: " + bridgeId, org.springframework.http.HttpStatus.NOT_FOUND));
            if (!bridge.cancellable()) {
                throw new CodexSessionException("BRIDGE_NOT_CANCELLABLE", "Bridge is not cancellable", org.springframework.http.HttpStatus.CONFLICT);
            }
            CodexPhoneBridgeRecord cancelled = copy(bridge, BridgeStatus.CANCELLED, bridge.taskId(), bridge.replacedTaskId(), null,
                    bridge.replyText(), null, null, now(), now());
            bridgeRepository.update(cancelled);
            return new BridgeCancelResult(cancelled, bridge.taskId(), null);
        });
        if (result.taskId() != null) {
            result = new BridgeCancelResult(result.bridge(), result.taskId(),
                    taskService.cancelTaskLogically(result.taskId()).orElse(null));
        }
        if (result.cancelResult() != null) {
            taskService.completeCancelSideEffects(result.cancelResult());
        }
        markSessionCompletedIfCurrent(result.bridge());
        return BridgeSummary.from(result.bridge());
    }

    public BridgeSummary renotify(String bridgeId) {
        CodexPhoneBridgeRecord current = bridgeRepository.findById(bridgeId).orElseThrow(() -> new CodexSessionException(
                "BRIDGE_NOT_FOUND", "Bridge not found: " + bridgeId, org.springframework.http.HttpStatus.NOT_FOUND));
        Optional<TaskView> previousBeforeClaim = current.taskId() == null ? Optional.empty() : taskService.getTask(current.taskId());
        boolean previousHasAudio = current.taskId() != null && taskService.hasTaskAudio(current.taskId());
        CodexPhoneBridgeRecord bridge = claimBridgeForRenotify(bridgeId, previousBeforeClaim, previousHasAudio);
        if (!bridge.renotifyAllowed()) {
            throw new CodexSessionException("BRIDGE_RENOTIFY_NOT_ALLOWED", "Bridge cannot be renotified", org.springframework.http.HttpStatus.CONFLICT);
        }
        String previousTaskId = bridge.taskId();
        if (previousTaskId != null) {
            Optional<TaskView> previous = previousBeforeClaim.filter(task -> previousTaskId.equals(task.taskId()))
                    .or(() -> taskService.getTask(previousTaskId));
            if (previous.isPresent() && live(previous.get().status())) {
                throw new CodexSessionException("BRIDGE_HAS_ACTIVE_TASK", "Bridge already has an active phone task", org.springframework.http.HttpStatus.CONFLICT);
            }
            if (previous.isPresent()
                    && previous.get().status() == TaskStatus.FAILED_BLF_NOTIFY
                    && taskService.hasTaskAudio(previousTaskId)
                    && previous.get().taskId() != null) {
                try {
                    TaskView retried = taskService.retryTask(previousTaskId);
                    CodexPhoneBridgeRecord updated = updateBridgeAfterTask(bridge, retried, bridge.replacedTaskId(), null, null, null);
                    markSessionWaitingIfBridgeLive(updated);
                    return BridgeSummary.from(updated);
                } catch (TaskCreationException e) {
                    CodexPhoneBridgeRecord updated = updateBridgeAfterTask(bridge, e.task(), bridge.replacedTaskId(), null,
                            bridgeStatusForTask(e.task().status()).name(), e.task().errorMessage());
                    return BridgeSummary.from(updated);
                }
            }
        }
        try {
            TaskView replacement = taskService.createBridgeTask(bridge.bridgeId(), bridge.lastAssistantMessage());
            CodexPhoneBridgeRecord updated = updateBridgeAfterTask(bridge, replacement, previousTaskId, null, null, null);
            markSessionWaitingIfBridgeLive(updated);
            return BridgeSummary.from(updated);
        } catch (TaskCreationException e) {
            CodexPhoneBridgeRecord updated = updateBridgeAfterTask(bridge, e.task(), previousTaskId, null,
                    bridgeStatusForTask(e.task().status()).name(), e.task().errorMessage());
            return BridgeSummary.from(updated);
        }
    }

    /**
     * Completes bridge recovery after application startup. TaskService restores
     * task and BLF state first; this pass covers bridges that were inserted but
     * did not yet receive a task id before the previous process stopped.
     */
    public void recoverIncompleteBridges() {
        for (CodexPhoneBridgeRecord bridge : bridgeRepository.findByStatuses(List.of(BridgeStatus.WAITING_DETECTED, BridgeStatus.TASK_CREATED))) {
            CodexPhoneBridgeRecord locked = transactionTemplate.execute(status -> bridgeRepository.findByIdForUpdate(bridge.bridgeId()).orElse(null));
            if (locked == null || locked.taskId() != null) {
                continue;
            }
            if (locked.status() == BridgeStatus.TASK_CREATED) {
                transactionTemplate.executeWithoutResult(status -> bridgeRepository.update(copy(locked, BridgeStatus.FAILED_TASK_CREATE,
                        null, locked.replacedTaskId(), locked.slot(), locked.replyText(), "FAILED_TASK_CREATE",
                        "Bridge reached TASK_CREATED without a persisted phone task", locked.cancelledAt(), now())));
            } else if (locked.status() == BridgeStatus.WAITING_DETECTED && currentCompletedOrWaitingSessionMatches(locked)) {
                claimBridgeForTask(locked.bridgeId()).ifPresent(this::createTaskForBridge);
            }
        }
    }

    private CodexPhoneBridgeRecord createBridge(CodexSessionRecord session, String waitingEventKey) {
        OffsetDateTime now = now();
        return bridgeRepository.insert(new CodexPhoneBridgeRecord(
                "bridge-" + UUID.randomUUID(),
                session.getId(),
                session.getThreadId(),
                waitingEventKey,
                session.getLastAssistantMessage(),
                BridgeStatus.WAITING_DETECTED,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        ));
    }

    private void createTaskForBridge(CodexPhoneBridgeRecord bridge) {
        try {
            TaskView task = taskService.createBridgeTask(bridge.bridgeId(), bridge.lastAssistantMessage());
            CodexPhoneBridgeRecord updated = updateBridgeAfterTask(bridge, task, bridge.replacedTaskId(), null, null, null);
            markSessionWaitingIfBridgeLive(updated);
        } catch (TaskCreationException e) {
            updateBridgeAfterTask(bridge, e.task(), bridge.replacedTaskId(), bridge.cancelledAt(),
                    bridgeStatusForTask(e.task().status()).name(), e.task().errorMessage());
        } catch (RuntimeException e) {
            transactionTemplate.executeWithoutResult(status -> bridgeRepository.update(copy(bridge, BridgeStatus.FAILED_TASK_CREATE, bridge.taskId(),
                    bridge.replacedTaskId(), bridge.slot(), null, "FAILED_TASK_CREATE", e.getMessage(), bridge.cancelledAt(), now())));
        }
    }

    /**
     * Claims a bridge before external task side effects begin. The short
     * transaction prevents concurrent poll/recovery requests from creating
     * multiple live phone tasks for the same waiting event.
     */
    private Optional<CodexPhoneBridgeRecord> claimBridgeForTask(String bridgeId) {
        return transactionTemplate.execute(status -> {
            Optional<CodexPhoneBridgeRecord> found = bridgeRepository.findByIdForUpdate(bridgeId);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            CodexPhoneBridgeRecord bridge = found.get();
            if (bridge.taskId() != null || bridge.status() != BridgeStatus.WAITING_DETECTED) {
                return Optional.empty();
            }
            CodexPhoneBridgeRecord claimed = copy(bridge, BridgeStatus.TASK_CREATED, null, bridge.replacedTaskId(), bridge.slot(),
                    bridge.replyText(), null, null, bridge.cancelledAt(), now());
            bridgeRepository.update(claimed);
            return Optional.of(claimed);
        });
    }

    /**
     * Claims manual renotify before retry/replacement task side effects begin.
     * The returned snapshot still carries the previous status so validation and
     * retry strategy can use the original bridge state, while the database row
     * is no longer renotify-eligible for concurrent requests.
     */
    private CodexPhoneBridgeRecord claimBridgeForRenotify(String bridgeId, Optional<TaskView> previous, boolean previousHasAudio) {
        return transactionTemplate.execute(status -> {
            CodexPhoneBridgeRecord bridge = bridgeRepository.findByIdForUpdate(bridgeId).orElseThrow(() -> new CodexSessionException(
                    "BRIDGE_NOT_FOUND", "Bridge not found: " + bridgeId, org.springframework.http.HttpStatus.NOT_FOUND));
            if (!bridge.renotifyAllowed()) {
                return bridge;
            }
            if (previous.isPresent() && live(previous.get().status())) {
                return bridge;
            }
            boolean retrySameTask = bridge.status() == BridgeStatus.FAILED_BLF_NOTIFY
                    && previous.isPresent()
                    && previous.get().status() == TaskStatus.FAILED_BLF_NOTIFY
                    && previousHasAudio;
            String claimedTaskId = retrySameTask ? bridge.taskId() : null;
            String claimedReplacedTaskId = retrySameTask ? bridge.replacedTaskId() : bridge.taskId();
            CodexPhoneBridgeRecord claimed = copy(bridge, BridgeStatus.TASK_CREATED, claimedTaskId, claimedReplacedTaskId, bridge.slot(),
                    bridge.replyText(), null, null, null, now());
            bridgeRepository.update(claimed);
            return bridge;
        });
    }

    private void replyToCodex(CodexPhoneBridgeRecord bridge) {
        Optional<CodexSessionRecord> session = sessionStore.get(bridge.codexSessionId());
        if (session.isEmpty() || !processGateway.hasTmuxSession(properties.getCodex().getTmuxCommand(), session.get().getTmuxName())) {
            CodexPhoneBridgeRecord failed = copy(bridge, BridgeStatus.FAILED_CODEX_SESSION_STOPPED, bridge.taskId(), bridge.replacedTaskId(), bridge.slot(), bridge.replyText(),
                    "FAILED_CODEX_SESSION_STOPPED", "Codex tmux session is not available", null, now());
            bridgeRepository.update(failed);
            taskService.markReplyFailure(bridge.taskId(), TaskStatus.FAILED_CODEX_SESSION_STOPPED, "Codex tmux session is not available");
            markSessionCompletedIfCurrent(failed);
            return;
        }
        try {
            if (!currentWaitingSessionMatches(bridge)) {
                bridgeRepository.update(copy(bridge, BridgeStatus.CANCELLED, bridge.taskId(), bridge.replacedTaskId(), bridge.slot(), bridge.replyText(),
                        "WAITING_EVENT_STALE", "Codex session already moved past this waiting event", now(), now()));
                taskService.cancelTaskLogically(bridge.taskId()).ifPresent(taskService::completeCancelSideEffects);
                return;
            }
            taskService.markReplying(bridge.taskId());
            CodexPhoneBridgeRecord replying = copy(bridge, BridgeStatus.REPLYING_TO_CODEX, bridge.taskId(), bridge.replacedTaskId(), bridge.slot(), bridge.replyText(), null, null, null, now());
            bridgeRepository.update(replying);
            Path prompt = Files.createTempFile("phone-agent-reply-", ".txt");
            Files.writeString(prompt, promptFormatter.formatPhoneReply(bridge.replyText(), properties.getCodex().getPromptLanguage()));
            submitReplyPrompt(session.get(), prompt);
            Files.deleteIfExists(prompt);
            bridgeRepository.update(copy(replying, BridgeStatus.REPLIED_TO_CODEX, bridge.taskId(), bridge.replacedTaskId(), bridge.slot(), bridge.replyText(), null, null, null, now()));
            taskService.markReplied(bridge.taskId());
            markSessionRunningIfCurrent(replying);
        } catch (Exception e) {
            CodexPhoneBridgeRecord failed = copy(bridge, BridgeStatus.FAILED_REPLY_TO_CODEX, bridge.taskId(), bridge.replacedTaskId(), bridge.slot(), bridge.replyText(),
                    "FAILED_REPLY_TO_CODEX", e.getMessage(), null, now());
            bridgeRepository.update(failed);
            taskService.markReplyFailure(bridge.taskId(), TaskStatus.FAILED_REPLY_TO_CODEX, e.getMessage());
            markSessionCompletedIfCurrent(failed);
        }
    }

    private void submitReplyPrompt(CodexSessionRecord session, Path prompt) {
        String threadId = session.getThreadId();
        if (threadId != null && !threadId.isBlank()) {
            processGateway.resumeTmuxWithPrompt(properties.getCodex().getTmuxCommand(), session.getTmuxName(), Path.of(session.getCwd()),
                    properties.getCodex().getCodexCommand(), threadId, prompt);
            return;
        }
        processGateway.submitPrompt(properties.getCodex().getTmuxCommand(), session.getTmuxName(), prompt);
    }

    private void markSessionRunningIfCurrent(CodexPhoneBridgeRecord bridge) {
        sessionStore.update(bridge.codexSessionId(), record -> {
            if ((record.getStatus() == CodexSessionStatus.WAITING_USER || record.getStatus() == CodexSessionStatus.COMPLETED)
                    && bridgeMatchesCurrentSession(record, bridge)) {
                record.setStatus(CodexSessionStatus.RUNNING);
                record.setWaitingMarker(false);
                record.setUpdatedAt(now());
            }
            return record;
        });
    }

    private void markSessionCompletedIfCurrent(CodexPhoneBridgeRecord bridge) {
        sessionStore.update(bridge.codexSessionId(), record -> {
            if ((record.getStatus() == CodexSessionStatus.WAITING_USER || record.getStatus() == CodexSessionStatus.COMPLETED)
                    && bridgeMatchesCurrentSession(record, bridge)) {
                record.setStatus(CodexSessionStatus.COMPLETED);
                record.setWaitingMarker(true);
                record.setUpdatedAt(now());
            }
            return record;
        });
    }

    private void markSessionWaitingIfCurrent(CodexPhoneBridgeRecord bridge) {
        sessionStore.update(bridge.codexSessionId(), record -> {
            if (record.getStatus() == CodexSessionStatus.COMPLETED
                    && bridgeMatchesCurrentSession(record, bridge)) {
                record.setStatus(CodexSessionStatus.WAITING_USER);
                record.setWaitingMarker(true);
                record.setUpdatedAt(now());
            }
            return record;
        });
    }

    /**
     * Marks Codex as waiting only after the database row confirms a live phone
     * attempt. Renotify and recovery side effects may finish after cancel or a
     * terminal callback wins the bridge row, so TASK_CREATED and terminal
     * snapshots must not reopen the session.
     */
    private void markSessionWaitingIfBridgeLive(CodexPhoneBridgeRecord bridge) {
        if (bridge == null || bridge.terminal()) {
            return;
        }
        if (switch (bridge.status()) {
            case QUEUED, NOTIFIED, PICKED_UP, RECORDING, TRANSCRIBING, ASR_DONE, REPLYING_TO_CODEX -> true;
            default -> false;
        }) {
            markSessionWaitingIfCurrent(bridge);
        }
    }

    /**
     * Applies task lifecycle updates to the owning bridge and returns the saved
     * bridge snapshot so terminal task failures can release the session-level
     * WAITING_USER state without losing the bridge failure audit trail.
     */
    private Optional<CodexPhoneBridgeRecord> updateByTask(String taskId, BridgeStatus status, Integer slot, String errorCode, String errorMessage) {
        return transactionTemplate.execute(tx -> {
            Optional<CodexPhoneBridgeRecord> found = bridgeRepository.findByTaskIdForUpdate(taskId);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            CodexPhoneBridgeRecord bridge = found.get();
            if (bridge.terminal()) {
                return Optional.of(bridge);
            }
            CodexPhoneBridgeRecord updated = copy(
                    bridge,
                    status,
                    bridge.taskId(),
                    bridge.replacedTaskId(),
                    slot == null ? bridge.slot() : slot,
                    bridge.replyText(),
                    errorCode,
                    errorMessage,
                    bridge.cancelledAt(),
                    now());
            bridgeRepository.update(updated);
            return Optional.of(updated);
        });
    }

    private CodexPhoneBridgeRecord updateBridgeAfterTask(
            CodexPhoneBridgeRecord bridge,
            TaskView task,
            String replacedTaskId,
            OffsetDateTime cancelledAt,
            String errorCode,
            String errorMessage
    ) {
        return transactionTemplate.execute(status -> {
            CodexPhoneBridgeRecord locked = bridgeRepository.findByIdForUpdate(bridge.bridgeId()).orElse(bridge);
            if (locked.terminal()) {
                return locked;
            }
            CodexPhoneBridgeRecord updated = copy(locked, bridgeStatusForTask(task.status()), task.taskId(), replacedTaskId, task.slot(),
                    task.replyText(), errorCode, errorMessage, cancelledAt, now());
            bridgeRepository.update(updated);
            return updated;
        });
    }

    private boolean currentWaitingSessionMatches(CodexPhoneBridgeRecord bridge) {
        return currentCompletedOrWaitingSessionMatches(bridge);
    }

    private boolean currentCompletedOrWaitingSessionMatches(CodexPhoneBridgeRecord bridge) {
        Optional<CodexSessionRecord> session = sessionStore.get(bridge.codexSessionId());
        if (session.isEmpty()) {
            return false;
        }
        CodexSessionStatus status = session.get().getStatus();
        if (status != CodexSessionStatus.COMPLETED && status != CodexSessionStatus.WAITING_USER) {
            return false;
        }
        return bridgeMatchesCurrentSession(session.get(), bridge);
    }

    public void cancelCurrentWaitingBridge(CodexSessionRecord session) {
        if (session == null || blank(session.getThreadId())) {
            return;
        }
        String key = waitingEventKey(session);
        if (key == null) {
            return;
        }
        bridgeRepository.findByWaitingEvent(session.getId(), session.getThreadId(), key)
                .filter(bridge -> !bridge.terminal())
                .ifPresent(bridge -> {
                    CodexPhoneBridgeRecord cancelled = copy(bridge, BridgeStatus.CANCELLED, bridge.taskId(), bridge.replacedTaskId(), null,
                            bridge.replyText(), "WAITING_EVENT_STALE", "Codex session was answered outside Phone Agent", now(), now());
                    bridgeRepository.update(cancelled);
                    if (bridge.taskId() != null) {
                        taskService.cancelTaskLogically(bridge.taskId()).ifPresent(taskService::completeCancelSideEffects);
                    }
                });
    }

    private boolean bridgeMatchesCurrentSession(CodexSessionRecord session, CodexPhoneBridgeRecord bridge) {
        return bridge.threadId().equals(session.getThreadId())
                && bridge.waitingEventKey().equals(waitingEventKey(session));
    }

    private void setPhoneError(String sessionId, String code, String message) {
        sessionStore.update(sessionId, record -> {
            if (Objects.equals(record.getPhoneBridgeErrorCode(), code)
                    && Objects.equals(record.getPhoneBridgeErrorMessage(), message)) {
                return record;
            }
            record.setPhoneBridgeErrorCode(code);
            record.setPhoneBridgeErrorMessage(message);
            record.setUpdatedAt(now());
            return record;
        });
    }

    private void clearPhoneError(String sessionId) {
        sessionStore.update(sessionId, record -> {
            if (record.getPhoneBridgeErrorCode() == null && record.getPhoneBridgeErrorMessage() == null) {
                return record;
            }
            record.setPhoneBridgeErrorCode(null);
            record.setPhoneBridgeErrorMessage(null);
            record.setUpdatedAt(now());
            return record;
        });
    }

    private void clearPhoneErrorIfNeeded(CodexSessionRecord session) {
        if (session != null && (session.getPhoneBridgeErrorCode() != null || session.getPhoneBridgeErrorMessage() != null)) {
            clearPhoneError(session.getId());
        }
    }

    private String waitingEventKey(CodexSessionRecord session) {
        if (!blank(session.getLastRelevantEventTimestamp())) {
            return session.getLastRelevantEventTimestamp();
        }
        if (!blank(session.getJsonlPath()) && session.getLastProcessedJsonlSize() > 0) {
            return session.getJsonlPath() + ":" + session.getLastProcessedJsonlSize();
        }
        return null;
    }

    private BridgeStatus bridgeStatusForTask(TaskStatus status) {
        return switch (status) {
            case QUEUED -> BridgeStatus.QUEUED;
            case ASSIGNED, NOTIFIED -> BridgeStatus.NOTIFIED;
            case PICKED_UP -> BridgeStatus.PICKED_UP;
            case RECORDING -> BridgeStatus.RECORDING;
            case RECORDED, TRANSCRIBING -> BridgeStatus.TRANSCRIBING;
            case ASR_DONE -> BridgeStatus.ASR_DONE;
            case NO_REPLY -> BridgeStatus.NO_REPLY;
            case REPLYING_TO_CODEX -> BridgeStatus.REPLYING_TO_CODEX;
            case REPLIED_TO_CODEX -> BridgeStatus.REPLIED_TO_CODEX;
            case CANCELLED -> BridgeStatus.CANCELLED;
            case FAILED_TASK_CREATE -> BridgeStatus.FAILED_TASK_CREATE;
            case FAILED_BLF_NOTIFY -> BridgeStatus.FAILED_BLF_NOTIFY;
            case FAILED_RECORDING -> BridgeStatus.FAILED_RECORDING;
            case FAILED_ASR -> BridgeStatus.FAILED_ASR;
            case FAILED_CODEX_SESSION_STOPPED -> BridgeStatus.FAILED_CODEX_SESSION_STOPPED;
            case FAILED_REPLY_TO_CODEX -> BridgeStatus.FAILED_REPLY_TO_CODEX;
            default -> BridgeStatus.TASK_CREATED;
        };
    }

    private boolean live(TaskStatus status) {
        return switch (status) {
            case CREATED, QUEUED, ASSIGNED, NOTIFIED, PICKED_UP, RECORDING, RECORDED, TRANSCRIBING, ASR_DONE, REPLYING_TO_CODEX -> true;
            default -> false;
        };
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static CodexPhoneBridgeRecord copy(
            CodexPhoneBridgeRecord bridge,
            BridgeStatus status,
            String taskId,
            String replacedTaskId,
            Integer slot,
            String replyText,
            String errorCode,
            String errorMessage,
            OffsetDateTime cancelledAt,
            OffsetDateTime updatedAt
    ) {
        return new CodexPhoneBridgeRecord(
                bridge.bridgeId(),
                bridge.codexSessionId(),
                bridge.threadId(),
                bridge.waitingEventKey(),
                bridge.lastAssistantMessage(),
                status,
                taskId,
                replacedTaskId,
                slot,
                replyText,
                errorCode,
                errorMessage,
                cancelledAt,
                bridge.createdAt(),
                updatedAt
        );
    }

    private record BridgeCancelResult(CodexPhoneBridgeRecord bridge, String taskId, TaskService.CancelResult cancelResult) {
    }
}
