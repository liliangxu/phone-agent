package io.github.liliangxu.phoneagent.ring;

import org.junit.jupiter.api.Test;
import io.github.liliangxu.phoneagent.task.AsteriskAmiClient;
import io.github.liliangxu.phoneagent.task.AsteriskControlException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingPhoneServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-17T04:00:00Z"), ZoneOffset.UTC);

    @Test
    void successfulRingCallsAmiAndRecordsAttemptBestEffort() {
        RecordingRepository repository = new RecordingRepository();
        RecordingAmi ami = new RecordingAmi();
        RingPhoneService service = new RingPhoneService(repository, ami, CLOCK);

        RingPhoneResponse response = service.ring();

        assertEquals("ring-20260617-040000-0001", response.attemptId());
        assertEquals(RingPhoneStatus.STARTED, response.status());
        assertEquals(1, ami.rings);
        assertEquals(RingPhoneStatus.STARTED, repository.inserted.getFirst().status());
        assertEquals(RingPhoneStatus.COMPLETED, repository.updated.getFirst().status());
    }

    @Test
    void repositoryFailureDoesNotBlockAmi() {
        RecordingRepository repository = new RecordingRepository();
        repository.failInsert = true;
        repository.failUpdate = true;
        RecordingAmi ami = new RecordingAmi();
        RingPhoneService service = new RingPhoneService(repository, ami, CLOCK);

        RingPhoneResponse response = service.ring();

        assertEquals(RingPhoneStatus.STARTED, response.status());
        assertEquals(1, ami.rings);
    }

    @Test
    void busyRequestReturnsConflictAndDoesNotCallAmiTwice() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        RecordingAmi ami = new RecordingAmi();
        ami.block = true;
        RingPhoneService service = new RingPhoneService(repository, ami, CLOCK);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RingPhoneResponse> first = executor.submit(service::ring);
            assertTrue(ami.entered.await(5, TimeUnit.SECONDS));

            RingPhoneException busy = assertThrows(RingPhoneException.class, service::ring);
            assertEquals("RING_PHONE_BUSY", busy.error());

            ami.release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(1, ami.rings);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void amiFailureUpdatesAttemptAndReleasesLock() {
        RecordingRepository repository = new RecordingRepository();
        RecordingAmi ami = new RecordingAmi();
        ami.fail = true;
        RingPhoneService service = new RingPhoneService(repository, ami, CLOCK);

        RingPhoneException failure = assertThrows(RingPhoneException.class, service::ring);
        assertEquals("RING_PHONE_AMI_FAILED", failure.error());
        assertEquals(RingPhoneStatus.FAILED, repository.updated.getFirst().status());

        ami.fail = false;
        assertEquals(RingPhoneStatus.STARTED, service.ring().status());
        assertEquals(2, ami.rings);
    }

    @Test
    void unexpectedFailureMapsToGenericRingPhoneErrorAndReleasesLock() {
        RecordingRepository repository = new RecordingRepository();
        RecordingAmi ami = new RecordingAmi();
        ami.unexpectedFailure = true;
        RingPhoneService service = new RingPhoneService(repository, ami, CLOCK);

        RingPhoneException failure = assertThrows(RingPhoneException.class, service::ring);
        assertEquals("RING_PHONE_FAILED", failure.error());
        assertEquals(RingPhoneStatus.FAILED, repository.updated.getFirst().status());

        ami.unexpectedFailure = false;
        assertEquals(RingPhoneStatus.STARTED, service.ring().status());
        assertEquals(2, ami.rings);
    }

    private static final class RecordingRepository implements RingPhoneAttemptRepository {
        final List<RingPhoneAttemptRecord> inserted = new ArrayList<>();
        final List<RingPhoneAttemptRecord> updated = new ArrayList<>();
        boolean failInsert;
        boolean failUpdate;

        @Override
        public void insert(RingPhoneAttemptRecord record) {
            if (failInsert) {
                throw new IllegalStateException("insert failed");
            }
            inserted.add(record);
        }

        @Override
        public void update(RingPhoneAttemptRecord record) {
            if (failUpdate) {
                throw new IllegalStateException("update failed");
            }
            updated.add(record);
        }
    }

    private static final class RecordingAmi implements AsteriskAmiClient {
        int rings;
        boolean fail;
        boolean unexpectedFailure;
        boolean block;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        @Override public void setInUse(int slot) {}

        @Override public void setNotInUse(int slot) {}

        @Override
        public void originateRingPhone() {
            rings++;
            if (block) {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            if (fail) {
                throw new AsteriskControlException("originate failed");
            }
            if (unexpectedFailure) {
                throw new IllegalStateException("unexpected originate failure");
            }
        }
    }
}
