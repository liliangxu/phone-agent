package io.github.liliangxu.phoneagent.task;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskController {
    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/api/tasks")
    public ResponseEntity<TaskCreateResponse> createTask(@RequestBody CreateTaskRequest request) {
        TaskView created = taskService.createTask(request == null ? null : request.text());
        return ResponseEntity.status(HttpStatus.CREATED).body(new TaskCreateResponse(
                created.taskId(),
                created.slot(),
                created.status(),
                created.createdAt(),
                created.updatedAt()));
    }

    @GetMapping("/api/tasks/{taskId}")
    public TaskView getTask(@PathVariable String taskId) {
        return taskService.getTask(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    @GetMapping(value = "/internal/asterisk/slots/{slot}/start", produces = MediaType.TEXT_PLAIN_VALUE)
    public String startSlot(@PathVariable int slot) {
        return taskService.startSlot(slot);
    }

    @PostMapping("/internal/asterisk/recordings/start")
    public void startRecording(@RequestParam int slot, @RequestParam String taskId) {
        validateTaskId(taskId);
        taskService.startRecording(slot, taskId);
    }

    @PostMapping("/internal/asterisk/recordings")
    public RecordingCallbackResult completeRecording(@RequestParam int slot, @RequestParam String taskId) {
        validateTaskId(taskId);
        return taskService.completeRecording(slot, taskId);
    }

    @PostMapping("/internal/admin/blf/sync")
    public ResponseEntity<BlfSyncResponse> syncBlf(
            @RequestParam(required = false) String reason,
            @RequestBody(required = false) BlfSyncRequest request
    ) {
        String resolvedReason = resolveSyncReason(reason, request);
        BlfSyncResponse response = taskService.syncAllSlots(resolvedReason);
        return ResponseEntity.status(response.success() ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private static String resolveSyncReason(String reason, BlfSyncRequest request) {
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        if (request != null && request.reason() != null && !request.reason().isBlank()) {
            return request.reason();
        }
        return "manual";
    }

    private static void validateTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new TaskValidationException("taskId must not be blank");
        }
    }
}
