package io.github.liliangxu.phoneagent.audio;

import java.time.Duration;
import java.util.List;

public interface CommandRunner {
    CommandResult run(List<String> command, Duration timeout);
}
