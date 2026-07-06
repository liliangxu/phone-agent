package io.github.liliangxu.phoneagent.task;

import org.springframework.stereotype.Component;
import io.github.liliangxu.phoneagent.audio.CommandResult;
import io.github.liliangxu.phoneagent.audio.CommandRunner;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class WhisperTranscriber {
    private static final Pattern WHISPER_TIMESTAMP_PREFIX = Pattern.compile(
            "^\\s*\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+-->\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]\\s*");

    private final PhoneAgentProperties properties;
    private final CommandRunner commandRunner;
    private final RecordingStore recordingStore;

    public WhisperTranscriber(PhoneAgentProperties properties, CommandRunner commandRunner, RecordingStore recordingStore) {
        this.properties = properties;
        this.commandRunner = commandRunner;
        this.recordingStore = recordingStore;
    }

    /**
     * Converts the Asterisk recording to 16 kHz mono wav before invoking
     * whisper.cpp. The returned text is normalized to user speech only; blank
     * normalized output is a valid no-reply result handled by TaskService.
     */
    public String transcribe(String taskId) {
        PhoneAgentProperties.Asr asr = properties.getAsr();
        if (asr.getWhisperCommand() == null || asr.getWhisperCommand().isBlank()) {
            throw new IllegalStateException("whisper command is not configured");
        }
        try {
            Path asrDir = properties.getRuntimeDir().resolve("asr-input");
            Files.createDirectories(asrDir);
            Path asrInput = asrDir.resolve(taskId + ".wav");
            CommandResult convert = commandRunner.run(List.of(
                    properties.getFfmpegCommand(),
                    "-y",
                    "-i", recordingStore.recordingPath(taskId).toString(),
                    "-ar", "16000",
                    "-ac", "1",
                    asrInput.toString()
            ), properties.getCommandTimeout());
            if (!convert.success()) {
                throw new IllegalStateException(convert.stderr());
            }
            List<String> command = new ArrayList<>();
            command.add(asr.getWhisperCommand());
            if (asr.getModelPath() != null && !asr.getModelPath().isBlank()) {
                command.add("-m");
                command.add(asr.getModelPath());
            }
            if (asr.getLanguage() != null && !asr.getLanguage().isBlank()) {
                command.add("-l");
                command.add(asr.getLanguage());
            }
            command.add("-f");
            command.add(asrInput.toString());
            CommandResult whisper = commandRunner.run(command, properties.getCommandTimeout());
            if (!whisper.success()) {
                throw new IllegalStateException(whisper.stderr());
            }
            return cleanWhisperOutput(whisper.stdout());
        } catch (Exception e) {
            throw new IllegalStateException("ASR failed", e);
        }
    }

    /**
     * Removes whisper.cpp per-line timestamp prefixes while preserving the
     * spoken text layout. Lines that contain only timestamps collapse to blank.
     */
    private static String cleanWhisperOutput(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder();
        for (String line : output.split("\\R", -1)) {
            if (cleaned.length() > 0) {
                cleaned.append('\n');
            }
            cleaned.append(WHISPER_TIMESTAMP_PREFIX.matcher(line).replaceFirst(""));
        }
        return cleaned.toString().trim();
    }
}
