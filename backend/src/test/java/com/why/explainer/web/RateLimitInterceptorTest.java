package com.why.explainer.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/** Client-IP extraction: proxy header first, direct address as fallback. */
class RateLimitInterceptorTest {

  private HttpServletRequest request(String forwardedFor, String remoteAddr) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
    when(req.getRemoteAddr()).thenReturn(remoteAddr);
    return req;
  }

  @Test
  void usesFirstForwardedAddress() {
    assertEquals("203.0.113.5",
        RateLimitInterceptor.resolveClientIp(request("203.0.113.5, 70.41.3.18", "10.0.0.1")));
  }

  @Test
  void trimsForwardedAddress() {
    assertEquals("203.0.113.5",
        RateLimitInterceptor.resolveClientIp(request("  203.0.113.5  ", "10.0.0.1")));
  }

  @Test
  void fallsBackToRemoteAddr() {
    assertEquals("10.0.0.1", RateLimitInterceptor.resolveClientIp(request(null, "10.0.0.1")));
    assertEquals("10.0.0.1", RateLimitInterceptor.resolveClientIp(request("   ", "10.0.0.1")));
  }
}
