package io.github.liliangxu.phoneagent.task;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import io.github.liliangxu.phoneagent.codex.CodexPhoneBridgeService;
import io.github.liliangxu.phoneagent.config.BlfSlot;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Service
@DependsOn("phoneAgentDatabaseInitializer")
public class TaskService {
    private static final int MAX_TEXT_CODE_POINTS = 2000;
    private static final int MAX_TASK_ID_ATTEMPTS = 1000;

    private final TaskIdGenerator taskIdGenerator;
    private final SpeechSynthesisService speechSynthesisService;
    private final SlotAudioStore slotAudioStore;
    private final RecordingStore recordingStore;
    private final AsteriskAmiClient asteriskAmiClient;
    private final AsrJobQueue asrJobQueue;
    private final JdbcTaskStateRepository stateRepository;
    private final CodexPhoneBridgeService bridgeService;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final ReentrantLock blfSyncLock = new ReentrantLock();
    private final Map<String, TaskRecord> tasks = new LinkedHashMap<>();
    private final Map<Integer, SlotRecord> slots = new LinkedHashMap<>();
    private final ArrayDeque<String> waitingQueue = new ArrayDeque<>();

    @Autowired
    public TaskService(
            TaskIdGenerator taskIdGenerator,
            SpeechSynthesisService speechSynthesisService,
            SlotAudioStore slotAudioStore,
            RecordingStore recordingStore,
            AsteriskAmiClient asteriskAmiClient,
            AsrJobQueue asrJobQueue,
            JdbcTaskStateRepository stateRepository,
            @Lazy CodexPhoneBridgeService bridgeService,
            PhoneAgentProperties properties,
            Clock clock
    ) {
        this.taskIdGenerator = taskIdGenerator;
        this.speechSynthesisService = speechSynthesisService;
        this.slotAudioStore = slotAudioStore;
        this.recordingStore = recordingStore;
        this.asteriskAmiClient = asteriskAmiClient;
        this.asrJobQueue = asrJobQueue;
        this.stateRepository = stateRepository;
        this.bridgeService = bridgeService;
        this.clock = clock;
        initializeSlots(properties.blfSlots());
        if (stateRepository != null) {
            for (TaskRecord task : stateRepository.loadTasks()) {
                tasks.put(task.taskId(), task);
                if (task.status() == TaskStatus.QUEUED) {
                    waitingQueue.addLast(task.taskId());
                }
            }
            Map<Integer, SlotRecord> loadedSlots = new LinkedHashMap<>();
            for (SlotRecord slot : stateRepository.loadSlots()) {
                loadedSlots.put(slot.slot(), slot);
            }
            for (BlfSlot configured : properties.blfSlots()) {
                SlotRecord loaded = loadedSlots.get(configured.slot());
                if (loaded != null) {
                    slots.put(configured.slot(), loaded.withExtension(configured.extension()));
                }
            }
        }
    }

    /**
     * Builds the active runtime slot map from startup BLF configuration only.
     * Database rows outside this map are intentionally ignored: this preserves
     * the no-migration/no-remap boundary when a local dev instance is restarted
     * with fewer configured BLF extensions than historical phone_slots rows.
     */
    private void initializeSlots(List<BlfSlot> configuredSlots) {
        for (BlfSlot configured : configuredSlots) {
            slots.put(configured.slot(), new SlotRecord(configured.slot(), configured.extension()));
        }
    }

    TaskService(
            TaskIdGenerator taskIdGenerator,
            SpeechSynthesisService speechSynthesisService,
            SlotAudioStore slotAudioStore,
            RecordingStore recordingStore,
            AsteriskAmiClient asteriskAmiClient,
            AsrJobQueue asrJobQueue,
            Clock clock
    ) {
        this(taskIdGenerator, speechSynthesisService, slotAudioStore, recordingStore, asteriskAmiClient, asrJobQueue, null, null, new PhoneAgentProperties(), clock);
    }

    /**
     * Creates a task and either assigns it to the first free BLF slot or queues it.
     * The assigned slot stays hidden from Asterisk until slot audio is published
     * and the AMI INUSE command succeeds, so a BLF press cannot resolve stale or
     * half-armed audio.
     */
    public TaskView createTask(String text) {
        return createTask(text, null);
    }

    /**
     * Creates a phone task for a Codex bridge or the public task API. Bridge
     * tasks deliberately bypass the legacy 2000-code-point limit so the full
     * assistant message can be spoken and audited.
     */
    public TaskView createBridgeTask(String bridgeId, String text) {
        if (bridgeId == null || bridgeId.isBlank()) {
            throw new TaskValidationException("bridgeId must not be blank");
        }
        return createTask(text, bridgeId);
    }

