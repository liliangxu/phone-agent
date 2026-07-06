package io.github.liliangxu.phoneagent.task;

import org.springframework.stereotype.Component;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@Component
public class AsteriskManagerClient implements AsteriskAmiClient {
    private final PhoneAgentProperties properties;

    public AsteriskManagerClient(PhoneAgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public void setInUse(int slot) {
        command("devstate change Custom:phone-agent-slot-" + slot + " INUSE");
    }

    @Override
    public void setNotInUse(int slot) {
        command("devstate change Custom:phone-agent-slot-" + slot + " NOT_INUSE");
    }

    @Override
    public void originateRingPhone() {
        action(
                "Action: Originate",
                "Channel: " + properties.getRing().getTarget(),
                "Context: phone-agent-ring",
                "Exten: s",
                "Priority: 1",
                "Timeout: 5000",
                "Async: true",
                "CallerID: Phone Agent"
        );
    }

    /**
     * Uses a short-lived AMI connection for each command. This is simple and
     * adequate for the MVP's low event volume, while keeping the AMI protocol
     * handling auditable.
     */
    private void command(String command) {
        action("Action: Command", "Command: " + command);
    }

    /**
     * Opens a short-lived AMI session, authenticates, sends one action, checks
     * Asterisk accepted it, then logs off. Both CustomDevstate and Originate use
     * this path so auth and response parsing stay consistent.
     */
    private void action(String... actionLines) {
        PhoneAgentProperties.Ami ami = properties.getAmi();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ami.getHost(), ami.getPort()), 3000);
            socket.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            reader.readLine();
            send(writer,
                    "Action: Login",
                    "Username: " + ami.getUsername(),
                    "Secret: " + ami.getSecret(),
                    "Events: off");
            String login = readResponse(reader);
            if (!login.contains("Response: Success")) {
                throw new AsteriskControlException("AMI login failed: " + login);
            }
            send(writer, actionLines);
            String result = readResponse(reader);
            if (!isCommandAccepted(result)) {
                throw new AsteriskControlException("AMI action failed: " + result);
            }
            send(writer, "Action: Logoff");
        } catch (IOException e) {
            throw new AsteriskControlException("AMI action failed", e);
        }
    }

    private static void send(BufferedWriter writer, String... lines) throws IOException {
        for (String line : lines) {
            writer.write(line);
            writer.write("\r\n");
        }
        writer.write("\r\n");
        writer.flush();
    }

    private static String readResponse(BufferedReader reader) throws IOException {
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                break;
            }
            response.append(line).append('\n');
        }
        return response.toString();
    }

    static boolean isCommandAccepted(String response) {
        return response.contains("Response: Success") || response.contains("Response: Follows");
    }
}
