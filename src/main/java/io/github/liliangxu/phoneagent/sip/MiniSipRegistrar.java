package io.github.liliangxu.phoneagent.sip;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;

/**
 * Minimal host-side SIP registrar for validating a directly connected GXP1630.
 *
 * <p>It is not a PBX replacement. It only handles REGISTER with Digest auth so we can
 * prove that the phone can register against a process running in this project on the Mac
 * host. The production path should still use Asterisk/FreePBX for call control.</p>
 */
public final class MiniSipRegistrar {
    private static final String DEFAULT_BIND = "0.0.0.0";
    private static final int DEFAULT_PORT = 5060;
    private static final String REALM = "phone-agent.local";
    private static final String DEFAULT_EXTENSION = "1001";
    private static final String DEFAULT_AUTH_ID = "1001";
    private static final String DEFAULT_PASSWORD = "1001";

    private final String nonce = newNonce();
    private final DatagramSocket socket;
    private final Credentials credentials;

    private MiniSipRegistrar(String bindAddress, int port, Credentials credentials) throws IOException {
        this.socket = new DatagramSocket(port, InetAddress.getByName(bindAddress));
        this.credentials = credentials;
    }

    public static void main(String[] args) throws IOException {
        String bindAddress = args.length > 0 ? args[0] : DEFAULT_BIND;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        new MiniSipRegistrar(bindAddress, port, configuredCredentials()).serve();
    }

    private void serve() throws IOException {
        System.out.printf("Mini SIP registrar listening on %s:%d, extension=%s, authId=%s%n",
                socket.getLocalAddress().getHostAddress(), socket.getLocalPort(), credentials.extension(), credentials.authId());
        byte[] buffer = new byte[8192];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            SipMessage request = SipMessage.parse(message);
            if (!"REGISTER".equals(request.method())) {
                System.out.printf("%s ignored %s from %s:%d%n",
                        Instant.now(), request.startLine(), packet.getAddress().getHostAddress(), packet.getPort());
                continue;
            }
            handleRegister(packet, request);
        }
    }

    private void handleRegister(DatagramPacket packet, SipMessage request) throws IOException {
        String from = packet.getAddress().getHostAddress() + ":" + packet.getPort();
        String response = registerResponse(credentials, nonce, request);
        if (response.startsWith("SIP/2.0 401 ")) {
            System.out.printf("%s REGISTER challenge sent to %s, callId=%s%n", Instant.now(), from, request.header("Call-ID"));
            send(packet, response);
            return;
        }

        System.out.printf("%s REGISTER accepted from %s, contact=%s%n", Instant.now(), from, request.header("Contact"));
        send(packet, response);
    }

    /**
     * Selects the SIP REGISTER response after digest validation. Keeping this
     * branch separate from UDP send/receive lets tests cover credential behavior
     * without binding a socket or entering the registrar's infinite CLI loop.
     */
    static String registerResponse(Credentials credentials, String nonce, SipMessage request) {
        Map<String, String> auth = SipMessage.parseDigestAuthorization(request.header("Authorization"));
        return validRegisterDigest(credentials, auth) ? ok(request) : unauthorized(request, nonce);
    }

    private static String unauthorized(SipMessage request, String nonce) {
        return responseBase("401 Unauthorized", request)
                + "WWW-Authenticate: Digest realm=\"" + REALM + "\", nonce=\"" + nonce + "\", algorithm=MD5, qop=\"auth\"\r\n"
                + "Content-Length: 0\r\n\r\n";
    }

    private static String ok(SipMessage request) {
        String contact = request.header("Contact");
        String expires = request.headerOrDefault("Expires", "3600");
        return responseBase("200 OK", request)
                + (contact == null ? "" : "Contact: " + contact + ";expires=" + expires + "\r\n")
                + "Expires: " + expires + "\r\n"
                + "Content-Length: 0\r\n\r\n";
    }

    private static String responseBase(String status, SipMessage request) {
        StringBuilder response = new StringBuilder();
        response.append("SIP/2.0 ").append(status).append("\r\n");
        for (String via : request.headers("Via")) {
            response.append("Via: ").append(via).append("\r\n");
        }
        appendHeader(response, "From", request.header("From"));
        appendHeader(response, "To", request.header("To"));
        appendHeader(response, "Call-ID", request.header("Call-ID"));
        appendHeader(response, "CSeq", request.header("CSeq"));
        response.append("Server: phone-agent-mini-registrar\r\n");
        return response.toString();
    }

    private void send(DatagramPacket requestPacket, String response) throws IOException {
        byte[] data = response.getBytes(StandardCharsets.UTF_8);
        DatagramPacket responsePacket = new DatagramPacket(
                data, data.length, requestPacket.getAddress(), requestPacket.getPort());
        socket.send(responsePacket);
    }

    private static void appendHeader(StringBuilder response, String name, String value) {
        if (value != null) {
            response.append(name).append(": ").append(value).append("\r\n");
        }
    }

    private static String newNonce() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static Credentials configuredCredentials() {
        return configuredCredentials(System.getenv(), System.getProperties());
    }

    /**
     * Checks REGISTER digest credentials against the configured auth ID and
     * password. Package visibility keeps the production API private while
     * allowing tests to prove the registrar is not still using hardcoded
     * default credentials for digest verification.
     */
    static boolean validRegisterDigest(Credentials credentials, Map<String, String> auth) {
        return credentials.authId().equals(auth.get("username"))
                && DigestAuth.verify("REGISTER", credentials.password(), auth);
    }

    /**
     * Resolves standalone registrar credentials without Spring. System
     * properties take precedence so local one-off commands can override exported
     * shell environment while keeping the password out of startup logs.
     */
    static Credentials configuredCredentials(Map<String, String> env, Properties properties) {
        return new Credentials(
                propertyOrEnv(properties, env, "phoneAgent.sip.extension", "PHONE_AGENT_SIP_EXTENSION", DEFAULT_EXTENSION),
                propertyOrEnv(properties, env, "phoneAgent.sip.authId", "PHONE_AGENT_SIP_AUTH_ID", DEFAULT_AUTH_ID),
                propertyOrEnv(properties, env, "phoneAgent.sip.password", "PHONE_AGENT_SIP_PASSWORD", DEFAULT_PASSWORD)
        );
    }

    private static String propertyOrEnv(Properties properties, Map<String, String> env, String propertyName, String envName, String defaultValue) {
        String property = properties.getProperty(propertyName);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String value = env.get(envName);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    record Credentials(String extension, String authId, String password) {
    }
}
