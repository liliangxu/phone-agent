package io.github.liliangxu.phoneagent.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class SlotAudioStore {
    private final Path slotsDir;

    public SlotAudioStore(Path slotsDir) {
        this.slotsDir = slotsDir;
    }

    /**
     * Publishes slot audio by writing a temporary file and then replacing the
     * visible slot file. Missing source audio is treated as a real failure,
     * because publishing a placeholder would only move the failure to Asterisk.
     */
    public void publish(Path sourceAudio, int slot) {
        try {
            Files.createDirectories(slotsDir);
            Path target = slotsDir.resolve("slot-" + slot + ".wav");
            Path tmp = slotsDir.resolve("slot-" + slot + ".wav.tmp");
            if (!Files.exists(sourceAudio)) {
                throw new IOException("Source audio does not exist: " + sourceAudio);
            }
            Files.copy(sourceAudio, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to publish slot audio for slot " + slot, e);
        }
    }
}
