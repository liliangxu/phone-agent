package io.github.liliangxu.phoneagent.audio;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessCommandRunnerTest {
    @Test
    void drainsLargeOutputBeforeWaitingForProcessCompletion() {
        ProcessCommandRunner runner = new ProcessCommandRunner();

        CommandResult result = runner.run(List.of(
                "/bin/sh",
                "-c",
                "head -c 200000 /dev/zero | tr '\\0' x"
        ), Duration.ofSeconds(5));

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().length() >= 200000);
        assertEquals("", result.stderr());
    }
}
