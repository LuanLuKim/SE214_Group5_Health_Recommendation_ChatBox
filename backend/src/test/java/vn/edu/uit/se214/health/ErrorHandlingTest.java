package vn.edu.uit.se214.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Failures always leave as the JSON error envelope, and a 500 leaves a safe trace in the log. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ErrorHandlingTest.FailingController.class)
@ExtendWith(OutputCaptureExtension.class)
class ErrorHandlingTest {
  private static final String SENTINEL = "private-test-sentinel";

  @Autowired MockMvc mvc;

  @RestController
  static class FailingController {
    @GetMapping("/test/unexpected")
    String unexpected() {
      throw new IllegalStateException(SENTINEL);
    }

    @GetMapping("/test/database-down")
    String databaseDown() {
      throw new CannotGetJdbcConnectionException(SENTINEL);
    }
  }

  @Test
  void errorPathIsNotTheSpringBootDefaultPage() throws Exception {
    mvc.perform(get("/error"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.timestamp").doesNotExist());
    // A container error dispatch keeps its status and still uses the envelope.
    mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 413))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
  }

  @Test
  void errorsAreJsonEvenWhenTheClientAsksForAnotherFormat(CapturedOutput output) throws Exception {
    mvc.perform(get("/healthz").accept(MediaType.APPLICATION_XML))
        .andExpect(status().isNotAcceptable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error.code").value("NOT_ACCEPTABLE"));
    mvc.perform(get("/missing").accept(MediaType.TEXT_HTML))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    assertThat(output.getAll()).doesNotContain("Failure in @ExceptionHandler");
  }

  @Test
  void unexpectedErrorIsLoggedWithoutItsMessage(CapturedOutput output) throws Exception {
    MvcResult result =
        mvc.perform(get("/test/unexpected"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andReturn();
    String requestId = result.getResponse().getHeader("X-Request-ID");
    assertThat(result.getResponse().getContentAsString()).doesNotContain(SENTINEL, "IllegalState");
    assertThat(output.getAll())
        .contains(requestId, "java.lang.IllegalStateException", "FailingController.unexpected")
        .doesNotContain(SENTINEL);
  }

  @Test
  void databaseOutageIsReportedAsTemporarilyUnavailable(CapturedOutput output) throws Exception {
    mvc.perform(get("/test/database-down"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("DATABASE_UNAVAILABLE"))
        .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    assertThat(output.getAll())
        .contains("CannotGetJdbcConnectionException")
        .doesNotContain(SENTINEL);
  }
}
