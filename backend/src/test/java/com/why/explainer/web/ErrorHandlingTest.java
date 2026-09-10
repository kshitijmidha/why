package com.why.explainer.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Error-contract tests: the root pointer, JSON 404s for unmapped
 * routes, and JSON 400s for bad input. POSTs here carry a dedicated
 * X-Forwarded-For IP so they never consume the shared test-suite
 * rate-limit bucket.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorHandlingTest {

  private static final String TEST_IP = "203.0.113.21";

  @Autowired
  MockMvc mockMvc;

  @Test
  void rootReturnsServicePointer() throws Exception {
    mockMvc.perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.service").value("why-backend"))
        .andExpect(jsonPath("$.docs").value("see /api/explain"));
  }

  @Test
  void unmappedRouteReturnsJsonNotFound() throws Exception {
    mockMvc.perform(get("/no-such-route"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("not_found"))
        .andExpect(jsonPath("$.message", containsString("/no-such-route")));
  }

  @Test
  void malformedJsonReturnsJsonBadRequest() throws Exception {
    mockMvc.perform(post("/api/explain")
            .header("X-Forwarded-For", TEST_IP)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{not valid json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"));
  }

  @Test
  void blankCommandReturnsJsonBadRequest() throws Exception {
    mockMvc.perform(post("/api/explain")
            .header("X-Forwarded-For", "203.0.113.22")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"command\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"))
        .andExpect(jsonPath("$.message", containsString("must not be blank")));
  }
}
