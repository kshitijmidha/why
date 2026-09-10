package com.why.explainer.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Tiny in-memory sliding-window rate limiter, keyed by client IP.
 *
 * <p>Each IP may record up to {@code requestsPerMinute} hits per rolling
 * 60-second window. This is best-effort abuse protection for the Groq
 * quota and free-tier resources — state is per-instance and resets on
 * restart, which is fine for a single Render container.
 */
@Service
public class RateLimitService {

  private final int requestsPerMinute;
  private final long windowMs;
  private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
  private Clock clock;

  @Autowired
  public RateLimitService(@Value("${rate-limit.requests-per-minute:10}") int requestsPerMinute) {
    this(requestsPerMinute, Duration.ofMinutes(1), Clock.systemUTC());
  }

  // Visible for testing: lets tests use a short window and a controllable clock.
  RateLimitService(int requestsPerMinute, Duration window, Clock clock) {
    this.requestsPerMinute = requestsPerMinute;
    this.windowMs = window.toMillis();
    this.clock = clock;
  }

  /**
   * Records a hit and returns true if the call is within the limit,
   * false if this IP has already used up its window.
   */
  public boolean tryAcquire(String ip) {
    String key = ip == null || ip.isBlank() ? "unknown" : ip;
    long now = clock.millis();
    Deque<Long> bucket = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (bucket) {
      while (!bucket.isEmpty() && now - bucket.peekFirst() >= windowMs) {
        bucket.pollFirst();
      }
      if (bucket.size() >= requestsPerMinute) {
        return false;
      }
      bucket.addLast(now);
      return true;
    }
  }

  // Visible for testing.
  void setClock(Clock clock) {
    this.clock = clock;
  }
}
