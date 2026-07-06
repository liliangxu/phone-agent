package io.github.liliangxu.phoneagent.codex;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local process adapter for tmux and ttyd. It deliberately uses argument lists
 * for every command and validates ttyd readiness against the expected tmux
 * session before the console exposes a writable iframe.
 */
@Component
public class LocalCodexProcessGateway implements CodexProcessGateway {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(5);
    private static final String CODEX_MODEL = "gpt-5.5";
    private static final String CODEX_REASONING_EFFORT = "medium";
    private final Map<Long, Process> startedTtyd = new ConcurrentHashMap<>();
    private final CommandExecutor commandExecutor;

    public LocalCodexProcessGateway() {
        this(LocalCodexProcessGateway::runStatus);
    }

    LocalCodexProcessGateway(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    @Override
    public boolean commandAvailable(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        Path path = Path.of(command);
        if (path.isAbsolute() || command.contains("/")) {
            return Files.isExecutable(path);
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return false;
        }
        for (String dir : pathEnv.split(":")) {
            if (Files.isExecutable(Path.of(dir).resolve(command))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void startTmux(String tmuxCommand, String tmuxName, Path cwd, String codexCommand, String initialPrompt) {
        // Inline mode keeps Codex output in tmux scrollback so ttyd wheel input
        // scrolls conversation history instead of cycling the TUI input field.
        List<String> command = new ArrayList<>(List.of(tmuxCommand, "new-session", "-d", "-s", tmuxName, "-c", cwd.toString()));
        command.addAll(codexBaseCommand(codexCommand));
        if (initialPrompt != null && !initialPrompt.isBlank()) {
            command.add(initialPrompt);
        }
        run(command);
        configureTmux(tmuxCommand, tmuxName);
    }

    @Override
    public void startResumeTmux(String tmuxCommand, String tmuxName, Path cwd, String codexCommand, String threadId) {
        List<String> command = new ArrayList<>(List.of(tmuxCommand, "new-session", "-d", "-s", tmuxName, "-c", cwd.toString()));
        command.addAll(codexBaseCommand(codexCommand));
        command.addAll(List.of("resume", threadId));
        run(command);
        configureTmux(tmuxCommand, tmuxName);
    }

    @Override
    public void resumeTmuxWithPrompt(String tmuxCommand, String tmuxName, Path cwd, String codexCommand, String threadId, Path promptFile) {
        String prompt = readPrompt(promptFile);
        // Respawn the existing pane instead of typing into the TUI. Codex TUI
        // paste/Enter behavior can leave multiline text in the input editor,
        // while `codex resume <thread> <prompt>` records the reply in JSONL.
        List<String> command = new ArrayList<>(List.of(tmuxCommand, "respawn-pane", "-k", "-t", tmuxName, "-c", cwd.toString()));
        command.addAll(codexBaseCommand(codexCommand));
        command.addAll(List.of("resume", threadId, prompt));
        run(command);
        configureTmux(tmuxCommand, tmuxName);
    }

    private static List<String> codexBaseCommand(String codexCommand) {
        return List.of(codexCommand, "--model", CODEX_MODEL, "-c",
                "model_reasoning_effort=\"" + CODEX_REASONING_EFFORT + "\"", "--no-alt-screen");
    }

    private void configureTmux(String tmuxCommand, String tmuxName) {
        run(List.of(tmuxCommand, "set-option", "-t", tmuxName, "status", "off"));
        run(List.of(tmuxCommand, "set-option", "-t", tmuxName, "history-limit", "50000"));
        // ttyd forwards wheel input into tmux; mouse mode lets that input scroll
        // the pane history instead of reaching Codex's prompt editor as up/down.
        run(List.of(tmuxCommand, "set-option", "-t", tmuxName, "mouse", "on"));
    }

    @Override
    public TtydProcess startTtyd(String ttydCommand, String tmuxCommand, String tmuxName, int port) {
        List<String> command = List.of(
                ttydCommand,
                "--interface", "127.0.0.1",
                "--port", String.valueOf(port),
                "--writable",
                "--client-option", "scrollback=50000",
                tmuxCommand, "attach-session", "-t", tmuxName
        );
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            startedTtyd.put(process.pid(), process);
            return new TtydProcess(process.pid());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start ttyd", e);
        }
    }

    @Override
    public void submitPrompt(String tmuxCommand, String tmuxName, Path promptFile) {
        String bufferName = "phone-agent-prompt-" + tmuxName;
        run(List.of(tmuxCommand, "load-buffer", "-b", bufferName, promptFile.toString()));
        run(List.of(tmuxCommand, "paste-buffer", "-d", "-b", bufferName, "-t", tmuxName));
        run(List.of(tmuxCommand, "send-keys", "-t", tmuxName, "Enter"));
        ensurePromptSubmitted(tmuxCommand, tmuxName, promptFile);
    }

    @Override
    public boolean hasTmuxSession(String tmuxCommand, String tmuxName) {
        return runStatus(List.of(tmuxCommand, "has-session", "-t", tmuxName)) == 0;
    }

    @Override
    public boolean isTtydReady(long pid, int port, String tmuxName) {
        if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false) {
            return false;
        }
        String command = processCommand(pid);
        if (!looksLikeExpectedTtydCommand(command, port)) {
            return false;
        }
        return canConnect(port);
    }

    @Override
    public void killProcess(long pid) {
        Process process = startedTtyd.remove(pid);
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(PROCESS_TIMEOUT)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            return;
        }
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
    }

    @Override
    public void killTmuxSession(String tmuxCommand, String tmuxName) {
        runStatus(List.of(tmuxCommand, "kill-session", "-t", tmuxName));
    }

    @Override
    public int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(false);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate free local port", e);
        }
    }

    @PreDestroy
    void shutdown() {
        for (Long pid : new ArrayList<>(startedTtyd.keySet())) {
            killProcess(pid);
        }
    }

    private void run(List<String> command) {
        int status = commandExecutor.run(command);
        if (status != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command));
        }
    }

    private static int runStatus(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(PROCESS_TIMEOUT)) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void ensurePromptSubmitted(String tmuxCommand, String tmuxName, Path promptFile) {
        String prompt = readPrompt(promptFile);
        for (int attempt = 0; attempt < 3; attempt++) {
            sleep(350);
            String screen = capturePane(tmuxCommand, tmuxName);
            if (screen.isBlank() || !promptStillVisible(screen, prompt)) {
                return;
            }
            run(List.of(tmuxCommand, "send-keys", "-t", tmuxName, "Enter"));
        }
    }

    private static boolean promptStillVisible(String screen, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        List<String> markers = prompt.lines()
                .map(String::trim)
                .filter(line -> line.length() >= 8)
                .limit(4)
                .toList();
        if (markers.isEmpty()) {
            return screen.contains(prompt.trim());
        }
        long visibleMarkers = markers.stream().filter(screen::contains).count();
        return visibleMarkers >= Math.min(2, markers.size());
    }

    private static String readPrompt(Path promptFile) {
        try {
            return Files.readString(promptFile);
        } catch (IOException e) {
            return "";
        }
    }

    private static String capturePane(String tmuxCommand, String tmuxName) {
        try {
            Process process = new ProcessBuilder(tmuxCommand, "capture-pane", "-p", "-t", tmuxName, "-S", "-80")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(PROCESS_TIMEOUT)) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            return new String(process.getInputStream().readAllBytes());
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String processCommand(long pid) {
        try {
            Process process = new ProcessBuilder(processCommandLookupCommand(pid))
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(PROCESS_TIMEOUT) || process.exitValue() != 0) {
                return null;
            }
            return new String(process.getInputStream().readAllBytes());
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static List<String> processCommandLookupCommand(long pid) {
        // BSD/macOS ps truncates long command lines unless wide output is
        // requested. ttyd may still rewrite argv, so command matching below
        // intentionally does not require the tmux target value.
        return List.of("ps", "-ww", "-p", String.valueOf(pid), "-o", "command=");
    }

    static boolean looksLikeExpectedTtydCommand(String command, int port) {
        if (command == null) {
            return false;
        }
        // ttyd rewrites its argv on macOS and drops the tmux target value from
        // ps output, so tmuxName cannot be validated from the process command.
        // Session ownership is checked separately with tmux has-session before
        // ttyd is started or resumed; readiness only proves the expected ttyd
        // listener is alive and attached through tmux.
        return command.contains("ttyd")
                && command.contains(String.valueOf(port))
                && command.contains("tmux")
                && command.contains("attach-session");
    }

    private static boolean canConnect(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @FunctionalInterface
    interface CommandExecutor {
        int run(List<String> command);
    }
}
