package com.why.explainer.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Rate-limit contract: requests within the per-minute budget succeed,
 * the first one over it gets 429 JSON. A dedicated X-Forwarded-For IP
 * keeps this burst isolated from every other test's bucket.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RateLimitTest {

  private static final String URL = "/api/explain";
  private static final String BODY = "{\"command\":\"git reset --soft HEAD~1\"}";

  @Autowired
  MockMvc mockMvc;

  @MockBean
  CommandParser parser;

  @MockBean
  ExplanationService explanations;

  @Value("${rate-limit.requests-per-minute}")
  int requestsPerMinute;

  @Test
  void burstOverLimitReturns429WithExpectedBody() throws Exception {
    when(parser.parse(ArgumentMatchers.anyString())).thenReturn(new Command("git", "reset",
        List.of(new Flag("--soft", "Move HEAD only.", null)),
        List.of(new Arg("HEAD~1", "commit-ref")),
        List.of(new Effect("git-history", "Moves the branch pointer.", "HEAD~1"))));
    when(explanations.explain(ArgumentMatchers.anyString(), ArgumentMatchers.any(Command.class)))
        .thenReturn(new Outcome("Moves the branch pointer back.", false));

    String ip = "203.0.113.77";
    for (int i = 0; i < requestsPerMinute; i++) {
      mockMvc.perform(post(URL)
              .header("X-Forwarded-For", ip)
              .contentType(MediaType.APPLICATION_JSON)
              .content(BODY))
          .andExpect(status().isOk());
    }

    mockMvc.perform(post(URL)
            .header("X-Forwarded-For", ip)
            .contentType(MediaType.APPLICATION_JSON)
            .content(BODY))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "60"))
        .andExpect(jsonPath("$.error").value("rate_limited"))
        .andExpect(jsonPath("$.message").value(
            "too many requests — please wait a moment before trying again."));
  }
}
