package io.github.liliangxu.phoneagent.sip;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SipMessageTest {
    @Test
    void parsesRegisterHeadersAndDigestAuthorization() {
        SipMessage message = SipMessage.parse("""
                REGISTER sip:192.168.10.1 SIP/2.0\r
                Via: SIP/2.0/UDP 192.168.10.2:5060;branch=z9hG4bK;rport\r
                From: <sip:1001@192.168.10.1>;tag=1\r
                Authorization: Digest username="1001", realm="phone-agent.local", nonce="n", uri="sip:192.168.10.1", response="r", qop=auth, nc=00000001, cnonce="c"\r
                Content-Length: 0\r
                \r
                """);

        assertEquals("REGISTER", message.method());
        assertEquals("<sip:1001@192.168.10.1>;tag=1", message.header("From"));
        Map<String, String> auth = SipMessage.parseDigestAuthorization(message.header("Authorization"));
        assertEquals("1001", auth.get("username"));
        assertEquals("auth", auth.get("qop"));
        assertEquals("c", auth.get("cnonce"));
    }
}
