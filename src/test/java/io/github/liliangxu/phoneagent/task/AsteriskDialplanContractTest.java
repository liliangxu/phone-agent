package io.github.liliangxu.phoneagent.task;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsteriskDialplanContractTest {

    @Test
    void dialplanKeepsSpringTaskIdsUnchangedForRecordingCallbacks() throws Exception {
        String dialplan = Files.readString(Path.of("ops/asterisk-mvp/extensions.conf"));

        // Spring task IDs contain hyphens; Asterisk must not transform them before Record() or callback URLs.
        assertTrue(dialplan.contains("Set(__PHONE_AGENT_TASK_ID=${TASK_ID_RAW})"));
        assertFalse(dialplan.contains("Set(__PHONE_AGENT_TASK_ID=${FILTER("));
    }

    @Test
    void dialplanExposesDefaultConfiguredBlfExtensions() throws Exception {
        String dialplan = Files.readString(Path.of("ops/asterisk-mvp/extensions.conf"));

        String[] defaultExtensions = {"601", "602", "603", "604", "605", "606", "607", "608"};
        for (int slot = 1; slot <= 8; slot++) {
            String extension = defaultExtensions[slot - 1];
            assertTrue(dialplan.contains("exten => " + extension + ",hint,Custom:phone-agent-slot-" + slot));
            assertTrue(dialplan.contains("exten => " + extension + ",1,Answer()"));
            assertTrue(dialplan.contains("/internal/asterisk/slots/" + slot + "/start"));
        }

        assertFalse(dialplan.matches("(?s).*exten\\s*=>\\s*80[1-8],hint,.*"));
        assertFalse(dialplan.matches("(?s).*exten\\s*=>\\s*80[1-8],1,.*"));
        assertFalse(dialplan.matches("(?s).*exten\\s*=>\\s*\\*\\*80[1-8],1,.*"));
        assertEquals(8, countOccurrences(dialplan, ",hint,Custom:phone-agent-slot-"));
    }

    @Test
    void dialplanRoutesZeroToInboundIntentRecording() throws Exception {
        String dialplan = Files.readString(Path.of("ops/asterisk-mvp/extensions.conf"));

        assertTrue(dialplan.contains("exten => 0,1,Answer()"));
        assertTrue(dialplan.contains("/internal/asterisk/inbound-intents/phone/start"));
        assertTrue(dialplan.contains("Playback(phone-agent/prompts/inbound-intent)"));
        assertTrue(dialplan.contains("Record(/var/spool/asterisk/phone-agent/recordings/${PHONE_AGENT_INBOUND_INTENT_ID}.wav,3,120,k)"));
        assertTrue(dialplan.contains("/internal/asterisk/inbound-intents/phone/recordings?intentId=${URIENCODE(${PHONE_AGENT_INBOUND_INTENT_ID})}"));
    }

    @Test
    void dialplanProvidesIndependentRingPhoneContext() throws Exception {
        String dialplan = Files.readString(Path.of("ops/asterisk-mvp/extensions.conf"));

        assertTrue(dialplan.contains("[phone-agent-ring]"));
        assertTrue(dialplan.contains("exten => s,1,Answer()"));
        assertTrue(dialplan.contains("Playback(phone-agent/prompts/ring-phone)"));
        assertTrue(dialplan.contains("same => n,Hangup()"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
