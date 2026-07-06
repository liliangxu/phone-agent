package io.github.liliangxu.phoneagent.task;

import java.nio.file.Path;

public interface SpeechSynthesisService {
    Path synthesize(String taskId, String text);
}
