package com.why.explainer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.why.explainer.model.Command;
import com.why.explainer.service.ExplanationService.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Tests for the inferred (unknown-command) LLM path. No real network
 * calls here. Kept in its own file so existing tests stay untouched.
 */
@SpringBootTest
class ExplanationFallbackTest {

  private static final String BASE_URL = "https://groq.test/openai/v1";

  @Autowired
  CommandParser parser;

  private ExplanationService serviceWith(RestTemplate http, String apiKey) {
    return new ExplanationService(http, BASE_URL, "test-model", apiKey);
  }

  private String groqOkBody(String content) {
    return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
        + content + "\"}}]}";
  }

  @Test
  void inferredSuccessPopulatesExplanation() {
    RestTemplate http = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    server.expect(requestTo(BASE_URL + "/chat/completions"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(groqOkBody("kubectl lists Kubernetes resources (guess)."),
            MediaType.APPLICATION_JSON));

    Outcome outcome = serviceWith(http, "key").explainUnknown("kubectl get pods");

    assertFalse(outcome.explanationError());
    assertEquals("kubectl lists Kubernetes resources (guess).", outcome.explanation());
    server.verify();
  }

  @Test
  void inferredTimeoutFallsBack() {
    RestTemplate http = mock(RestTemplate.class);
    when(http.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
        .thenThrow(new ResourceAccessException("Read timed out"));

    Outcome outcome = serviceWith(http, "key").explainUnknown("kubectl get pods");

    assertTrue(outcome.explanationError());
    assertNull(outcome.explanation());
  }

  @Test
  void inferredMissingKeySkipsTheCall() {
    RestTemplate http = new RestTemplate();
    // Zero expectations: any HTTP attempt would fail verification.
    MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    Outcome outcome = serviceWith(http, "").explainUnknown("kubectl get pods");

    assertTrue(outcome.explanationError());
    assertNull(outcome.explanation());
    server.verify();
  }

  @Test
  void inferredRepeatedCommandReusesCache() {
    RestTemplate http = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    // Exactly one expectation: a second HTTP call would fail the test.
    server.expect(requestTo(BASE_URL + "/chat/completions"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(groqOkBody("Guessed once."), MediaType.APPLICATION_JSON));

    ExplanationService service = serviceWith(http, "key");
    Outcome first = service.explainUnknown("kubectl get pods");
    Outcome second = service.explainUnknown("  kubectl   get pods  ");

    assertEquals("Guessed once.", first.explanation());
    assertEquals(first, second);
    server.verify();
  }

  @Test
  void guessPromptContainsRawCommand() {
    // The fallback has nothing structured to work with, so the raw
    // command string is intentionally part of its prompt.
    ExplanationService service = serviceWith(new RestTemplate(), "key");

    assertTrue(service.buildGuessPrompt("kubectl get pods").contains("kubectl get pods"));
  }

  @Test
  void verifiedPromptExcludesRawCommandString() {
    // Verified prompts are built from structured fields only — the raw,
    // unmodified user string must never appear verbatim (prompt-injection
    // surface stays minimal even if args contain tricky text).
    ExplanationService service = serviceWith(new RestTemplate(), "key");
    Command cmd = parser.parse("git reset --soft HEAD~1");

    String prompt = service.buildPrompt(cmd);

    assertFalse(prompt.contains("git reset --soft HEAD~1"),
        "Verified prompt must not embed the raw command string, got:\n" + prompt);
  }
}
