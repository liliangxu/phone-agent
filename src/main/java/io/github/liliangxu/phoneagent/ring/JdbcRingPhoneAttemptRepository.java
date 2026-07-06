package io.github.liliangxu.phoneagent.ring;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@DependsOn("phoneAgentDatabaseInitializer")
public class JdbcRingPhoneAttemptRepository implements RingPhoneAttemptRepository {
    private final JdbcTemplate jdbc;

    public JdbcRingPhoneAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(RingPhoneAttemptRecord record) {
        jdbc.update("""
                        INSERT INTO ring_phone_attempts (
                            attempt_id, status, started_at, ended_at, error_code,
                            error_message, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                record.attemptId(),
                record.status().name(),
                record.startedAt(),
                record.endedAt(),
                record.errorCode(),
                record.errorMessage(),
                record.createdAt(),
                record.updatedAt());
    }

    @Override
    public void update(RingPhoneAttemptRecord record) {
        jdbc.update("""
                        UPDATE ring_phone_attempts
                        SET status = ?, ended_at = ?, error_code = ?, error_message = ?, updated_at = ?
                        WHERE attempt_id = ?
                        """,
                record.status().name(),
                record.endedAt(),
                record.errorCode(),
                record.errorMessage(),
                record.updatedAt(),
                record.attemptId());
    }
}
