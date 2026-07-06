package io.github.liliangxu.phoneagent.codex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCodexProcessGatewayTest {
    @TempDir
    Path tempDir;

    @Test
    void submitPromptLoadsNamedBufferAndPastesItToTargetSession() {
        List<List<String>> commands = new ArrayList<>();
        LocalCodexProcessGateway gateway = new LocalCodexProcessGateway(command -> {
            commands.add(List.copyOf(command));
            return 0;
        });

        gateway.submitPrompt("tmux", "phone-agent-codex-cs-1", Path.of("/tmp/prompt.txt"));

        assertEquals(List.of(
                List.of("tmux", "load-buffer", "-b", "phone-agent-prompt-phone-agent-codex-cs-1", "/tmp/prompt.txt"),
                List.of("tmux", "paste-buffer", "-d", "-b", "phone-agent-prompt-phone-agent-codex-cs-1", "-t", "phone-agent-codex-cs-1"),
                List.of("tmux", "send-keys", "-t", "phone-agent-codex-cs-1", "Enter")
        ), commands);
    }

    @Test
    void startTmuxConfiguresTerminalForConsoleScrollback() {
        List<List<String>> commands = new ArrayList<>();
        LocalCodexProcessGateway gateway = new LocalCodexProcessGateway(command -> {
            commands.add(List.copyOf(command));
            return 0;
        });

        gateway.startTmux("tmux", "phone-agent-codex-cs-1", Path.of("/workspace"), "codex", null);

        assertEquals(List.of(
                List.of("tmux", "new-session", "-d", "-s", "phone-agent-codex-cs-1", "-c", "/workspace",
                        "codex", "--model", "gpt-5.5", "-c", "model_reasoning_effort=\"medium\"", "--no-alt-screen"),
                List.of("tmux", "set-option", "-t", "phone-agent-codex-cs-1", "status", "off"),
                List.of("tmux", "set-option", "-t", "phone-agent-codex-cs-1", "history-limit", "50000"),
                List.of("tmux", "set-option", "-t", "phone-agent-codex-cs-1", "mouse", "on")
        ), commands);
    }

    @Test
    void startTmuxPassesInitialPromptAsCodexArgument() {
        List<List<String>> commands = new ArrayList<>();
        LocalCodexProcessGateway gateway = new LocalCodexProcessGateway(command -> {
            commands.add(List.copyOf(command));
            return 0;
        });

        gateway.startTmux("tmux", "phone-agent-codex-cs-1", Path.of("/workspace"), "codex", "请检查测试失败");

        assertEquals(List.of("tmux", "new-session", "-d", "-s", "phone-agent-codex-cs-1", "-c", "/workspace",
                        "codex", "--model", "gpt-5.5", "-c", "model_reasoning_effort=\"medium\"", "--no-alt-screen", "请检查测试失败"),
                commands.getFirst());
    }

    @Test
    void startResumeTmuxConfiguresTerminalForConsoleScrollback() {
        List<List<String>> commands = new ArrayList<>();
        LocalCodexProcessGateway gateway = new LocalCodexProcessGateway(command -> {
            commands.add(List.copyOf(command));
            return 0;
        });

        gateway.startResumeTmux("tmux", "phone-agent-codex-cs-1", Path.of("/workspace"), "codex", "thread-1");

        assertEquals(List.of(
                List.of("tmux", "new-session", "-d", "-s", "phone-agent-codex-cs-1", "-c", "/workspace",
                        "codex", "--model", "gpt-5.5", "-c", "model_reasoning_effort=\"medium\"", "--no-alt-screen", "resume", "thread-1"),
                List.of("tmux", "set-option", "-t", "phone-agent-codex-cs-1", "status", "off"),
                List.of("tmux", "set-option", "-t", "phone-agent-codex-cs-1", "history-limit", "50000"),
                List.of("tmux", "set-option", "-t", "phone-agent-codex-cs-1", "mouse", "on")
        ), commands);
    }

    @Test
    void resumeTmuxWithPromptRespawnsExistingPaneWithPromptArgument() throws Exception {
        Path prompt = tempDir.resolve("reply.txt");
        Files.writeString(prompt, "电话回复：知道了");
        List<List<String>> commands = new ArrayList<>();
        LocalCodexProcessGateway gateway = new LocalCodexProcessGateway(command -> {
            commands.add(List.copyOf(command));
            return 0;
        });

        gateway.resumeTmuxWithPrompt("tmux", "phone-agent-codex-cs-1", Path.of("/workspace"), "codex", "thread-1", prompt);

        assertEquals(List.of(
                List.of("tmux", "respawn-pane", "-k", "-t", "phone-agent-codex-cs-1", "-c", "/workspace",
                        "codex", "--model", "gpt-5.5", "-c", "model_reasoning_effort=\"medium\"", "--no-alt-screen",
                        "resume", "thread-1", "电话回复：知道了"),
                List.of("tmux", "set-option", "-t", "phone-agent-codex-cs-1", "status", "off"),
                List.of("tmux", "set-option", "-t", "phone-agent-codex-cs-1", "history-limit", "50000"),
                List.of("tmux", "set-option", "-t", "phone-agent-codex-cs-1", "mouse", "on")
        ), commands);
    }

    @Test
    void startTtydIncludesScrollbackClientOptionBeforeAttachingTmux() throws Exception {
        Path capture = tempDir.resolve("ttyd-args.txt");
        Path fakeTtyd = tempDir.resolve("fake-ttyd.sh");
        Files.writeString(fakeTtyd, "#!/bin/sh\nprintf '%s\\n' \"$@\" > "
                + shellQuote(capture) + "\nsleep 30\n");
        assertTrue(fakeTtyd.toFile().setExecutable(true));
        LocalCodexProcessGateway gateway = new LocalCodexProcessGateway(command -> 0);
        CodexProcessGateway.TtydProcess process = null;
        try {
            process = gateway.startTtyd(fakeTtyd.toString(), "tmux", "phone-agent-codex-cs-1", 49152);
            for (int i = 0; i < 40 && !Files.exists(capture); i++) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(capture), "fake ttyd should capture its argv");
            List<String> args = Files.readAllLines(capture);

            assertTrue(args.contains("--client-option"), "ttyd must receive a client option flag");
            assertTrue(args.stream().anyMatch(arg -> arg.startsWith("scrollback=")),
                    "ttyd must enable browser-side scrollback");
            assertEquals(List.of("tmux", "attach-session", "-t", "phone-agent-codex-cs-1"),
                    args.subList(args.size() - 4, args.size()));
        } finally {
            if (process != null) {
                gateway.killProcess(process.pid());
            }
        }
    }

    @Test
    void readinessCommandLookupUsesWidePsOutputForLongTtydCommands() {
        assertEquals(List.of("ps", "-ww", "-p", "12345", "-o", "command="),
                LocalCodexProcessGateway.processCommandLookupCommand(12345));
    }

    @Test
    void readinessCommandMatcherAcceptsTtydRewrittenArgvWithoutTmuxTargetValue() {
        String rewritten = "ttyd --interface 127.0.0.1 --port 64442 --writable "
                + "--client-option scrollback 50000 tmux attach-session -t";

        assertTrue(LocalCodexProcessGateway.looksLikeExpectedTtydCommand(rewritten, 64442));
        assertFalse(LocalCodexProcessGateway.looksLikeExpectedTtydCommand(rewritten, 64443));
    }

    private static String shellQuote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }
}
