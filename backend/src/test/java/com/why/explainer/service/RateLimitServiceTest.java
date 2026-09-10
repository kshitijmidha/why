package com.why.explainer.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Unit tests for the sliding-window logic (no Spring context needed). */
class RateLimitServiceTest {

  /** A clock the test can move forward by hand. */
  private static final class ManualClock extends Clock {
    private final AtomicLong millis;

    ManualClock(long start) {
      this.millis = new AtomicLong(start);
    }

    void advance(long deltaMs) {
      millis.addAndGet(deltaMs);
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
      return Instant.ofEpochMilli(millis.get());
    }
  }

  @Test
  void allowsUpToLimitThenRejects() {
    RateLimitService limiter = new RateLimitService(3, Duration.ofMinutes(1), Clock.systemUTC());
    assertTrue(limiter.tryAcquire("1.2.3.4"));
    assertTrue(limiter.tryAcquire("1.2.3.4"));
    assertTrue(limiter.tryAcquire("1.2.3.4"));
    assertFalse(limiter.tryAcquire("1.2.3.4"));
  }

  @Test
  void windowSlidesSoOldHitsExpire() {
    ManualClock clock = new ManualClock(1_000_000L);
    RateLimitService limiter = new RateLimitService(2, Duration.ofMillis(500), clock);
    assertTrue(limiter.tryAcquire("1.2.3.4"));
    assertTrue(limiter.tryAcquire("1.2.3.4"));
    assertFalse(limiter.tryAcquire("1.2.3.4"));
    clock.advance(500);
    // Window slid past both old hits, so the budget is fully refilled.
    assertTrue(limiter.tryAcquire("1.2.3.4"));
    assertTrue(limiter.tryAcquire("1.2.3.4"));
    assertFalse(limiter.tryAcquire("1.2.3.4"));
  }

  @Test
  void bucketsArePerIp() {
    RateLimitService limiter = new RateLimitService(1, Duration.ofMinutes(1), Clock.systemUTC());
    assertTrue(limiter.tryAcquire("1.2.3.4"));
    assertFalse(limiter.tryAcquire("1.2.3.4"));
    assertTrue(limiter.tryAcquire("5.6.7.8"));
  }
}
