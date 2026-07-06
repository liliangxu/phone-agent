package io.github.liliangxu.phoneagent.task;

import org.junit.jupiter.api.Test;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsteriskManagerClientTest {
    @Test
    void amiClientContractUsesInUseInsteadOfRinging() {
        assertDoesNotThrow(() -> AsteriskAmiClient.class.getMethod("setInUse", int.class));
        assertThrows(NoSuchMethodException.class, () -> AsteriskAmiClient.class.getMethod("setRinging", int.class));
    }

    @Test
    void acceptsSuccessAndFollowsAmiCommandResponses() {
        assertTrue(AsteriskManagerClient.isCommandAccepted("Response: Success\nMessage: Command output follows\n"));
        assertTrue(AsteriskManagerClient.isCommandAccepted("""
                Response: Follows
                Privilege: Command
                Device state changed.
                --END COMMAND--
                """));
        assertFalse(AsteriskManagerClient.isCommandAccepted("Response: Error\nMessage: Permission denied\n"));
    }

    @Test
    void sendsInUseAndNotInUseDevstateCommandsToAmi() throws Exception {
        List<List<String>> actions = new ArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            FutureTask<Void> amiServer = new FutureTask<>(() -> {
                acceptAmiCommand(server, actions);
                acceptAmiCommand(server, actions);
                return null;
            });
            Thread serverThread = new Thread(amiServer, "fake-ami-server");
            serverThread.start();

            AsteriskManagerClient client = new AsteriskManagerClient(propertiesFor(server.getLocalPort()));
            client.setInUse(3);
            client.setNotInUse(3);

            amiServer.get();
        }

        assertEquals("devstate change Custom:phone-agent-slot-3 INUSE", commandFrom(actions.get(1)));
        assertEquals("devstate change Custom:phone-agent-slot-3 NOT_INUSE", commandFrom(actions.get(4)));
    }

    @Test
    void sendsConfiguredOriginateActionForRingPhone() throws Exception {
        List<List<String>> actions = new ArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            FutureTask<Void> amiServer = new FutureTask<>(() -> {
                acceptAmiCommand(server, actions);
                return null;
            });
            Thread serverThread = new Thread(amiServer, "fake-ami-server");
            serverThread.start();

            PhoneAgentProperties properties = propertiesFor(server.getLocalPort());
            properties.getRing().setTarget("PJSIP/1002");
            new AsteriskManagerClient(properties).originateRingPhone();

            amiServer.get();
        }

        List<String> originate = actions.get(1);
        assertTrue(originate.contains("Action: Originate"));
        assertTrue(originate.contains("Channel: PJSIP/1002"));
        assertTrue(originate.contains("Context: phone-agent-ring"));
        assertTrue(originate.contains("Exten: s"));
        assertTrue(originate.contains("Timeout: 5000"));
        assertTrue(originate.contains("Async: true"));
    }

    private static PhoneAgentProperties propertiesFor(int port) {
        PhoneAgentProperties properties = new PhoneAgentProperties();
        properties.getAmi().setHost("127.0.0.1");
        properties.getAmi().setPort(port);
        properties.getAmi().setUsername("user");
        properties.getAmi().setSecret("secret");
        return properties;
    }

    private static void acceptAmiCommand(ServerSocket server, List<List<String>> actions) throws IOException {
        try (Socket socket = server.accept();
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            writeResponse(writer, "Asterisk Call Manager/5.0\r\n");
            actions.add(readAction(reader));
            writeResponse(writer, "Response: Success\r\nMessage: Authentication accepted\r\n\r\n");
            actions.add(readAction(reader));
            writeResponse(writer, "Response: Follows\r\nPrivilege: Command\r\nDevice state changed.\r\n--END COMMAND--\r\n\r\n");
            actions.add(readAction(reader));
        }
    }

    private static List<String> readAction(BufferedReader reader) throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    private static void writeResponse(BufferedWriter writer, String response) throws IOException {
        writer.write(response);
        writer.flush();
    }

    private static String commandFrom(List<String> action) {
        return action.stream()
                .filter(line -> line.startsWith("Command: "))
                .map(line -> line.substring("Command: ".length()))
                .findFirst()
                .orElseThrow(AssertionError::new);
    }
}