    private TaskView createTask(String text, String bridgeId) {
        String normalized = validateText(text, bridgeId != null);
        OffsetDateTime now = now();
        TaskRecord task;
        lock.lock();
        try {
            task = new TaskRecord(nextUnusedTaskId(), normalized, now);
            task.bridgeId(bridgeId, now);
            tasks.put(task.taskId(), task);
            persistTask(task);
        } finally {
            lock.unlock();
        }
        try {
            notifyTaskCreated(bridgeId, task.taskId());
        } catch (RuntimeException e) {
            return failDuringCreate(task, FailureStage.INTERNAL, "Failed to bind bridge task ownership: " + e.getMessage(), e);
        }
        Optional<TaskView> cancelled = cancelledSnapshot(task.taskId());
        if (cancelled.isPresent()) {
            return cancelled.get();
        }

        Path taskAudio;
        try {
            taskAudio = speechSynthesisService.synthesize(task.taskId(), normalized);
            lock.lock();
            try {
                if (task.status() == TaskStatus.CANCELLED) {
                    return TaskView.from(task);
                }
                task.taskAudioFile(taskAudio, now());
                persistTask(task);
            } finally {
                lock.unlock();
            }
        } catch (AudioPreparationException e) {
            return failDuringCreate(task, e.stage(), e.getMessage(), e);
        } catch (RuntimeException e) {
            return failDuringCreate(task, FailureStage.TTS, "TTS failed: " + e.getMessage(), e);
        }

        Integer reservedSlot = null;
        lock.lock();
        try {
            SlotRecord free = firstIdleSlot();
            if (free == null) {
                waitingQueue.addLast(task.taskId());
                task.status(TaskStatus.QUEUED, now());
                persistTask(task);
                return TaskView.from(task);
            }
            free.reserve();
            reservedSlot = free.slot();
            task.slot(reservedSlot, now());
            task.status(TaskStatus.ASSIGNED, now());
            persistSlot(free);
            persistTask(task);
        } finally {
            lock.unlock();
        }

        try {
            slotAudioStore.publish(taskAudio, reservedSlot);
        } catch (RuntimeException e) {
            final Integer failedSlot = reservedSlot;
            String failureMessage = "Failed to publish slot audio: " + e.getMessage();
            if (failAssignedTaskIfStillOwner(task.taskId(), failedSlot, TaskStatus.FAILED_TASK_CREATE, FailureStage.INTERNAL, failureMessage)) {
                throw new TaskCreationException("Failed to publish slot audio", snapshot(task.taskId()).orElseThrow(), e);
            }
            return snapshot(task.taskId()).orElseThrow();
        }
        cancelled = cancelledSnapshot(task.taskId());
        if (cancelled.isPresent()) {
            return cancelled.get();
        }

        lock.lock();
        try {
            SlotRecord slot = slots.get(reservedSlot);
            if (task.status() == TaskStatus.CANCELLED) {
                return TaskView.from(task);
            }
            slot.reserveForTask(task.taskId());
            task.slot(reservedSlot, now());
            task.status(TaskStatus.ASSIGNED, now());
            persistSlot(slot);
            persistTask(task);
        } finally {
            lock.unlock();
        }

        try {
            asteriskAmiClient.setInUse(reservedSlot);
        } catch (RuntimeException e) {
            final Integer failedSlot = reservedSlot;
            if (failAssignedTaskIfStillOwner(task.taskId(), failedSlot, TaskStatus.FAILED_BLF_NOTIFY, FailureStage.BLF_NOTIFY, e.getMessage())) {
                resetSlotIfStillSafe(failedSlot, task.taskId());
                throw new TaskCreationException("Failed to notify BLF", snapshot(task.taskId()).orElseThrow(), e);
            }
            resetSlotIfStillSafe(failedSlot, task.taskId());
            return snapshot(task.taskId()).orElseThrow();
        }
        cancelled = cancelledSnapshot(task.taskId());
        if (cancelled.isPresent()) {
            resetSlotIfStillSafe(reservedSlot, task.taskId());
            return cancelled.get();
        }

        boolean notified = false;
        lock.lock();
        try {
            SlotRecord slot = slots.get(reservedSlot);
            if (task.taskId().equals(slot.taskId())) {
                slot.notified(task.taskId());
                task.status(TaskStatus.NOTIFIED, now());
                persistSlot(slot);
                persistTask(task);
                notified = true;
            }
        } finally {
            lock.unlock();
        }
        if (notified) {
            notifyNotified(task.taskId(), reservedSlot);
        }
        return snapshot(task.taskId()).orElseThrow();
    }

    public Optional<TaskView> getTask(String taskId) {
        return snapshot(taskId);
    }

