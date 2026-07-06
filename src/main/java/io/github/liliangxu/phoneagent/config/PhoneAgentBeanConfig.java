package io.github.liliangxu.phoneagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.liliangxu.phoneagent.task.RecordingStore;
import io.github.liliangxu.phoneagent.task.SlotAudioStore;

import java.time.Clock;

@Configuration
public class PhoneAgentBeanConfig {
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    Clock phoneAgentClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    SlotAudioStore slotAudioStore(PhoneAgentProperties properties) {
        return new SlotAudioStore(properties.getRuntimeDir().resolve("sounds").resolve("slots"));
    }

    @Bean
    RecordingStore recordingStore(PhoneAgentProperties properties) {
        return new RecordingStore(properties.getRuntimeDir().resolve("recordings"));
    }
}
