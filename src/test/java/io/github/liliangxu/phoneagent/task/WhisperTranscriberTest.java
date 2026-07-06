package io.github.liliangxu.phoneagent.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.liliangxu.phoneagent.audio.CommandResult;
import io.github.liliangxu.phoneagent.audio.CommandRunner;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhisperTranscriberTest {
    @TempDir
    Path runtimeDir;

    @Test
    void convertsRecordingThenRunsWhisperWithModelAndReturnsNonEmptyOutput() throws Exception {
        Files.createDirectories(runtimeDir.resolve("recordings"));
        Files.writeString(runtimeDir.resolve("recordings/task-1.wav"), "voice");
        RecordingCommandRunner runner = new RecordingCommandRunner();
        runner.whisperOutput = """
                [00:00:00.000 --> 00:00:02.000] 用户回复
                [00:00:02.000 --> 00:00:04.000] 第二句
                """;
        PhoneAgentProperties properties = properties("/opt/whisper/main", "/models/ggml.bin");
        WhisperTranscriber transcriber = new WhisperTranscriber(
                properties,
                runner,
                new RecordingStore(runtimeDir.resolve("recordings"))
        );

        String text = transcriber.transcribe("task-1");

        assertEquals("用户回复\n第二句", text);
        assertEquals(List.of(
                List.of("ffmpeg", "-y", "-i", runtimeDir.resolve("recordings/task-1.wav").toString(),
                        "-ar", "16000", "-ac", "1", runtimeDir.resolve("asr-input/task-1.wav").toString()),
                List.of("/opt/whisper/main", "-m", "/models/ggml.bin", "-f",
                        runtimeDir.resolve("asr-input/task-1.wav").toString())
        ), runner.commands);
    }

    @Test
    void failsWhenFfmpegFails() {
        RecordingCommandRunner runner = new RecordingCommandRunner();
        runner.failFfmpeg = true;

        WhisperTranscriber transcriber = new WhisperTranscriber(
                properties("/opt/whisper/main", ""),
                runner,
                new RecordingStore(runtimeDir.resolve("recordings"))
        );

        assertThrows(IllegalStateException.class, () -> transcriber.transcribe("task-1"));
    }

    @Test
    void failsWhenWhisperFails() {
        RecordingCommandRunner failingWhisper = new RecordingCommandRunner();
        failingWhisper.failWhisper = true;
        WhisperTranscriber transcriber = new WhisperTranscriber(
                properties("/opt/whisper/main", ""),
                failingWhisper,
                new RecordingStore(runtimeDir.resolve("recordings"))
        );
        assertThrows(IllegalStateException.class, () -> transcriber.transcribe("task-1"));
    }

    @Test
    void timestampOnlyWhisperOutputCleansToBlankForNoReplyMapping() {
        RecordingCommandRunner blankWhisper = new RecordingCommandRunner();
        blankWhisper.whisperOutput = """
                [00:00:00.000 --> 00:00:02.000]
                [00:00:02.000 --> 00:00:04.000]
                """;
        WhisperTranscriber blankTranscriber = new WhisperTranscriber(
                properties("/opt/whisper/main", ""),
                blankWhisper,
                new RecordingStore(runtimeDir.resolve("recordings"))
        );
        assertEquals("", blankTranscriber.transcribe("task-1"));
    }

    @Test
    void timestampedWhisperContentRemovesTimestampEnvelope() {
        RecordingCommandRunner runner = new RecordingCommandRunner();
        runner.whisperOutput = "[00:00:00.000 --> 00:00:02.000] 用户回复\n";
        WhisperTranscriber transcriber = new WhisperTranscriber(
                properties("/opt/whisper/main", ""),
                runner,
                new RecordingStore(runtimeDir.resolve("recordings"))
        );

        assertEquals("用户回复", transcriber.transcribe("task-1"));
    }

    private PhoneAgentProperties properties(String whisperCommand, String modelPath) {
        PhoneAgentProperties properties = new PhoneAgentProperties();
        properties.setRuntimeDir(runtimeDir);
        properties.getAsr().setWhisperCommand(whisperCommand);
        properties.getAsr().setModelPath(modelPath);
        properties.getAsr().setLanguage("");
        return properties;
    }

    private static final class RecordingCommandRunner implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        boolean failFfmpeg;
        boolean failWhisper;
        String whisperOutput = "用户回复\n";

        @Override
        public CommandResult run(List<String> command, Duration timeout) {
            commands.add(command);
            if (command.get(0).contains("ffmpeg")) {
                return failFfmpeg ? new CommandResult(1, "", "ffmpeg failed") : new CommandResult(0, "", "");
            }
            if (failWhisper) {
                return new CommandResult(1, "", "whisper failed");
            }
            assertTrue(command.contains("-f"));
            return new CommandResult(0, whisperOutput, "");
        }
    }
}
