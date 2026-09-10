package com.why.explainer.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bare-domain landing: hitting the root URL returns a small JSON
 * pointer instead of a 404, so the bare Render URL answers sensibly.
 */
@RestController
public class RootController {

  @GetMapping("/")
  public Map<String, String> root() {
    return Map.of(
        "status", "ok",
        "service", "why-backend",
        "docs", "see /api/explain");
  }
}
