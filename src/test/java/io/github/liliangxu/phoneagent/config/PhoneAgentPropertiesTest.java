package io.github.liliangxu.phoneagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.OriginTrackedMapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhoneAgentPropertiesTest {
    @Test
    void bindsAsrNestedPropertiesFromDocumentedNames() {
        OriginTrackedMapPropertySource source = new OriginTrackedMapPropertySource("test", Map.of(
                "phone-agent.asr.whisper-command", "/opt/whisper/main",
                "phone-agent.asr.model-path", "/models/ggml.bin",
                "phone-agent.asr.language", "zh",
                "phone-agent.codex.prompt-language", "en"
        ));

        PhoneAgentProperties properties = new Binder(ConfigurationPropertySources.from(source))
                .bind("phone-agent", Bindable.of(PhoneAgentProperties.class))
                .orElseThrow(AssertionError::new);

        assertEquals("/opt/whisper/main", properties.getAsr().getWhisperCommand());
        assertEquals("/models/ggml.bin", properties.getAsr().getModelPath());
        assertEquals("zh", properties.getAsr().getLanguage());
        assertEquals("en", properties.getCodex().getPromptLanguage());
    }

    @Test
    void defaultPhoneConfigPreservesValidatedExampleMapping() {
        PhoneAgentProperties properties = new PhoneAgentProperties();

        assertEquals("1001", properties.getSip().getExtension());
        assertEquals("1001", properties.getSip().getAuthId());
        assertEquals("1001", properties.getSip().getPassword());
        assertEquals("PJSIP/1001", properties.getRing().getTarget());
        assertEquals("phone-agent-slots", properties.getBlf().getEventlistUri());
        assertEquals(8, properties.blfSlots().size());
        assertEquals(new BlfSlot(1, "601"), properties.blfSlots().getFirst());
        assertEquals(new BlfSlot(8, "608"), properties.blfSlots().getLast());
    }

    @Test
    void bindsCustomSipRingAndBlfConfigInDeclaredOrder() {
        PhoneAgentProperties properties = bind(Map.of(
                "phone-agent.sip.extension", "1002",
                "phone-agent.sip.auth-id", "auth_1002",
                "phone-agent.sip.password", "custom-password",
                "phone-agent.ring.target", "PJSIP/1002",
                "phone-agent.blf.eventlist-uri", "custom.slots",
                "phone-agent.blf.extensions", "804,801,803,802"
        ));

        properties.validate();

        assertEquals("1002", properties.getSip().getExtension());
        assertEquals("auth_1002", properties.getSip().getAuthId());
        assertEquals("custom-password", properties.getSip().getPassword());
        assertEquals("PJSIP/1002", properties.getRing().getTarget());
        assertEquals("custom.slots", properties.getBlf().getEventlistUri());
        assertEquals(new BlfSlot(1, "804"), properties.blfSlots().get(0));
        assertEquals(new BlfSlot(2, "801"), properties.blfSlots().get(1));
        assertEquals(new BlfSlot(4, "802"), properties.blfSlots().get(3));
    }

    @Test
    void rejectsInvalidPhoneConfigBranches() {
        assertInvalid(Map.of("phone-agent.sip.extension", "1002 bad"));
        assertInvalidProperty(properties -> properties.getSip().setExtension(null));
        assertInvalid(Map.of("phone-agent.sip.auth-id", ""));
        assertInvalid(Map.of("phone-agent.sip.password", "line\nbreak"));
        assertInvalidProperty(properties -> properties.getSip().setPassword(null));
        assertInvalidProperty(properties -> properties.getSip().setPassword(" \t "));
        assertInvalidProperty(properties -> properties.getSip().setPassword("line\rbreak"));
        assertInvalid(Map.of("phone-agent.ring.target", "PJSIP/1002;rm"));
        assertInvalidProperty(properties -> properties.getRing().setTarget(null));
        assertInvalidProperty(properties -> properties.getRing().setTarget(" \t "));
        assertInvalid(Map.of("phone-agent.blf.eventlist-uri", "bad uri"));
        assertInvalid(Map.of("phone-agent.blf.extensions", ""));
        assertInvalidProperty(properties -> properties.getBlf().setExtensions(null));
        assertInvalidProperty(properties -> properties.getBlf().setExtensions(java.util.Arrays.asList("801", null)));
        assertInvalid(Map.of("phone-agent.blf.extensions", "801,802,801"));
        assertInvalid(Map.of("phone-agent.blf.extensions", "801,abc"));
        assertInvalid(Map.of("phone-agent.blf.extensions", "801,802,803,804,805,806,807,808,809"));
    }

    private static PhoneAgentProperties bind(Map<String, String> values) {
        OriginTrackedMapPropertySource source = new OriginTrackedMapPropertySource("test", values);
        return new Binder(ConfigurationPropertySources.from(source))
                .bind("phone-agent", Bindable.of(PhoneAgentProperties.class))
                .orElseThrow(AssertionError::new);
    }

    private static void assertInvalid(Map<String, String> values) {
        PhoneAgentProperties properties = bind(values);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    private static void assertInvalidProperty(java.util.function.Consumer<PhoneAgentProperties> mutation) {
        PhoneAgentProperties properties = new PhoneAgentProperties();
        mutation.accept(properties);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
