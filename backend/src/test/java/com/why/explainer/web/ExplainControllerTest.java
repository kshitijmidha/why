package com.why.explainer.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.why.explainer.model.Arg;
import com.why.explainer.model.Command;
import com.why.explainer.model.Effect;
import com.why.explainer.model.Flag;
import com.why.explainer.service.CommandParser;
import com.why.explainer.service.ExplanationService;
import com.why.explainer.service.ExplanationService.Outcome;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for POST /api/explain: confidence marking,
 * the inferred fallback path, and the input length cap.
 * The LLM is mocked — no network calls here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExplainControllerTest {

  private static final String URL = "/api/explain";

  @Autowired
  MockMvc mockMvc;

  @MockBean
  CommandParser parser;

  @MockBean
  ExplanationService explanations;

  private Command knownCommand() {
    return new Command("git", "reset",
        List.of(new Flag("--soft", "Move HEAD only.", null)),
        List.of(new Arg("HEAD~1", "commit-ref")),
        List.of(new Effect("git-history", "Moves the branch pointer.", "HEAD~1")));
  }

  private Command unknownCommand(String tool) {
    return new Command(tool, "unknown",
        List.of(),
        List.of(new Arg("get", "operand")),
        List.of(new Effect("unknown", "Unknown tool '" + tool + "'.", null)));
  }

  private String body(String command) {
    return "{\"command\":\"" + command + "\"}";
  }

  @Test
  void knownCommandReturnsVerified() throws Exception {
    when(parser.parse("git reset --soft HEAD~1")).thenReturn(knownCommand());
    when(explanations.explain(anyString(), any(Command.class)))
        .thenReturn(new Outcome("Moves the branch pointer back.", false));

    mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("git reset --soft HEAD~1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confidence").value("verified"))
        .andExpect(jsonPath("$.explanation").value("Moves the branch pointer back."))
        .andExpect(jsonPath("$.explanationError").value(false))
        .andExpect(jsonPath("$.command.subcommand").value("reset"));

    verify(explanations, never()).explainUnknown(anyString());
  }

  @Test
  void unknownCommandReturnsInferredWithExplanation() throws Exception {
    when(parser.parse("kubectl get pods")).thenReturn(unknownCommand("kubectl"));
    when(explanations.explainUnknown("kubectl get pods"))
        .thenReturn(new Outcome("Likely lists pods (best-effort guess).", false));

    mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("kubectl get pods")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confidence").value("inferred"))
        .andExpect(jsonPath("$.explanation").value("Likely lists pods (best-effort guess)."))
        .andExpect(jsonPath("$.explanationError").value(false));

    verify(explanations, never()).explain(anyString(), any(Command.class));
  }

  @Test
  void inferredPathFallsBackOnLlmFailure() throws Exception {
    when(parser.parse("npm install")).thenReturn(unknownCommand("npm"));
    when(explanations.explainUnknown("npm install")).thenReturn(new Outcome(null, true));

    mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("npm install")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confidence").value("inferred"))
        .andExpect(jsonPath("$.explanationError").value(true))
        .andExpect(jsonPath("$.explanation").value(nullValue()));
  }

  @Test
  void oversizedCommandIsRejectedBeforeParsingOrLlm() throws Exception {
    String big = "git status " + "x".repeat(600); // well over 500 chars

    mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(big)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"))
        .andExpect(jsonPath("$.message", containsString("500 characters")));

    verifyNoInteractions(parser, explanations);
  }
}
