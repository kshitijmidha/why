package com.why.explainer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
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
 * Tests for the AI explanation layer. No real network calls here:
 * Groq is replaced by a mocked HTTP server (or a Mockito stub).
 */
@SpringBootTest
class ExplanationServiceTest {

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
  void successfulExplanation() {
    RestTemplate http = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    server.expect(requestTo(BASE_URL + "/chat/completions"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(
            groqOkBody("This moves the branch pointer back one commit."),
            MediaType.APPLICATION_JSON));

    Command cmd = parser.parse("git reset --soft HEAD~1");
    Outcome outcome = serviceWith(http, "key").explain("git reset --soft HEAD~1", cmd);

    assertFalse(outcome.explanationError());
    assertEquals("This moves the branch pointer back one commit.", outcome.explanation());
    server.verify();
  }

  @Test
  void serverErrorFallsBackToStructuredOnly() {
    RestTemplate http = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    server.expect(requestTo(BASE_URL + "/chat/completions"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withServerError());

    Command cmd = parser.parse("git push --force origin main");
    Outcome outcome = serviceWith(http, "key").explain("git push --force origin main", cmd);

    assertTrue(outcome.explanationError());
    assertNull(outcome.explanation());
    server.verify();
  }

  @Test
  void timeoutFallsBackToStructuredOnly() {
    RestTemplate http = mock(RestTemplate.class);
    when(http.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
        .thenThrow(new ResourceAccessException("Read timed out"));

    Command cmd = parser.parse("docker run -d -p 8080:80 nginx");
    Outcome outcome = serviceWith(http, "key").explain("docker run -d -p 8080:80 nginx", cmd);

    assertTrue(outcome.explanationError());
    assertNull(outcome.explanation());
  }

  @Test
  void missingApiKeySkipsTheCall() {
    RestTemplate http = new RestTemplate();
    // Zero expectations: any HTTP attempt would fail verification.
    MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    Command cmd = parser.parse("git stash -u");
    Outcome outcome = serviceWith(http, "").explain("git stash -u", cmd);

    assertTrue(outcome.explanationError());
    assertNull(outcome.explanation());
    server.verify();
  }

  @Test
  void repeatedCommandReusesCache() {
    RestTemplate http = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    // Exactly one expectation: a second HTTP call would fail the test.
    server.expect(requestTo(BASE_URL + "/chat/completions"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(groqOkBody("Cached explanation."), MediaType.APPLICATION_JSON));

    ExplanationService service = serviceWith(http, "key");
    Command cmd = parser.parse("git stash -u");
    Outcome first = service.explain("git stash -u", cmd);
    Outcome second = service.explain("  git   stash -u  ", cmd); // same after normalization

    assertEquals("Cached explanation.", first.explanation());
    assertEquals(first, second);
    server.verify();
  }

  @Test
  void timeoutDoesNotRetryOnCacheHit() {
    RestTemplate http = mock(RestTemplate.class);
    when(http.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
        .thenReturn(groqOkBody("Once."));

    ExplanationService service = serviceWith(http, "key");
    Command cmd = parser.parse("git stash -u");
    service.explain("git stash -u", cmd);
    service.explain("git stash -u", cmd);

    verify(http, times(1)).postForObject(anyString(), any(HttpEntity.class), eq(String.class));
  }

  @Test
  void softResetPromptContainsNoHardText() {
    // Depends on the Part 1 fix: conditional --hard effects must be
    // filtered out before the prompt is built.
    ExplanationService service = serviceWith(new RestTemplate(), "key");
    Command cmd = parser.parse("git reset --soft HEAD~1");

    String prompt = service.buildPrompt(cmd);

    assertFalse(prompt.contains("--hard"),
        "Prompt for --soft must not mention --hard, got:\n" + prompt);
    assertTrue(prompt.contains("Moves the current branch pointer"),
        "Prompt must still contain the baseline effect, got:\n" + prompt);
  }

  @Test
  void hardResetPromptWarnsAboutDestruction() {
    ExplanationService service = serviceWith(new RestTemplate(), "key");
    Command cmd = parser.parse("git reset --hard HEAD");

    String prompt = service.buildPrompt(cmd);

    assertTrue(prompt.contains("[DESTRUCTIVE]"), "Destructive flag must be marked.");
    assertTrue(prompt.contains("caution"), "Prompt must ask for a caution sentence.");
  }
}
