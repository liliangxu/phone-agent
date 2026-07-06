package io.github.liliangxu.phoneagent.codex;

import java.nio.file.Path;

public interface CodexProcessGateway {
    boolean commandAvailable(String command);

    void startTmux(String tmuxCommand, String tmuxName, Path cwd, String codexCommand, String initialPrompt);

    void startResumeTmux(String tmuxCommand, String tmuxName, Path cwd, String codexCommand, String threadId);

    void resumeTmuxWithPrompt(String tmuxCommand, String tmuxName, Path cwd, String codexCommand, String threadId, Path promptFile);

    TtydProcess startTtyd(String ttydCommand, String tmuxCommand, String tmuxName, int port);

    void submitPrompt(String tmuxCommand, String tmuxName, Path promptFile);

    boolean hasTmuxSession(String tmuxCommand, String tmuxName);

    boolean isTtydReady(long pid, int port, String tmuxName);

    void killProcess(long pid);

    void killTmuxSession(String tmuxCommand, String tmuxName);

    int freePort();

    record TtydProcess(long pid) {
    }
}
