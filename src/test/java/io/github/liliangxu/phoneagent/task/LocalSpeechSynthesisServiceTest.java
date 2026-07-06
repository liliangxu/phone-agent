package io.github.liliangxu.phoneagent.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.liliangxu.phoneagent.audio.CommandResult;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalSpeechSynthesisServiceTest {
    @TempDir
    Path runtimeDir;

    @Test
    void mapsFfmpegFailureToAudioConvertStage() {
        PhoneAgentProperties properties = new PhoneAgentProperties();
        properties.setRuntimeDir(runtimeDir);
        LocalSpeechSynthesisService service = new LocalSpeechSynthesisService(properties, (command, timeout) -> {
            if (command.contains("-ar")) {
                return new CommandResult(1, "", "ffmpeg failed");
            }
            return new CommandResult(0, "", "");
        });

        AudioPreparationException exception = assertThrows(
                AudioPreparationException.class,
                () -> service.synthesize("task-1", "hello")
        );

        assertEquals(FailureStage.AUDIO_CONVERT, exception.stage());
    }

    @Test
    void mapsSayFailureToTtsStage() {
        PhoneAgentProperties properties = new PhoneAgentProperties();
        properties.setRuntimeDir(runtimeDir);
        LocalSpeechSynthesisService service = new LocalSpeechSynthesisService(
                properties,
                (command, timeout) -> new CommandResult(1, "", "say failed")
        );

        AudioPreparationException exception = assertThrows(
                AudioPreparationException.class,
                () -> service.synthesize("task-1", "hello")
        );

        assertEquals(FailureStage.TTS, exception.stage());
    }

    @Test
    void writesSayOutputAsAiffBeforeFfmpegConvertsToWav() {
        PhoneAgentProperties properties = new PhoneAgentProperties();
        properties.setRuntimeDir(runtimeDir);
        List<List<String>> commands = new ArrayList<>();
        LocalSpeechSynthesisService service = new LocalSpeechSynthesisService(properties, (command, timeout) -> {
            commands.add(command);
            return new CommandResult(0, "", "");
        });

        Path output = service.synthesize("task-1", "hello");

        assertEquals(runtimeDir.resolve("generated/task-1.wav"), output);
        assertEquals(runtimeDir.resolve("generated/task-1-raw.aiff").toString(), commands.get(0).get(2));
        assertEquals(runtimeDir.resolve("generated/task-1-raw.aiff").toString(), commands.get(1).get(3));
    }
}
