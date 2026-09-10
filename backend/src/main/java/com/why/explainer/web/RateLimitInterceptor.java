package com.why.explainer.web;

import com.why.explainer.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces the per-IP rate limit on the explain endpoint. Over-limit
 * requests are rejected here with 429 JSON before any parsing or LLM
 * call happens.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

  static final String BODY = "{\"error\":\"rate_limited\","
      + "\"message\":\"too many requests — please wait a moment before trying again.\"}";

  private final RateLimitService rateLimit;

  public RateLimitInterceptor(RateLimitService rateLimit) {
    this.rateLimit = rateLimit;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {
    if (rateLimit.tryAcquire(resolveClientIp(request))) {
      return true;
    }
    byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
    response.setStatus(429);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Retry-After", "60");
    response.getOutputStream().write(bytes);
    return false;
  }

  /**
   * Render sits behind a proxy, so the real client IP is the first
   * entry of X-Forwarded-For; otherwise fall back to the direct peer.
   */
  static String resolveClientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      String first = forwarded.split(",", 2)[0].trim();
      if (!first.isEmpty()) {
        return first;
      }
    }
    return request.getRemoteAddr();
  }
}
