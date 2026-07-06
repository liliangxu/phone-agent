package io.github.liliangxu.phoneagent.task;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import io.github.liliangxu.phoneagent.audio.CommandResult;
import io.github.liliangxu.phoneagent.audio.CommandRunner;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class PromptAudioInitializer implements ApplicationRunner {
    private final PhoneAgentProperties properties;
    private final CommandRunner commandRunner;

    public PromptAudioInitializer(PhoneAgentProperties properties, CommandRunner commandRunner) {
        this.properties = properties;
        this.commandRunner = commandRunner;
    }

    /**
     * Generates the fixed Asterisk prompt files needed by the dialplan. These
     * files live under runtime/sounds/prompts and are mounted into the Asterisk
     * container as phone-agent/prompts/*.wav.
     */
    @Override
    public void run(ApplicationArguments args) {
        generate("reply-after-beep", "请在滴声后回复，结束请挂机。");
        generate("inbound-intent", "请在滴声后留言，结束请挂机。");
        generate("ring-phone", "请按红色按键处理");
        generate("no-task", "暂无任务。");
    }

    private void generate(String name, String text) {
        try {
            Path promptDir = properties.getRuntimeDir().resolve("sounds").resolve("prompts");
            Files.createDirectories(promptDir);
            Path raw = promptDir.resolve(name + "-raw.aiff");
            Path wav = promptDir.resolve(name + ".wav");
            CommandResult say = commandRunner.run(List.of(properties.getSayCommand(), "-o", raw.toString(), text),
                    properties.getCommandTimeout());
            if (!say.success()) {
                throw new IllegalStateException(say.stderr());
            }
            CommandResult ffmpeg = commandRunner.run(List.of(
                    properties.getFfmpegCommand(),
                    "-y",
                    "-i", raw.toString(),
                    "-ar", "8000",
                    "-ac", "1",
                    wav.toString()
            ), properties.getCommandTimeout());
            if (!ffmpeg.success()) {
                throw new IllegalStateException(ffmpeg.stderr());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate prompt audio " + name, e);
        }
    }
}
