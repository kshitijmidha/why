package com.why.explainer.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies the rate-limit interceptor to the explain endpoint only —
 * health checks, the root pointer, and error responses stay unlimited.
 */
@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

  private final RateLimitInterceptor rateLimitInterceptor;

  public RateLimitConfig(RateLimitInterceptor rateLimitInterceptor) {
    this.rateLimitInterceptor = rateLimitInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/explain");
  }
}
