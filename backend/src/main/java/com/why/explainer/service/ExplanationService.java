package com.why.explainer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.explainer.model.Command;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Turns a structured {@link Command} into a short plain-English
 * explanation by calling an LLM (Groq's OpenAI-compatible API).
 *
 * <p>The structured breakdown always works, with or without the AI
 * layer: if the key is missing or the call fails/times out, we return
 * a failed outcome (explanation null, error flag set) instead of
 * throwing, so callers can still show the deterministic breakdown.
 */
@Service
public class ExplanationService {

  /** Outcome of asking the LLM: text on success, error flag otherwise. */
  public record Outcome(String explanation, boolean explanationError) {
    static Outcome ok(String text) {
      return new Outcome(text, false);
    }

    static Outcome failed() {
      return new Outcome(null, true);
    }
  }

  private final RestTemplate http;
  private final ObjectMapper json = new ObjectMapper();
  private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);
  private final String baseUrl;
  private final String model;
  private final String apiKey;

  // Simple in-memory cache: normalized command -> LLM outcome.
  // Same key as CommandParser's cache, so a repeated command
  // costs zero additional LLM calls.
  private final Map<String, Outcome> cache = new ConcurrentHashMap<>();

  @Autowired
  public ExplanationService(
      RestTemplateBuilder builder,
      @Value("${groq.base-url}") String baseUrl,
      @Value("${groq.model}") String model,
      @Value("${groq.api-key:}") String apiKey,
      @Value("${groq.timeout-ms:5000}") int timeoutMs) {
    this(builder.setConnectTimeout(Duration.ofMillis(timeoutMs))
        .setReadTimeout(Duration.ofMillis(timeoutMs)).build(),
        baseUrl, model, apiKey);
  }

  // Test seam: lets tests inject a mocked RestTemplate (no real network).
  ExplanationService(RestTemplate http, String baseUrl, String model, String apiKey) {
    this.http = http;
    this.baseUrl = baseUrl;
    this.model = model;
    this.apiKey = apiKey;
  }

  /** Explain a command, reusing the cached outcome when already seen. */
  public Outcome explain(String rawCommand, Command command) {
    String key = CommandParser.normalize(rawCommand);
    return cache.computeIfAbsent(key, k -> fetchExplanation(command));
  }

  /**
   * Best-effort LLM guess for commands outside the knowledge base.
   * Same shared cache (one normalized command always takes the same
   * path), same timeouts, same graceful fallback.
   */
  public Outcome explainUnknown(String rawCommand) {
    String key = CommandParser.normalize(rawCommand);
    return cache.computeIfAbsent(key, k -> fetchGuess(rawCommand));
  }

  private Outcome fetchExplanation(Command command) {
    String label = command.tool() + " " + command.subcommand();
    if (apiKey == null || apiKey.isBlank()) {
      // No key configured -> structured-only mode. This is a warning, not an
      // error: nothing was attempted, but the user probably expects AI output.
      log.warn("Skipping Groq explanation for '{}': GROQ_API_KEY is not set.", label);
      return Outcome.failed();
    }
    return callGroq(buildPrompt(command), label);
  }

  private Outcome fetchGuess(String rawCommand) {
    if (apiKey == null || apiKey.isBlank()) {
      log.warn("Skipping Groq guess for unknown command: GROQ_API_KEY is not set.");
      return Outcome.failed();
    }
    return callGroq(buildGuessPrompt(rawCommand), "unknown command");
  }

  /** One Groq chat-completions call with the standard timeout/fallback contract. */
  private Outcome callGroq(String prompt, String label) {
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(apiKey);
      Map<String, Object> body = Map.of(
          "model", model,
          "temperature", 0.3,
          "max_tokens", 220,
          "messages", List.of(
              Map.of("role", "system",
                  "content", "You explain shell commands briefly in plain English. "
                      + "Reply with plain text only, no markdown."),
              Map.of("role", "user", "content", prompt)));
      HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
      String raw = http.postForObject(baseUrl + "/chat/completions", request, String.class);
      String text = extractContent(raw);
      if (text == null || text.isBlank()) {
        log.error("Groq explanation request for '{}' returned an empty explanation.", label);
        return Outcome.failed();
      }
      return Outcome.ok(text.strip());
    } catch (HttpStatusCodeException e) {
      // Groq answered with 4xx/5xx: log status AND body (it names the cause,
      // e.g. invalid key, unknown model, rate limit). Stack trace follows.
      log.error("Groq explanation request for '{}' failed with status {} and body: {}",
          label, e.getStatusCode(), e.getResponseBodyAsString(), e);
      return Outcome.failed();
    } catch (ResourceAccessException e) { // includes connect/read timeouts
      log.error("Groq explanation request for '{}' failed (connection problem or timeout): {}",
          label, e.getMessage(), e);
      return Outcome.failed();
    } catch (RestClientException e) {
      log.error("Groq explanation request for '{}' failed: {}", label, e.getMessage(), e);
      return Outcome.failed();
    }
  }

  /** The prompt sent to the LLM. Package-visible so tests can assert on it. */
  String buildPrompt(Command command) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("Explain what this shell command does in 2-3 plain-English sentences:\n\n");
    prompt.append("Tool: ").append(command.tool()).append("\n");
    prompt.append("Subcommand: ").append(command.subcommand()).append("\n");
    if (!command.flags().isEmpty()) {
      prompt.append("Flags:\n");
      for (var flag : command.flags()) {
        prompt.append("- ").append(flag.flag()).append(": ").append(flag.meaning());
        if ("destructive".equals(flag.risk())) {
          prompt.append(" [DESTRUCTIVE]");
        }
        prompt.append("\n");
      }
    }
    if (!command.args().isEmpty()) {
      prompt.append("Arguments:\n");
      for (var arg : command.args()) {
        prompt.append("- ").append(arg.value()).append(" (").append(arg.role()).append(")\n");
      }
    }
    if (!command.effects().isEmpty()) {
      prompt.append("Effects:\n");
      for (var effect : command.effects()) {
        prompt.append("- [").append(effect.subsystem()).append("] ").append(effect.change());
        if (effect.target() != null) {
          prompt.append(" (target: ").append(effect.target()).append(")");
        }
        prompt.append("\n");
      }
    }
    boolean destructive = command.flags().stream().anyMatch(f -> "destructive".equals(f.risk()));
    if (destructive) {
      prompt.append("This command includes a destructive flag. Add one sentence of caution about it.\n");
    }
    prompt.append("Base your answer ONLY on the facts above.");
    return prompt.toString();
  }

  /**
   * The prompt for commands outside the knowledge base. Unlike
   * {@link #buildPrompt(Command)}, this deliberately includes the raw
   * command string: there is nothing structured to work with, so a
   * best-effort guess needs the literal text. Package-visible for tests.
   */
  String buildGuessPrompt(String rawCommand) {
    return "The following shell command is NOT in our known command database, "
        + "so there is no verified breakdown for it:\n\n"
        + rawCommand + "\n\n"
        + "In 2-3 plain-English sentences, explain what this command likely does. "
        + "Clearly caveat that this is a best-effort guess since the command is "
        + "outside the tool's known command set. "
        + "Reply with plain text only, no markdown.";
  }

  /** Pull choices[0].message.content out of a chat-completions response. */
  private String extractContent(String rawJson) {
    if (rawJson == null) {
      log.error("Groq explanation request returned an empty response body.");
      return null;
    }
    try {
      JsonNode root = json.readTree(rawJson);
      JsonNode content = root.path("choices").path(0).path("message").path("content");
      if (content.isMissingNode() || content.isNull()) {
        log.error("Groq explanation response has no choices[0].message.content. Body was: {}",
            truncate(rawJson));
        return null;
      }
      return content.asText(null);
    } catch (Exception e) {
      log.error("Could not parse Groq explanation response as JSON: {}", e.getMessage(), e);
      return null;
    }
  }

  private static String truncate(String value) {
    return value.length() > 300 ? value.substring(0, 300) + "..." : value;
  }
}
