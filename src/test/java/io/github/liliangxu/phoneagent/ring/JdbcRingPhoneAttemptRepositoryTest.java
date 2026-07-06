package io.github.liliangxu.phoneagent.ring;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcRingPhoneAttemptRepositoryTest {
    @Test
    void insertAndUpdateAttempt() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:ring_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcRingPhoneAttemptRepository repository = new JdbcRingPhoneAttemptRepository(jdbc);
        OffsetDateTime now = OffsetDateTime.parse("2026-06-17T12:00:00+08:00");

        repository.insert(new RingPhoneAttemptRecord("ring-1", RingPhoneStatus.STARTED, now, null,
                null, null, now, now));
        repository.update(new RingPhoneAttemptRecord("ring-1", RingPhoneStatus.COMPLETED, now, now.plusSeconds(1),
                null, null, now, now.plusSeconds(1)));

        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM ring_phone_attempts WHERE attempt_id = ?",
                String.class,
                "ring-1"));
    }
}
