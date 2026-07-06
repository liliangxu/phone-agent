package io.github.liliangxu.phoneagent.sip;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigestAuthTest {
    @Test
    void verifiesRfcDigestWithQopAuth() {
        String response = DigestAuth.md5(
                DigestAuth.md5("1001:phone-agent.local:1001")
                        + ":nonce-1:00000001:client-1:auth:"
                        + DigestAuth.md5("REGISTER:sip:192.168.10.1"));

        assertTrue(DigestAuth.verify("REGISTER", "1001", Map.of(
                "username", "1001",
                "realm", "phone-agent.local",
                "nonce", "nonce-1",
                "uri", "sip:192.168.10.1",
                "response", response,
                "qop", "auth",
                "nc", "00000001",
                "cnonce", "client-1"
        )));
    }

    @Test
    void rejectsWrongPassword() {
        String response = DigestAuth.md5(
                DigestAuth.md5("1001:phone-agent.local:1001")
                        + ":nonce-1:"
                        + DigestAuth.md5("REGISTER:sip:192.168.10.1"));

        assertFalse(DigestAuth.verify("REGISTER", "wrong", Map.of(
                "username", "1001",
                "realm", "phone-agent.local",
                "nonce", "nonce-1",
                "uri", "sip:192.168.10.1",
                "response", response
        )));
    }
}
