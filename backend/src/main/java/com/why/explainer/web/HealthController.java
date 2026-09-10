package com.why.explainer.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness probe for the hosting platform (Render health checks hit this).
 * Always returns 200 OK with a tiny JSON body; never touches the LLM.
 */
@RestController
public class HealthController {

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "ok");
  }
}
