package io.github.liliangxu.phoneagent.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SlotAudioStoreTest {
    @TempDir
    Path runtimeDir;

    @Test
    void rejectsMissingSourceAudioInsteadOfPublishingPlaceholder() {
        SlotAudioStore store = new SlotAudioStore(runtimeDir.resolve("slots"));

        assertThrows(IllegalStateException.class, () -> store.publish(runtimeDir.resolve("missing.wav"), 1));
    }
}