    public boolean hasTaskAudio(String taskId) {
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            return task != null && task.taskAudioFile() != null && java.nio.file.Files.exists(task.taskAudioFile());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Generates task ids under the service lock so a restarted id generator
     * cannot overwrite historical phone_tasks rows that were reloaded at boot.
     */
    private String nextUnusedTaskId() {
        for (int attempt = 0; attempt < MAX_TASK_ID_ATTEMPTS; attempt++) {
            String candidate = taskIdGenerator.nextTaskId();
            if (!tasks.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate unique task id after " + MAX_TASK_ID_ATTEMPTS + " attempts");
    }

    public List<TaskView> listTasks() {
        lock.lock();
        try {
            return tasks.values().stream().map(TaskView::from).toList();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called by Asterisk when a user presses a BLF key.
     * Repeated calls for the same in-flight slot return the same task id until
     * recording completion is processed.
     */
    public String startSlot(int slotNumber) {
        SlotRecord slot = requireSlot(slotNumber);
        String pickedUpTaskId = null;
        lock.lock();
        try {
            if (slot.status() == SlotStatus.NOTIFIED && slot.taskId() != null) {
                TaskRecord task = tasks.get(slot.taskId());
                task.status(TaskStatus.PICKED_UP, now());
                slot.pickedUp(task.taskId());
                persistTask(task);
                persistSlot(slot);
                pickedUpTaskId = task.taskId();
            }
            if (pickedUpTaskId == null
                    && (slot.status() == SlotStatus.PICKED_UP || slot.status() == SlotStatus.RECORDING)
                    && slot.startedTaskId() != null) {
                return slot.startedTaskId();
            }
        } finally {
            lock.unlock();
        }
        if (pickedUpTaskId != null) {
            notifyPickedUp(pickedUpTaskId);
            return pickedUpTaskId;
        }
        return "";
    }

    /**
     * Refreshes every Eventlist BLF resource without touching task ownership or
     * queue order. Partial-state BLF subscribers need an actual state edge per
     * slot, so idle slots are pulsed INUSE -> NOT_INUSE and active slots are
     * pulsed NOT_INUSE -> INUSE in slot order.
     */
    public BlfSyncResponse syncAllSlots(String reason) {
        if (!blfSyncLock.tryLock()) {
            throw new BlfSyncInProgressException(reason);
        }
        try {
            List<SlotRefresh> refreshes = new ArrayList<>();
            lock.lock();
            try {
                for (SlotRecord slot : slots.values()) {
                    refreshes.add(new SlotRefresh(slot.slot(), slot.idle(), slot.taskId()));
                }
            } finally {
                lock.unlock();
            }

            List<BlfSyncSlotResult> slotResults = new ArrayList<>();
            List<Integer> failedSlots = new ArrayList<>();
            for (SlotRefresh refresh : refreshes) {
                String target = refresh.idle() ? "NOT_INUSE" : "INUSE";
                slotResults.add(new BlfSyncSlotResult(refresh.slot(), target, refresh.taskId()));
                try {
                    if (refresh.idle()) {
                        asteriskAmiClient.setInUse(refresh.slot());
                        pauseBetweenRefreshEdges();
                        asteriskAmiClient.setNotInUse(refresh.slot());
                    } else {
                        asteriskAmiClient.setNotInUse(refresh.slot());
                        pauseBetweenRefreshEdges();
                        asteriskAmiClient.setInUse(refresh.slot());
                    }
                } catch (RuntimeException e) {
                    failedSlots.add(refresh.slot());
                }
            }
            boolean success = failedSlots.isEmpty();
            return new BlfSyncResponse(success, reason, slotResults, failedSlots, success ? null : "BLF_SYNC_PARTIAL_FAILURE");
        } finally {
            blfSyncLock.unlock();
        }
    }

    public void startRecording(int slotNumber, String taskId) {
        SlotRecord slot = requireSlot(slotNumber);
        lock.lock();
        try {
            if (!matchesCurrentOrStarted(slot, taskId)) {
                throw new TaskConflictException(slotNumber, taskId);
            }
            TaskRecord task = tasks.get(taskId);
            task.status(TaskStatus.RECORDING, now());
            slot.recording(taskId);
            persistTask(task);
            persistSlot(slot);
        } finally {
            lock.unlock();
        }
        notifyRecording(taskId);
    }

    /**
     * Processes Asterisk's hangup callback. The method deliberately checks
     * callback idempotency twice: once before the file-system probe and again
     * after reacquiring the lock. Only the request that flips the handled flag
     * may release the slot, refill the queue, or submit ASR.
     */
    public RecordingCallbackResult completeRecording(int slotNumber, String taskId) {
        SlotRecord slot = requireSlot(slotNumber);
        TaskRecord task;
        lock.lock();
        try {
            task = tasks.get(taskId);
            if (task == null) {
                throw new TaskConflictException(slotNumber, taskId);
            }
            if (task.recordingCallbackHandled() && task.slot() != null && task.slot() == slotNumber) {
                return RecordingCallbackResult.DUPLICATE;
            }
            if (!matchesCurrentOrStarted(slot, taskId)) {
                throw new TaskConflictException(slotNumber, taskId);
            }
        } finally {
            lock.unlock();
        }

        boolean hasRecording = recordingStore.hasNonEmptyRecording(taskId);
        Path recordingPath = recordingStore.displayRecordingPath(taskId);
        RefillPlan refillPlan = null;
        boolean shouldSubmitAsr = false;
        boolean shouldNotifyNoReply = false;
        TaskStatus failureStatus = null;
        String failureMessage = null;

        lock.lock();
        try {
            task = tasks.get(taskId);
            if (task == null) {
                throw new TaskConflictException(slotNumber, taskId);
            }
            if (task.recordingCallbackHandled()) {
                return RecordingCallbackResult.DUPLICATE;
            }
            if (!matchesCurrentOrStarted(slot, taskId)) {
                throw new TaskConflictException(slotNumber, taskId);
            }
            task.markRecordingCallbackHandled(now());
            task.recordingFile(recordingPath, now());
            if (hasRecording) {
                task.status(TaskStatus.RECORDED, now());
                shouldSubmitAsr = true;
            } else if (task.status() == TaskStatus.PICKED_UP) {
                // A hangup before /recordings/start means the user never reached
                // the recording phase; classify it as no usable phone reply
                // rather than a recording subsystem failure.
                task.status(TaskStatus.NO_REPLY, now());
                shouldNotifyNoReply = true;
            } else {
                task.fail(TaskStatus.FAILED_RECORDING, FailureStage.RECORDING, "recording file is missing or empty", now());
                failureStatus = TaskStatus.FAILED_RECORDING;
                failureMessage = task.errorMessage();
            }
            slot.release();
            persistTask(task);
            persistSlot(slot);
            refillPlan = reserveNextQueuedTask(slotNumber);
        } finally {
            lock.unlock();
        }
        if (shouldSubmitAsr) {
            notifyRecorded(taskId);
        } else if (shouldNotifyNoReply && bridgeService != null) {
            bridgeService.onTaskNoReply(taskId);
        } else if (failureStatus != null) {
            notifyFailure(taskId, failureStatus, failureMessage);
        }

        try {
            asteriskAmiClient.setNotInUse(slotNumber);
        } catch (RuntimeException ignored) {
            // Reset is best-effort; logical slot state has already been released.
        }

        if (refillPlan != null) {
            publishRefill(refillPlan);
        }
        if (shouldSubmitAsr) {
            markTranscribing(taskId);
            asrJobQueue.submit(taskId);
        }
        return RecordingCallbackResult.PROCESSED;
    }

    void completeAsrSuccess(String taskId, String replyText) {
        String cleanedReplyText = replyText == null ? "" : replyText.strip();
        boolean completed = false;
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            if (task == null) {
                return;
            }
            if (task.status() != TaskStatus.TRANSCRIBING) {
                return;
            }
            if (cleanedReplyText.isEmpty()) {
                task.replyText(null, now());
                task.status(TaskStatus.NO_REPLY, now());
            } else {
                task.replyText(cleanedReplyText, now());
                task.status(TaskStatus.ASR_DONE, now());
            }
            persistTask(task);
            completed = true;
        } finally {
            lock.unlock();
        }
        if (completed && bridgeService != null) {
            if (cleanedReplyText.isEmpty()) {
                bridgeService.onTaskNoReply(taskId);
            } else {
                bridgeService.onTaskAsrSuccess(taskId, cleanedReplyText);
            }
        }
    }

    void markTranscribing(String taskId) {
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            if (task != null && task.status() == TaskStatus.RECORDED) {
                task.status(TaskStatus.TRANSCRIBING, now());
                persistTask(task);
            }
        } finally {
            lock.unlock();
        }
    }

    void completeAsrFailure(String taskId, String message) {
        lock.lock();
        boolean failed = false;
        try {
            TaskRecord task = tasks.get(taskId);
            if (task == null) {
                return;
            }
            if (task.status() != TaskStatus.TRANSCRIBING) {
                return;
            }
            task.fail(TaskStatus.FAILED_ASR, FailureStage.ASR, message, now());
            persistTask(task);
            failed = true;
        } finally {
            lock.unlock();
        }
        if (failed) {
            notifyFailure(taskId, TaskStatus.FAILED_ASR, message);
        }
    }

    public Optional<TaskView> cancelTask(String taskId) {
        Optional<CancelResult> result = cancelTaskLogically(taskId);
        result.ifPresent(this::completeCancelSideEffects);
        return result.map(CancelResult::task);
    }

    /**
     * Persists logical cancellation without AMI or slot-audio side effects. Bridge
     * cancellation uses this inside its own short transaction so bridge/task/slot
     * recovery state cannot split across a crash.
     */
    public Optional<CancelResult> cancelTaskLogically(String taskId) {
        Integer releasedSlot = null;
        RefillPlan refillPlan = null;
        TaskView cancelled;
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            if (task == null) {
                return Optional.empty();
            }
            Integer slotNumber = task.slot();
            task.status(TaskStatus.CANCELLED, now());
            persistTask(task);
            waitingQueue.remove(taskId);
            if (slotNumber != null) {
                SlotRecord slot = slots.get(slotNumber);
                if (slot != null && (taskId.equals(slot.taskId()) || (slot.status() == SlotStatus.RESERVED && slot.taskId() == null))) {
                    slot.release();
                    persistSlot(slot);
                    releasedSlot = slotNumber;
                    refillPlan = reserveNextQueuedTask(slotNumber);
                }
            }
            cancelled = TaskView.from(task);
        } finally {
            lock.unlock();
        }
        return Optional.of(new CancelResult(
                cancelled,
                releasedSlot,
                refillPlan == null ? null : refillPlan.taskId(),
                refillPlan == null ? null : refillPlan.taskAudio()));
    }

    public void completeCancelSideEffects(CancelResult result) {
        if (result.releasedSlot() != null) {
            try {
                asteriskAmiClient.setNotInUse(result.releasedSlot());
            } catch (RuntimeException ignored) {
                // Logical cancel state is persisted; BLF sync can repair physical state later.
            }
        }
        if (result.refillTaskId() != null) {
            publishRefill(new RefillPlan(result.releasedSlot(), result.refillTaskId(), result.refillTaskAudio()));
        }
    }

    public TaskView retryTask(String taskId) {
        Integer assignedSlot = null;
        Path taskAudio = null;
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            if (task == null) {
                throw new TaskNotFoundException(taskId);
            }
            task.clearFailure(now());
            SlotRecord free = firstIdleSlot();
            if (free == null) {
                waitingQueue.addLast(task.taskId());
                task.status(TaskStatus.QUEUED, now());
                task.slot(null, now());
                persistTask(task);
                return TaskView.from(task);
            }
            free.reserve();
            task.slot(free.slot(), now());
            task.status(TaskStatus.ASSIGNED, now());
            persistSlot(free);
            persistTask(task);
            assignedSlot = free.slot();
            taskAudio = task.taskAudioFile();
        } finally {
            lock.unlock();
        }
        try {
            slotAudioStore.publish(taskAudio, assignedSlot);
        } catch (RuntimeException e) {
            String failureMessage = "Failed to publish retry slot audio: " + e.getMessage();
            if (failAssignedTaskIfStillOwner(taskId, assignedSlot, TaskStatus.FAILED_TASK_CREATE, FailureStage.INTERNAL, failureMessage)) {
                notifyFailure(taskId, TaskStatus.FAILED_TASK_CREATE, failureMessage);
                throw new TaskCreationException("Failed to publish retry slot audio", snapshot(taskId).orElseThrow(), e);
            }
            return snapshot(taskId).orElseThrow();
        }
        lock.lock();
        try {
            TaskRecord current = tasks.get(taskId);
            SlotRecord slot = slots.get(assignedSlot);
            if (current == null || current.status() == TaskStatus.CANCELLED) {
                return current == null ? snapshot(taskId).orElseThrow() : TaskView.from(current);
            }
            slot.reserveForTask(taskId);
            persistSlot(slot);
            persistTask(current);
        } finally {
            lock.unlock();
        }
        TaskView task = snapshot(taskId).orElseThrow();
        try {
            asteriskAmiClient.setInUse(task.slot());
        } catch (RuntimeException e) {
            String failureMessage = e.getMessage();
            if (failAssignedTaskIfStillOwner(taskId, assignedSlot, TaskStatus.FAILED_BLF_NOTIFY, FailureStage.BLF_NOTIFY, failureMessage)) {
                notifyFailure(taskId, TaskStatus.FAILED_BLF_NOTIFY, failureMessage);
                throw new TaskCreationException("Failed to notify BLF", snapshot(taskId).orElseThrow(), e);
            }
            resetSlotIfStillSafe(assignedSlot, taskId);
            return snapshot(taskId).orElseThrow();
        }
        boolean notified = false;
        Integer notifiedSlot = null;
        boolean cancelledDuringAmi = false;
        lock.lock();
        try {
            TaskRecord current = tasks.get(taskId);
            SlotRecord slot = current == null || current.slot() == null ? null : slots.get(current.slot());
            if (current == null || current.status() != TaskStatus.ASSIGNED || slot == null || !taskId.equals(slot.taskId())) {
                cancelledDuringAmi = true;
                return current == null ? snapshot(taskId).orElseThrow() : TaskView.from(current);
            }
            slot.notified(taskId);
            current.status(TaskStatus.NOTIFIED, now());
            persistSlot(slot);
            persistTask(current);
            notified = true;
            notifiedSlot = current.slot();
            return TaskView.from(current);
        } finally {
            lock.unlock();
            if (cancelledDuringAmi && assignedSlot != null) {
                resetSlotIfStillSafe(assignedSlot, taskId);
            }
            if (notified) {
                notifyNotified(taskId, notifiedSlot);
            }
        }
    }

    public void markReplying(String taskId) {
        updateReplyStatus(taskId, TaskStatus.REPLYING_TO_CODEX, null);
    }

    public void markReplied(String taskId) {
        updateReplyStatus(taskId, TaskStatus.REPLIED_TO_CODEX, null);
    }

    public void markReplyFailure(String taskId, TaskStatus status, String message) {
        updateReplyStatus(taskId, status, message);
    }

    private String validateText(String text, boolean bridgeTask) {
        if (text == null || text.trim().isEmpty()) {
            throw new TaskValidationException("text must not be blank");
        }
        String normalized = text.trim();
        if (!bridgeTask && normalized.codePointCount(0, normalized.length()) > MAX_TEXT_CODE_POINTS) {
            throw new TaskValidationException("text must not exceed 2000 code points");
        }
        return normalized;
    }

    private TaskView failDuringCreate(TaskRecord task, FailureStage stage, String message, RuntimeException cause) {
        TaskStatus status = stage == FailureStage.BLF_NOTIFY ? TaskStatus.FAILED_BLF_NOTIFY : TaskStatus.FAILED_TASK_CREATE;
        lock.lock();
        try {
            task.fail(status, stage, message, now());
            persistTask(task);
        } finally {
            lock.unlock();
        }
        notifyFailure(task.taskId(), status, message);
        throw new TaskCreationException(message, TaskView.from(task), cause);
    }

    private boolean failAssignedTaskIfStillOwner(
            String taskId,
            Integer slotNumber,
            TaskStatus failureStatus,
            FailureStage failureStage,
            String message
    ) {
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            SlotRecord slot = slotNumber == null ? null : slots.get(slotNumber);
            if (task == null || task.status() != TaskStatus.ASSIGNED || slot == null || !slotStillOwnedByTask(task, slot, slotNumber)) {
                return false;
            }
            slot.release();
            task.slot(null, now());
            task.fail(failureStatus, failureStage, message, now());
            persistSlot(slot);
            persistTask(task);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean canArmHiddenSlotForTask(TaskRecord task, SlotRecord slot, String taskId, Integer slotNumber) {
        return task != null
                && task.status() == TaskStatus.ASSIGNED
                && slotNumber != null
                && task.slot() != null
                && task.slot().equals(slotNumber)
                && slot != null
                && slot.status() == SlotStatus.RESERVED
                && slot.taskId() == null
                && taskId.equals(task.taskId());
    }

    private boolean slotStillOwnedByTask(TaskRecord task, SlotRecord slot, Integer slotNumber) {
        if (task == null || slot == null || slotNumber == null || task.slot() == null || !task.slot().equals(slotNumber)) {
            return false;
        }
        return task.taskId().equals(slot.taskId())
                || (slot.status() == SlotStatus.RESERVED && slot.taskId() == null && task.status() == TaskStatus.ASSIGNED);
    }

    private void resetSlotIfStillSafe(Integer slotNumber, String taskId) {
        if (slotNumber == null) {
            return;
        }
        boolean safeToReset;
        lock.lock();
        try {
            SlotRecord slot = slots.get(slotNumber);
            TaskRecord task = tasks.get(taskId);
            safeToReset = slot != null && (slot.idle() || slotStillOwnedByTask(task, slot, slotNumber));
        } finally {
            lock.unlock();
        }
        if (safeToReset) {
            try {
                asteriskAmiClient.setNotInUse(slotNumber);
            } catch (RuntimeException ignored) {
                // Logical state already won; later BLF sync can repair any remaining physical state.
            }
        }
    }

    private Optional<TaskView> cancelledSnapshot(String taskId) {
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            if (task != null && task.status() == TaskStatus.CANCELLED) {
                return Optional.of(TaskView.from(task));
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    private Optional<TaskView> snapshot(String taskId) {
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            return task == null ? Optional.empty() : Optional.of(TaskView.from(task));
        } finally {
            lock.unlock();
        }
    }

    private SlotRecord requireSlot(int slotNumber) {
        SlotRecord slot = slots.get(slotNumber);
        if (slot == null) {
            throw new InvalidSlotException(slotNumber);
        }
        return slot;
    }

    private SlotRecord firstIdleSlot() {
        return slots.values().stream().filter(SlotRecord::idle).findFirst().orElse(null);
    }

    private boolean matchesCurrentOrStarted(SlotRecord slot, String taskId) {
        return taskId != null && (taskId.equals(slot.taskId()) || taskId.equals(slot.startedTaskId()));
    }

    private boolean activeSlot(Integer slotNumber) {
        return slotNumber != null && slots.containsKey(slotNumber);
    }

    private RefillPlan reserveNextQueuedTask(int releasedSlot) {
        String nextTaskId = waitingQueue.pollFirst();
        if (nextTaskId == null) {
            return null;
        }
        SlotRecord slot = slots.get(releasedSlot);
        slot.reserve();
        TaskRecord next = tasks.get(nextTaskId);
        if (next != null) {
            next.slot(releasedSlot, now());
            next.status(TaskStatus.ASSIGNED, now());
            persistTask(next);
        }
        persistSlot(slot);
        return new RefillPlan(releasedSlot, nextTaskId, next.taskAudioFile());
    }

    /**
     * Reuses a released slot for the next queued task with the same publish,
     * hidden assignment, and AMI confirmation ordering as initial task creation.
     */
    private void publishRefill(RefillPlan plan) {
        try {
            slotAudioStore.publish(plan.taskAudio(), plan.slot());
        } catch (RuntimeException e) {
            String failureMessage = "Failed to publish refill slot audio: " + e.getMessage();
            if (failAssignedTaskIfStillOwner(plan.taskId(), plan.slot(), TaskStatus.FAILED_TASK_CREATE, FailureStage.INTERNAL, failureMessage)) {
                notifyFailure(plan.taskId(), TaskStatus.FAILED_TASK_CREATE, failureMessage);
            }
            return;
        }

        lock.lock();
        try {
            SlotRecord slot = slots.get(plan.slot());
            TaskRecord task = tasks.get(plan.taskId());
            if (canArmHiddenSlotForTask(task, slot, plan.taskId(), plan.slot())) {
                slot.reserveForTask(plan.taskId());
                task.slot(plan.slot(), now());
                task.status(TaskStatus.ASSIGNED, now());
                persistSlot(slot);
                persistTask(task);
            } else {
                return;
            }
        } finally {
            lock.unlock();
        }
        try {
            asteriskAmiClient.setInUse(plan.slot());
        } catch (RuntimeException e) {
            String failureMessage = e.getMessage();
            if (failAssignedTaskIfStillOwner(plan.taskId(), plan.slot(), TaskStatus.FAILED_BLF_NOTIFY, FailureStage.BLF_NOTIFY, failureMessage)) {
                notifyFailure(plan.taskId(), TaskStatus.FAILED_BLF_NOTIFY, failureMessage);
            }
            resetSlotIfStillSafe(plan.slot(), plan.taskId());
        }

        boolean notified = false;
        boolean resetAfterLateStateChange = false;
        lock.lock();
        try {
            SlotRecord slot = slots.get(plan.slot());
            TaskRecord task = tasks.get(plan.taskId());
            if (task != null && task.status() == TaskStatus.ASSIGNED && slot != null && plan.taskId().equals(slot.taskId())) {
                slot.notified(plan.taskId());
                task.status(TaskStatus.NOTIFIED, now());
                persistSlot(slot);
                persistTask(task);
                notified = true;
            } else {
                resetAfterLateStateChange = true;
            }
        } finally {
            lock.unlock();
        }
        if (resetAfterLateStateChange) {
            resetSlotIfStillSafe(plan.slot(), plan.taskId());
        }
        if (notified) {
            notifyNotified(plan.taskId(), plan.slot());
        }
    }

    /**
     * Reconciles task and BLF state loaded from MySQL after the Spring context
     * is fully initialized. It repairs states that can be left behind by a
     * process crash between DB writes, AMI notification, recording callback, and
     * ASR submission.
     */
    public void recoverLoadedState() {
        List<StartupRecovery> recoveries = new ArrayList<>();
        lock.lock();
        try {
            for (TaskRecord task : tasks.values()) {
                if (task.status() == TaskStatus.CREATED) {
                    recoveries.add(new StartupRecovery(task.taskId(), task.slot(), StartupRecoveryAction.FAIL_TASK_CREATE));
                } else if (task.status() == TaskStatus.ASSIGNED && activeSlot(task.slot())) {
                    recoveries.add(new StartupRecovery(task.taskId(), task.slot(), StartupRecoveryAction.NOTIFY_BLF));
                } else if (task.status() == TaskStatus.RECORDED || task.status() == TaskStatus.TRANSCRIBING) {
                    recoveries.add(new StartupRecovery(task.taskId(), task.slot(), StartupRecoveryAction.SUBMIT_ASR));
                } else if (task.status() == TaskStatus.FAILED_BLF_NOTIFY && task.taskAudioFile() != null && (task.slot() == null || activeSlot(task.slot()))) {
                    recoveries.add(new StartupRecovery(task.taskId(), task.slot(), StartupRecoveryAction.RETRY_BLF));
                }
            }
        } finally {
            lock.unlock();
        }
        for (StartupRecovery recovery : recoveries) {
            switch (recovery.action()) {
                case NOTIFY_BLF -> recoverAssignedTask(recovery.taskId(), recovery.slot());
                case SUBMIT_ASR -> recoverRecordedTask(recovery.taskId(), recovery.slot());
                case RETRY_BLF -> retryFailedBlfTask(recovery.taskId());
                case FAIL_TASK_CREATE -> failRecoveredTaskCreate(recovery.taskId());
            }
        }
    }

    private void failRecoveredTaskCreate(String taskId) {
        String failureMessage = null;
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            if (task != null && task.status() == TaskStatus.CREATED) {
                task.fail(TaskStatus.FAILED_TASK_CREATE, FailureStage.INTERNAL,
                        "Task was left in CREATED during startup recovery", now());
                persistTask(task);
                failureMessage = task.errorMessage();
            }
        } finally {
            lock.unlock();
        }
        if (failureMessage != null) {
            notifyFailure(taskId, TaskStatus.FAILED_TASK_CREATE, failureMessage);
        }
    }

    private void recoverAssignedTask(String taskId, Integer slotNumber) {
        Path taskAudio;
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            if (task == null || task.status() != TaskStatus.ASSIGNED) {
                return;
            }
            taskAudio = task.taskAudioFile();
        } finally {
            lock.unlock();
        }
        try {
            slotAudioStore.publish(taskAudio, slotNumber);
            asteriskAmiClient.setInUse(slotNumber);
            boolean notified = false;
            boolean resetAfterLateStateChange = false;
            lock.lock();
            try {
                TaskRecord task = tasks.get(taskId);
                SlotRecord slot = slots.get(slotNumber);
                if (task != null && slot != null && task.status() == TaskStatus.ASSIGNED && slotStillOwnedByTask(task, slot, slotNumber)) {
                    slot.notified(taskId);
                    task.status(TaskStatus.NOTIFIED, now());
                    persistSlot(slot);
                    persistTask(task);
                    notified = true;
                } else {
                    resetAfterLateStateChange = true;
                }
            } finally {
                lock.unlock();
            }
            if (resetAfterLateStateChange) {
                resetSlotIfStillSafe(slotNumber, taskId);
            }
            if (notified) {
                notifyNotified(taskId, slotNumber);
            }
        } catch (RuntimeException e) {
            failRecoveredNotification(taskId, slotNumber, e.getMessage());
        }
    }

    private void recoverRecordedTask(String taskId, Integer slotNumber) {
        RefillPlan refillPlan = null;
        boolean recorded = false;
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            if (task == null) {
                return;
            }
            if (slotNumber != null) {
                SlotRecord slot = slots.get(slotNumber);
                if (slot != null && taskId.equals(slot.taskId())) {
                    slot.release();
                    persistSlot(slot);
                }
                if (slot != null && slot.idle()) {
                    refillPlan = reserveNextQueuedTask(slotNumber);
                }
            }
            task.status(TaskStatus.TRANSCRIBING, now());
            persistTask(task);
            recorded = true;
        } finally {
            lock.unlock();
        }
        if (recorded) {
            notifyRecorded(taskId);
        }
        if (slotNumber != null) {
            try {
                asteriskAmiClient.setNotInUse(slotNumber);
            } catch (RuntimeException ignored) {
                // BLF reset is best-effort during startup recovery.
            }
        }
        if (refillPlan != null) {
            publishRefill(refillPlan);
        }
        asrJobQueue.submit(taskId);
    }

