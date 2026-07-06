package io.github.liliangxu.phoneagent.task;

import org.springframework.stereotype.Component;
import io.github.liliangxu.phoneagent.audio.CommandResult;
import io.github.liliangxu.phoneagent.audio.CommandRunner;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class LocalSpeechSynthesisService implements SpeechSynthesisService {
    private final PhoneAgentProperties properties;
    private final CommandRunner commandRunner;

    public LocalSpeechSynthesisService(PhoneAgentProperties properties, CommandRunner commandRunner) {
        this.properties = properties;
        this.commandRunner = commandRunner;
    }

    /**
     * Generates task audio with macOS say and converts it to Asterisk-friendly
     * 8 kHz mono wav. Both commands are local process side effects and failures
     * are surfaced as task creation failures by TaskService.
     */
    @Override
    public Path synthesize(String taskId, String text) {
        try {
            Path generatedDir = properties.getRuntimeDir().resolve("generated");
            Files.createDirectories(generatedDir);
            Path raw = generatedDir.resolve(taskId + "-raw.aiff");
            Path converted = generatedDir.resolve(taskId + ".wav");
            CommandResult say = commandRunner.run(List.of(
                    properties.getSayCommand(),
                    "-o", raw.toString(),
                    text
            ), properties.getCommandTimeout());
            if (!say.success()) {
                throw new AudioPreparationException(FailureStage.TTS, say.stderr());
            }
            CommandResult ffmpeg = commandRunner.run(List.of(
                    properties.getFfmpegCommand(),
                    "-y",
                    "-i", raw.toString(),
                    "-ar", "8000",
                    "-ac", "1",
                    converted.toString()
            ), properties.getCommandTimeout());
            if (!ffmpeg.success()) {
                throw new AudioPreparationException(FailureStage.AUDIO_CONVERT, ffmpeg.stderr());
            }
            return converted;
        } catch (Exception e) {
            if (e instanceof AudioPreparationException audioPreparationException) {
                throw audioPreparationException;
            }
            throw new IllegalStateException("speech synthesis failed", e);
        }
    }
}
