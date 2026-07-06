package io.github.liliangxu.phoneagent.ring;

public interface RingPhoneAttemptRepository {
    void insert(RingPhoneAttemptRecord record);

    void update(RingPhoneAttemptRecord record);
}
