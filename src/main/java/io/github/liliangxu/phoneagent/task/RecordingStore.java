package io.github.liliangxu.phoneagent.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class RecordingStore {
    private final Path recordingsDir;

    public RecordingStore(Path recordingsDir) {
        this.recordingsDir = recordingsDir;
    }

    public Path recordingPath(String taskId) {
        return recordingsDir.resolve(taskId + ".wav");
    }

    public Path displayRecordingPath(String taskId) {
        return Path.of("runtime").resolve("recordings").resolve(taskId + ".wav");
    }

    public boolean hasNonEmptyRecording(String taskId) {
        Path recording = recordingPath(taskId);
        try {
            return Files.exists(recording) && Files.size(recording) > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