    private void retryFailedBlfTask(String taskId) {
        try {
            retryTask(taskId);
        } catch (RuntimeException ignored) {
            // The failed state remains persisted; operators can renotify later.
        }
    }

    private void failRecoveredNotification(String taskId, Integer slotNumber, String message) {
        boolean failed = false;
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            if (task == null) {
                return;
            }
            if (slotNumber != null) {
                SlotRecord slot = slots.get(slotNumber);
                if (slot != null && task.status() == TaskStatus.ASSIGNED && (taskId.equals(slot.taskId()) || slot.status() == SlotStatus.RESERVED)) {
                    slot.release();
                    persistSlot(slot);
                    task.slot(null, now());
                    task.fail(TaskStatus.FAILED_BLF_NOTIFY, FailureStage.BLF_NOTIFY, message, now());
                    persistTask(task);
                    failed = true;
                }
            }
        } finally {
            lock.unlock();
        }
        if (failed) {
            notifyFailure(taskId, TaskStatus.FAILED_BLF_NOTIFY, message);
        }
        resetSlotIfStillSafe(slotNumber, taskId);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private void persistTask(TaskRecord task) {
        if (stateRepository != null && task != null) {
            stateRepository.saveTask(task);
        }
    }

    private void persistSlot(SlotRecord slot) {
        if (stateRepository != null && slot != null) {
            stateRepository.saveSlot(slot, now());
        }
    }

