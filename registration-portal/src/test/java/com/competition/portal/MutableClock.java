package com.competition.portal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move forward; its views in other zones (withZone) move with it. */
public class MutableClock extends Clock {
    private final Instant[] now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(new Instant[] {start}, ZoneOffset.UTC);
    }

    private MutableClock(Instant[] now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration d) {
        now[0] = now[0].plus(d);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now[0];
    }
}
