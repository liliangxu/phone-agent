package io.github.liliangxu.phoneagent.codex;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CodexSessionControllerHttpTest {
    private final CodexSessionService service = org.mockito.Mockito.mock(CodexSessionService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new CodexSessionController(service))
            .setControllerAdvice(new CodexSessionExceptionHandler())
            .build();

    @Test
    void listReturnsSessionViews() throws Exception {
        when(service.list()).thenReturn(List.of(view()));

        mockMvc.perform(get("/api/codex-sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("cs-1"))
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].ttydUrl").value("http://127.0.0.1:49152/"));
    }

    @Test
    void getReturnsSessionViewOrNotFoundError() throws Exception {
        when(service.get("cs-1")).thenReturn(Optional.of(view()));
        when(service.get("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/codex-sessions/cs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cs-1"));

        mockMvc.perform(get("/api/codex-sessions/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CODEX_SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.errorMessage").value("Codex session not found: missing"));
    }

    @Test
    void createReturnsCreatedSessionView() throws Exception {
        when(service.create(any(CreateCodexSessionRequest.class))).thenReturn(view());

        mockMvc.perform(post("/api/codex-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"x","cwd":"/workspace/phone-agent","initialPrompt":"hello"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("cs-1"))
                .andExpect(jsonPath("$.title").value("phone-agent"));
    }

    @Test
    void createMapsValidationAndMissingCommandErrors() throws Exception {
        when(service.create(any(CreateCodexSessionRequest.class)))
                .thenThrow(new CodexSessionException("CODEX_SESSION_VALIDATION_FAILED",
                        "cwd must be an existing directory", HttpStatus.BAD_REQUEST))
                .thenThrow(new CodexSessionException("CODEX_COMMAND_NOT_FOUND",
                        "tmux command not found", HttpStatus.SERVICE_UNAVAILABLE));

        mockMvc.perform(post("/api/codex-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cwd\":\"/missing\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CODEX_SESSION_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errorMessage").value("cwd must be an existing directory"));

        mockMvc.perform(post("/api/codex-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cwd\":\"/workspace/phone-agent\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("CODEX_COMMAND_NOT_FOUND"))
                .andExpect(jsonPath("$.errorMessage").value("tmux command not found"));
    }

    private static CodexSessionView view() {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-06-11T10:00:00+08:00");
        CodexSessionRecord record = new CodexSessionRecord();
        record.setId("cs-1");
        record.setTitle("phone-agent");
        record.setCwd("/workspace/phone-agent");
        record.setStatus(CodexSessionStatus.RUNNING);
        record.setTmuxName("phone-agent-codex-cs-1");
        record.setTtydUrl("http://127.0.0.1:49152/");
        record.setThreadId("019eb51f-542e-7f50-aa46-97045de3aa39");
        record.setJsonlPath("/workspace/.codex/sessions/example.jsonl");
        record.setCreatedAt(timestamp);
        record.setUpdatedAt(timestamp);
        return CodexSessionView.from(record);
    }
}
