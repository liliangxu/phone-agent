package io.github.liliangxu.phoneagent.audio;

public record CommandResult(int exitCode, String stdout, String stderr) {
    public boolean success() {
        return exitCode == 0;
    }
}
