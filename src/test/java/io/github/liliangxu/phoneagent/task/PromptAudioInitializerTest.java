package io.github.liliangxu.phoneagent.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.liliangxu.phoneagent.audio.CommandResult;
import io.github.liliangxu.phoneagent.audio.CommandRunner;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptAudioInitializerTest {
    @TempDir
    Path runtimeDir;

    @Test
    void replyPromptUsesShortInstruction() {
        RecordingCommandRunner runner = new RecordingCommandRunner();
        PhoneAgentProperties properties = new PhoneAgentProperties();
        properties.setRuntimeDir(runtimeDir);

        new PromptAudioInitializer(properties, runner).run(null);

        List<List<String>> sayCommands = runner.commands.stream()
                .filter(command -> command.getFirst().endsWith("say"))
                .toList();
        assertEquals("请在滴声后回复，结束请挂机。", sayCommands.getFirst().get(3));
        assertEquals("请在滴声后留言，结束请挂机。", sayCommands.get(1).get(3));
        assertEquals("请按红色按键处理", sayCommands.get(2).get(3));
        assertEquals("暂无任务。", sayCommands.get(3).get(3));
    }

    private static final class RecordingCommandRunner implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();

        @Override
        public CommandResult run(List<String> command, Duration timeout) {
            commands.add(command);
            return new CommandResult(0, "", "");
        }
    }
}
