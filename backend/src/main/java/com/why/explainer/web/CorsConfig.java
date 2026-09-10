package com.why.explainer.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS policy for the whole backend.
 *
 * <p>The browser origin allowed to call the API comes from the
 * {@code ALLOWED_ORIGIN} environment variable (set to the deployed
 * frontend URL on Render), defaulting to the local Vite dev server
 * so plain local development needs no env vars at all.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

  private final String allowedOrigin;

  public CorsConfig(@Value("${ALLOWED_ORIGIN:http://localhost:5173}") String allowedOrigin) {
    this.allowedOrigin = allowedOrigin;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
        .allowedOrigins(allowedOrigin)
        .allowedMethods("GET", "POST", "OPTIONS")
        .allowedHeaders("Content-Type")
        .maxAge(3600);
    registry.addMapping("/health")
        .allowedOrigins(allowedOrigin)
        .allowedMethods("GET", "OPTIONS")
        .maxAge(3600);
  }
}
