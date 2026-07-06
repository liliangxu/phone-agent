package io.github.liliangxu.phoneagent.sip;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniSipRegistrarTest {
    @Test
    void usesDefaultSipCredentialsWhenNoEnvOrSystemPropertiesAreSet() {
        MiniSipRegistrar.Credentials credentials = MiniSipRegistrar.configuredCredentials(Map.of(), new Properties());

        assertEquals("1001", credentials.extension());
        assertEquals("1001", credentials.authId());
        assertEquals("1001", credentials.password());
    }

    @Test
    void configuredCredentialsReadsProcessEnvironmentAndSystemProperties() {
        MiniSipRegistrar.Credentials credentials = MiniSipRegistrar.configuredCredentials();

        assertFalse(credentials.extension().isBlank());
        assertFalse(credentials.authId().isBlank());
        assertFalse(credentials.password().isBlank());
    }

    @Test
    void readsCustomSipCredentialsFromEnvironment() {
        MiniSipRegistrar.Credentials credentials = MiniSipRegistrar.configuredCredentials(Map.of(
                "PHONE_AGENT_SIP_EXTENSION", "1002",
                "PHONE_AGENT_SIP_AUTH_ID", "auth1002",
                "PHONE_AGENT_SIP_PASSWORD", "secret"
        ), new Properties());

        assertEquals("1002", credentials.extension());
        assertEquals("auth1002", credentials.authId());
        assertEquals("secret", credentials.password());
    }

    @Test
    void systemPropertiesOverrideEnvironmentForOneOffRegistrarRuns() {
        Properties properties = new Properties();
        properties.setProperty("phoneAgent.sip.extension", "2001");
        properties.setProperty("phoneAgent.sip.authId", "auth2001");
        properties.setProperty("phoneAgent.sip.password", "override-secret");

        MiniSipRegistrar.Credentials credentials = MiniSipRegistrar.configuredCredentials(Map.of(
                "PHONE_AGENT_SIP_EXTENSION", "1002",
                "PHONE_AGENT_SIP_AUTH_ID", "auth1002",
                "PHONE_AGENT_SIP_PASSWORD", "env-secret"
        ), properties);

        assertEquals("2001", credentials.extension());
        assertEquals("auth2001", credentials.authId());
        assertEquals("override-secret", credentials.password());
    }

    @Test
    void blankSystemPropertiesFallBackToEnvironmentAndBlankEnvironmentFallsBackToDefaults() {
        Properties properties = new Properties();
        properties.setProperty("phoneAgent.sip.extension", " \t ");
        properties.setProperty("phoneAgent.sip.authId", "auth2001");

        MiniSipRegistrar.Credentials credentials = MiniSipRegistrar.configuredCredentials(Map.of(
                "PHONE_AGENT_SIP_EXTENSION", "1002",
                "PHONE_AGENT_SIP_PASSWORD", " \t "
        ), properties);

        assertEquals("1002", credentials.extension());
        assertEquals("auth2001", credentials.authId());
        assertEquals("1001", credentials.password());
    }

    @Test
    void acceptsRegisterDigestWithCustomCredentials() {
        MiniSipRegistrar.Credentials credentials = new MiniSipRegistrar.Credentials("1002", "auth1002", "custom-secret");

        assertTrue(MiniSipRegistrar.validRegisterDigest(credentials, digest("auth1002", "custom-secret")));
    }

    @Test
    void rejectsRegisterDigestForWrongUser() {
        MiniSipRegistrar.Credentials credentials = new MiniSipRegistrar.Credentials("1002", "auth1002", "custom-secret");

        assertFalse(MiniSipRegistrar.validRegisterDigest(credentials, digest("other-user", "custom-secret")));
    }

    @Test
    void rejectsRegisterDigestForWrongPassword() {
        MiniSipRegistrar.Credentials credentials = new MiniSipRegistrar.Credentials("1002", "auth1002", "custom-secret");

        assertFalse(MiniSipRegistrar.validRegisterDigest(credentials, digest("auth1002", "1001")));
    }

    @Test
    void challengesRegisterWhenDigestIsMissing() {
        MiniSipRegistrar.Credentials credentials = new MiniSipRegistrar.Credentials("1002", "auth1002", "custom-secret");

        String response = MiniSipRegistrar.registerResponse(credentials, "nonce-1", request(null));

        assertTrue(response.startsWith("SIP/2.0 401 Unauthorized"));
        assertTrue(response.contains("WWW-Authenticate: Digest realm=\"phone-agent.local\", nonce=\"nonce-1\""));
    }

    @Test
    void acceptsRegisterWhenDigestMatchesCustomCredentials() {
        MiniSipRegistrar.Credentials credentials = new MiniSipRegistrar.Credentials("1002", "auth1002", "custom-secret");

        String response = MiniSipRegistrar.registerResponse(credentials, "nonce-1", request(authorization("auth1002", "custom-secret")));

        assertTrue(response.startsWith("SIP/2.0 200 OK"));
        assertTrue(response.contains("Contact: <sip:1002@127.0.0.1>;expires=120"));
        assertTrue(response.contains("Expires: 120"));
    }

    @Test
    void challengesRegisterWhenDigestPasswordDoesNotMatchCustomCredentials() {
        MiniSipRegistrar.Credentials credentials = new MiniSipRegistrar.Credentials("1002", "auth1002", "custom-secret");

        String response = MiniSipRegistrar.registerResponse(credentials, "nonce-1", request(authorization("auth1002", "wrong-secret")));

        assertTrue(response.startsWith("SIP/2.0 401 Unauthorized"));
    }

    private static Map<String, String> digest(String username, String password) {
        String realm = "phone-agent.local";
        String nonce = "test-nonce";
        String uri = "sip:phone-agent.local";
        String nc = "00000001";
        String cnonce = "test-cnonce";
        String qop = "auth";
        String ha1 = DigestAuth.md5(username + ":" + realm + ":" + password);
        String ha2 = DigestAuth.md5("REGISTER:" + uri);
        String response = DigestAuth.md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
        return Map.of(
                "username", username,
                "realm", realm,
                "nonce", nonce,
                "uri", uri,
                "nc", nc,
                "cnonce", cnonce,
                "qop", qop,
                "response", response
        );
    }

    private static SipMessage request(String authorization) {
        String authHeader = authorization == null ? "" : "Authorization: " + authorization + "\r\n";
        return SipMessage.parse("""
                REGISTER sip:phone-agent.local SIP/2.0\r
                Via: SIP/2.0/UDP 127.0.0.1:5062;branch=z9hG4bK-test\r
                From: <sip:1002@phone-agent.local>;tag=from-tag\r
                To: <sip:1002@phone-agent.local>\r
                Call-ID: call-1\r
                CSeq: 1 REGISTER\r
                Contact: <sip:1002@127.0.0.1>\r
                Expires: 120\r
                """ + authHeader + "\r\n");
    }

    private static String authorization(String username, String password) {
        return "Digest " + digest(username, password).entrySet().stream()
                .map(entry -> entry.getKey() + "=\"" + entry.getValue() + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
