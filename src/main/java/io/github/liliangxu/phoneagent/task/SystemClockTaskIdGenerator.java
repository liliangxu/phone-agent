package io.github.liliangxu.phoneagent.task;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SystemClockTaskIdGenerator implements TaskIdGenerator {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final Clock clock;
    private final AtomicInteger sequence = new AtomicInteger(1);

    public SystemClockTaskIdGenerator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String nextTaskId() {
        return "task-" + DATE.format(clock.instant().atZone(clock.getZone())) + "-"
                + "%06d".formatted(sequence.getAndIncrement());
    }
}
