package io.github.liliangxu.phoneagent.ring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import io.github.liliangxu.phoneagent.task.AsteriskAmiClient;
import io.github.liliangxu.phoneagent.task.AsteriskControlException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RingPhoneService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RingPhoneService.class);
    private final RingPhoneAttemptRepository repository;
    private final AsteriskAmiClient amiClient;
    private final Clock clock;
    private final AtomicBoolean inProgress = new AtomicBoolean();
    private final AtomicInteger sequence = new AtomicInteger();

    public RingPhoneService(RingPhoneAttemptRepository repository, AsteriskAmiClient amiClient, Clock clock) {
        this.repository = repository;
        this.amiClient = amiClient;
        this.clock = clock;
    }

    /**
     * Triggers the independent phone ring reminder. Attempt persistence is
     * diagnostic-only and best effort: database logging failures must not stop
     * the Asterisk Originate action or alter BLF bridge/task state.
     */
    public RingPhoneResponse ring() {
        if (!inProgress.compareAndSet(false, true)) {
            throw new RingPhoneException("RING_PHONE_BUSY", "Ring Phone is already in progress", HttpStatus.CONFLICT);
        }
        String attemptId = nextId();
        OffsetDateTime started = now();
        RingPhoneAttemptRecord startedRecord = new RingPhoneAttemptRecord(
                attemptId, RingPhoneStatus.STARTED, started, null, null, null, started, started);
        bestEffortInsert(startedRecord);
        try {
            amiClient.originateRingPhone();
            OffsetDateTime ended = now();
            bestEffortUpdate(new RingPhoneAttemptRecord(
                    attemptId, RingPhoneStatus.COMPLETED, started, ended, null, null, started, ended));
            return new RingPhoneResponse(attemptId, RingPhoneStatus.STARTED);
        } catch (AsteriskControlException e) {
            OffsetDateTime ended = now();
            bestEffortUpdate(new RingPhoneAttemptRecord(
                    attemptId, RingPhoneStatus.FAILED, started, ended, "RING_PHONE_AMI_FAILED", e.getMessage(), started, ended));
            throw new RingPhoneException("RING_PHONE_AMI_FAILED", "Failed to ring phone: " + e.getMessage(), HttpStatus.BAD_GATEWAY);
        } catch (RuntimeException e) {
            OffsetDateTime ended = now();
            bestEffortUpdate(new RingPhoneAttemptRecord(
                    attemptId, RingPhoneStatus.FAILED, started, ended, "RING_PHONE_FAILED", e.getMessage(), started, ended));
            throw new RingPhoneException("RING_PHONE_FAILED", "Failed to ring phone: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            inProgress.set(false);
        }
    }

    private void bestEffortInsert(RingPhoneAttemptRecord record) {
        try {
            repository.insert(record);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to insert ring phone attempt {}", record.attemptId(), e);
        }
    }

    private void bestEffortUpdate(RingPhoneAttemptRecord record) {
        try {
            repository.update(record);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to update ring phone attempt {}", record.attemptId(), e);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private String nextId() {
        return "ring-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(now()) + "-" + "%04d".formatted(sequence.incrementAndGet());
    }
}
