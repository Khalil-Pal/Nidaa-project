package com.humanitarian.platform.evaluation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the simulation moves by hand, so {@code PriorityScoreService} ages a
 * request by simulated hours waited, not by wall-clock time since the dataset
 * was generated. Fixed to UTC: the datasets carry {@code LocalDateTime}s and
 * the offset must not depend on the machine running the study.
 */
public final class SimulationClock extends Clock {

    private Instant now;

    public SimulationClock(LocalDateTime start) {
        this.now = start.toInstant(ZoneOffset.UTC);
    }

    public void set(LocalDateTime time) {
        this.now = time.toInstant(ZoneOffset.UTC);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
