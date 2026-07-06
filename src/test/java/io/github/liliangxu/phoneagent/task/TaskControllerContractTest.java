package io.github.liliangxu.phoneagent.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskControllerContractTest {
    private TaskService taskService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TaskController(taskService))
                .setControllerAdvice(new TaskExceptionHandler())
                .build();
    }

    @Test
    void createTaskReturns201AndSummary() throws Exception {
        when(taskService.createTask("电话通知")).thenReturn(view("task-1", TaskStatus.NOTIFIED, 1));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"电话通知\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.slot").value(1))
                .andExpect(jsonPath("$.status").value("NOTIFIED"));
    }

    @Test
    void getTaskReturns404ForUnknownTask() throws Exception {
        when(taskService.getTask("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/tasks/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorMessage").value("Task not found: missing"));
    }

    @Test
    void internalStartReturnsPlainTaskIdOrBadRequest() throws Exception {
        when(taskService.startSlot(1)).thenReturn("task-1");
        when(taskService.startSlot(9)).thenThrow(new InvalidSlotException(9));

        mockMvc.perform(get("/internal/asterisk/slots/1/start"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("task-1"));

        mockMvc.perform(get("/internal/asterisk/slots/9/start"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordingConflictReturns409() throws Exception {
        when(taskService.completeRecording(1, "other")).thenThrow(new TaskConflictException(1, "other"));

        mockMvc.perform(post("/internal/asterisk/recordings")
                        .param("slot", "1")
                        .param("taskId", "other"))
                .andExpect(status().isConflict());
    }

    @Test
    void recordingStartEndpointInvokesServiceAndMapsConflictTo409() throws Exception {
        doThrow(new TaskConflictException(1, "other")).when(taskService).startRecording(1, "other");

        mockMvc.perform(post("/internal/asterisk/recordings/start")
                        .param("slot", "1")
                        .param("taskId", "task-1"))
                .andExpect(status().isOk());
        verify(taskService).startRecording(1, "task-1");

        mockMvc.perform(post("/internal/asterisk/recordings/start")
                        .param("slot", "1")
                        .param("taskId", "other"))
                .andExpect(status().isConflict());
    }

    @Test
    void recordingEndpointsRejectBlankTaskIdAsBadRequest() throws Exception {
        mockMvc.perform(post("/internal/asterisk/recordings/start")
                        .param("slot", "1")
                        .param("taskId", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("taskId must not be blank"));

        mockMvc.perform(post("/internal/asterisk/recordings")
                        .param("slot", "1")
                        .param("taskId", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("taskId must not be blank"));
    }

    @Test
    void blfSyncEndpointUsesManualDefaultAndMapsInProgressTo409() throws Exception {
        when(taskService.syncAllSlots("manual")).thenReturn(new BlfSyncResponse(
                true,
                "manual",
                List.of(new BlfSyncSlotResult(1, "NOT_INUSE", null)),
                List.of(),
                null
        ));
        when(taskService.syncAllSlots("busy")).thenThrow(new BlfSyncInProgressException("busy"));

        mockMvc.perform(post("/internal/admin/blf/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.reason").value("manual"))
                .andExpect(jsonPath("$.slots[0].slot").value(1))
                .andExpect(jsonPath("$.slots[0].targetDeviceState").value("NOT_INUSE"));
        verify(taskService).syncAllSlots("manual");

        mockMvc.perform(post("/internal/admin/blf/sync")
                        .param("reason", "busy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("BLF_SYNC_IN_PROGRESS"));
    }

    @Test
    void blfSyncEndpointAcceptsJsonReasonAndMapsPartialFailureTo500() throws Exception {
        when(taskService.syncAllSlots("startup")).thenReturn(new BlfSyncResponse(
                false,
                "startup",
                List.of(new BlfSyncSlotResult(1, "INUSE", "task-1")),
                List.of(1),
                "BLF_SYNC_PARTIAL_FAILURE"
        ));

        mockMvc.perform(post("/internal/admin/blf/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"startup\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.reason").value("startup"))
                .andExpect(jsonPath("$.slots[0].taskId").value("task-1"))
                .andExpect(jsonPath("$.failedSlots[0]").value(1))
                .andExpect(jsonPath("$.error").value("BLF_SYNC_PARTIAL_FAILURE"));
    }

    private static TaskView view(String taskId, TaskStatus status, Integer slot) {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-09T12:00:00+08:00");
        return new TaskView(taskId, null, "电话通知", status, null, null, slot, null, null, now, now);
    }
}
