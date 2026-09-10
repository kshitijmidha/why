package com.why.explainer.web;

import com.why.explainer.model.Command;
import com.why.explainer.service.CommandParser;
import com.why.explainer.service.ExplanationService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Single REST endpoint for the whole backend.
 *
 * <p>Returns the deterministic breakdown plus the AI explanation.
 * The explanation is best-effort: if the LLM is unreachable, the
 * structured command still comes back with explanation=null and
 * explanationError=true.
 *
 * <p>CORS is locked down in {@code CorsConfig}: the allowed origin comes
 * from the ALLOWED_ORIGIN env var (the deployed frontend URL), defaulting
 * to the local Vite dev server.
 */
@RestController
@RequestMapping("/api")
public class ExplainController {

  private final CommandParser parser;
  private final ExplanationService explanations;

  public ExplainController(CommandParser parser, ExplanationService explanations) {
    this.parser = parser;
    this.explanations = explanations;
  }

  /** Request body: { "command": "git reset --soft HEAD~1" } */
  public record ExplainRequest(@NotBlank(message = "Field 'command' must not be blank.") String command) {
  }

  /**
   * Response body: structured breakdown + plain-English explanation.
   * Confidence is "verified" when the command matched the knowledge base,
   * "inferred" when the explanation is an LLM-only guess for an unknown command.
   */
  public record ExplainResponse(
      Command command, String explanation, boolean explanationError, String confidence) {
  }

  /** Anything longer is rejected with 400 before parsing or any LLM call. */
  private static final int MAX_COMMAND_LENGTH = 500;

  @PostMapping("/explain")
  public ExplainResponse explain(@RequestBody ExplainRequest request) {
    if (request == null || request.command() == null || request.command().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field 'command' must not be blank.");
    }
    if (request.command().length() > MAX_COMMAND_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Field 'command' must be 500 characters or fewer.");
    }
    Command command = parser.parse(request.command());
    boolean unknown = "unknown".equals(command.subcommand());
    ExplanationService.Outcome outcome = unknown
        ? explanations.explainUnknown(request.command())
        : explanations.explain(request.command(), command);
    return new ExplainResponse(command, outcome.explanation(), outcome.explanationError(),
        unknown ? "inferred" : "verified");
  }
}
