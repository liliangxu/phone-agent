package io.github.liliangxu.phoneagent.ring;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RingPhoneControllerTest {
    private final RingPhoneService service = mock(RingPhoneService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new RingPhoneController(service))
            .setControllerAdvice(new RingPhoneExceptionHandler())
            .build();

    @Test
    void ringReturnsAttemptResponse() throws Exception {
        when(service.ring()).thenReturn(new RingPhoneResponse("ring-1", RingPhoneStatus.STARTED));

        mockMvc.perform(post("/api/ring-phone"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value("ring-1"))
                .andExpect(jsonPath("$.status").value("STARTED"));
    }

    @Test
    void busyMapsToConflict() throws Exception {
        when(service.ring()).thenThrow(new RingPhoneException("RING_PHONE_BUSY", "busy", HttpStatus.CONFLICT));

        mockMvc.perform(post("/api/ring-phone"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RING_PHONE_BUSY"))
                .andExpect(jsonPath("$.errorMessage").value("busy"));
    }
}
