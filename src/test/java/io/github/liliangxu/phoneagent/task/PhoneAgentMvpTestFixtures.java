package io.github.liliangxu.phoneagent.task;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

/**
 * Test fixture for the Phone Agent MVP service contract. The implementation may
 * adjust this factory to match constructor details, while keeping the tests'
 * behavioral assertions intact.
 */
final class PhoneAgentMvpTestFixtures {
    static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-09T04:00:00Z"), ZoneOffset.UTC);

    private PhoneAgentMvpTestFixtures() {
    }

    static TaskService newTaskService(Path runtimeDir) {
        return newTaskService(runtimeDir, new RecordingAsteriskAmiClient(), new RecordingAsrJobQueue());
    }

    static TaskService newTaskService(Path runtimeDir, PhoneAgentProperties properties) {
        return newTaskService(
                runtimeDir,
                new SlotAudioStore(runtimeDir.resolve("sounds").resolve("slots")),
                new RecordingAsteriskAmiClient(),
                new RecordingAsrJobQueue(),
                properties
        );
    }

    static TaskService newTaskService(Path runtimeDir, RecordingAsteriskAmiClient amiClient, RecordingAsrJobQueue asrJobQueue) {
        return newTaskService(
                runtimeDir,
                new SlotAudioStore(runtimeDir.resolve("sounds").resolve("slots")),
                amiClient,
                asrJobQueue,
                new PhoneAgentProperties()
        );
    }

    static TaskService newTaskService(
            Path runtimeDir,
            SlotAudioStore slotAudioStore,
            RecordingAsteriskAmiClient amiClient,
            RecordingAsrJobQueue asrJobQueue
    ) {
        return newTaskService(runtimeDir, slotAudioStore, amiClient, asrJobQueue, new PhoneAgentProperties());
    }

    static TaskService newTaskService(
            Path runtimeDir,
            SlotAudioStore slotAudioStore,
            RecordingAsteriskAmiClient amiClient,
            RecordingAsrJobQueue asrJobQueue,
            PhoneAgentProperties properties
    ) {
        return new TaskService(
                new SequentialTaskIdGenerator("task-20260609-"),
                new StubSpeechSynthesisService(runtimeDir.resolve("generated")),
                slotAudioStore,
                new RecordingStore(runtimeDir.resolve("recordings")),
                amiClient,
                asrJobQueue,
                null,
                null,
                properties,
                FIXED_CLOCK
        );
    }

    static final class SequentialTaskIdGenerator implements TaskIdGenerator {
        private final String prefix;
        private final AtomicInteger next = new AtomicInteger(1);

        SequentialTaskIdGenerator(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String nextTaskId() {
            return prefix + "%06d".formatted(next.getAndIncrement());
        }
    }

    static final class StubSpeechSynthesisService implements SpeechSynthesisService {
        private final Path generatedDir;

        StubSpeechSynthesisService(Path generatedDir) {
            this.generatedDir = generatedDir;
        }

        @Override
        public Path synthesize(String taskId, String text) {
            try {
                Files.createDirectories(generatedDir);
                Path audio = generatedDir.resolve(taskId + ".wav");
                Files.writeString(audio, text);
                return audio;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static final class RecordingAsteriskAmiClient implements AsteriskAmiClient {
        final List<String> commands = Collections.synchronizedList(new ArrayList<>());
        boolean failNextInUse;
        private int inUseCommandCount;
        private int blockedInUseCommandNumber = -1;
        private CountDownLatch blockedInUseEntered;
        private CountDownLatch unblockInUse;

        @Override
        public void setInUse(int slot) {
            int commandNumber;
            synchronized (this) {
                commands.add("INUSE:" + slot);
                commandNumber = ++inUseCommandCount;
            }
            if (failNextInUse) {
                failNextInUse = false;
                throw new AsteriskControlException("AMI command failed");
            }
            awaitIfBlocked(commandNumber);
        }

        @Override
        public void setNotInUse(int slot) {
            commands.add("NOT_INUSE:" + slot);
        }

        @Override
        public void originateRingPhone() {
            commands.add("RING_PHONE");
        }

        void blockInUseCommand(int commandNumber) {
            blockedInUseCommandNumber = commandNumber;
            blockedInUseEntered = new CountDownLatch(1);
            unblockInUse = new CountDownLatch(1);
        }

        void awaitBlockedInUse() throws InterruptedException {
            if (!blockedInUseEntered.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for blocked INUSE command");
            }
        }

        void unblockInUse() {
            unblockInUse.countDown();
        }

        long count(String command) {
            return commands.stream().filter(command::equals).count();
        }

        private void awaitIfBlocked(int commandNumber) {
            if (commandNumber != blockedInUseCommandNumber) {
                return;
            }
            blockedInUseEntered.countDown();
            try {
                if (!unblockInUse.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out unblocking INUSE command");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while blocking INUSE command", e);
            }
        }
    }

    static final class BlockingSlotAudioStore extends SlotAudioStore {
        private CountDownLatch blockedPublishEntered;
        private CountDownLatch unblockPublish;
        private volatile boolean blockNextPublish;

        BlockingSlotAudioStore(Path slotsDir) {
            super(slotsDir);
        }

        void blockNextPublish() {
            blockNextPublish = true;
            blockedPublishEntered = new CountDownLatch(1);
            unblockPublish = new CountDownLatch(1);
        }

        void awaitBlockedPublish() throws InterruptedException {
            if (!blockedPublishEntered.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for blocked slot audio publish");
            }
        }

        void unblockPublish() {
            unblockPublish.countDown();
        }

        @Override
        public void publish(Path sourceAudio, int slot) {
            if (blockNextPublish) {
                blockNextPublish = false;
                blockedPublishEntered.countDown();
                try {
                    if (!unblockPublish.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out unblocking slot audio publish");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while blocking slot audio publish", e);
                }
            }
            super.publish(sourceAudio, slot);
        }
    }

    static final class RecordingAsrJobQueue implements AsrJobQueue {
        final List<String> submittedTaskIds = new ArrayList<>();

        @Override
        public void submit(String taskId) {
            submittedTaskIds.add(taskId);
        }
    }
}