    private void updateReplyStatus(String taskId, TaskStatus status, String message) {
        lock.lock();
        try {
            TaskRecord task = tasks.get(taskId);
            if (task == null) {
                return;
            }
            if (status == TaskStatus.FAILED_CODEX_SESSION_STOPPED || status == TaskStatus.FAILED_REPLY_TO_CODEX) {
                task.fail(status, FailureStage.INTERNAL, message, now());
            } else {
                task.status(status, now());
            }
            persistTask(task);
        } finally {
            lock.unlock();
        }
    }

    private void notifyTaskCreated(String bridgeId, String taskId) {
        if (bridgeId != null && bridgeService != null) {
            bridgeService.onBridgeTaskCreated(bridgeId, taskId);
        }
    }

    private void notifyNotified(String taskId, Integer slot) {
        if (bridgeService != null) {
            bridgeService.onTaskNotified(taskId, slot);
        }
    }

    private void notifyPickedUp(String taskId) {
        if (bridgeService != null) {
            bridgeService.onTaskPickedUp(taskId);
        }
    }

    private void notifyRecording(String taskId) {
        if (bridgeService != null) {
            bridgeService.onTaskRecording(taskId);
        }
    }

    private void notifyRecorded(String taskId) {
        if (bridgeService != null) {
            bridgeService.onTaskRecorded(taskId);
        }
    }

    private void notifyFailure(String taskId, TaskStatus status, String message) {
        if (bridgeService != null) {
            bridgeService.onTaskFailure(taskId, status, message);
        }
    }

    private static void pauseBetweenRefreshEdges() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AsteriskControlException("Interrupted while refreshing BLF state", e);
        }
    }

    private record RefillPlan(int slot, String taskId, Path taskAudio) {
    }

    public record CancelResult(TaskView task, Integer releasedSlot, String refillTaskId, Path refillTaskAudio) {
    }

    private record SlotRefresh(int slot, boolean idle, String taskId) {
    }

    private enum StartupRecoveryAction {
        NOTIFY_BLF,
        SUBMIT_ASR,
        RETRY_BLF,
        FAIL_TASK_CREATE
    }

    private record StartupRecovery(String taskId, Integer slot, StartupRecoveryAction action) {
    }
}
