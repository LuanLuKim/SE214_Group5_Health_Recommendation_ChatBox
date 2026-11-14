package vn.edu.uit.se214.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.ActiveProfiles;
import vn.edu.uit.se214.health.safety.UnavailableSafetyEvaluator;
import vn.edu.uit.se214.health.safety.api.CareLevel;
import vn.edu.uit.se214.health.safety.api.SafetyDecision;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/** Port of apps/api/tests/api.test.ts (DATA_MODE=mock, no database). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class ApiTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  @Test
  void healthAndModuleRegistryExposeSixModulesCoveringEach19UcOnce() throws Exception {
    mvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ok"));

    JsonNode data = body(mvc.perform(get("/api/v1/modules")).andExpect(status().isOk()).andReturn()).get("data");
    List<ModuleDescriptor> modules = List.of(json.treeToValue(data, ModuleDescriptor[].class));
    assertThat(modules).isEqualTo(ModuleRegistry.MODULES).hasSize(6);
    List<String> useCases = modules.stream().flatMap(m -> m.useCases().stream()).toList();
    assertThat(useCases).hasSize(19);
    assertThat(new HashSet<>(useCases)).hasSize(19);
    for (ModuleDescriptor module : modules)
      mvc.perform(get(module.apiPrefix())).andExpect(jsonPath("$.data.id").value(module.id()));
  }

  @Test
  void publicSyntheticSearchWorksAndDoesNotExposeTheDraftFixture() throws Exception {
    mvc.perform(get("/api/v1/knowledge/articles").param("q", "giấc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.meta.status").value("SYNTHETIC_DATA"))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].title").value(org.hamcrest.Matchers.startsWith("[TEST]")));
    mvc.perform(get("/api/v1/knowledge/articles").param("q", "not-present"))
        .andExpect(jsonPath("$.data.length()").value(0));
    mvc.perform(get("/api/v1/knowledge/facilities"))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].address").value(org.hamcrest.Matchers.containsString("không có thật")));
    mvc.perform(get("/api/v1/knowledge/articles").param("q", "a", "b")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/knowledge/articles").param("q", "x".repeat(201)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
    mvc.perform(get("/api/v1/knowledge/facilities").param("q", "a\u0000b"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
  }

  @Test
  void swaggerUiAndOpenApiDocumentAreAvailable() throws Exception {
    mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/swagger-ui/index.html"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("style-src 'self'")));
    mvc.perform(get("/swagger-ui/swagger-ui-bundle.js"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self'")));
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("Health Recommendation API"))
        .andExpect(jsonPath("$.paths['/api/v1/knowledge/articles']").exists());
  }

  @Test
  void privateClinicalReviewAndAdminPathsFailClosedEvenWithForgedIdentity() throws Exception {
    List<Object[]> paths =
        List.of(
            new Object[] {HttpMethod.POST, "/safety/evaluate"},
            new Object[] {HttpMethod.GET, "/safety/rules"},
            new Object[] {HttpMethod.POST, "/knowledge/articles"},
            new Object[] {HttpMethod.GET, "/knowledge/reviews"},
            new Object[] {HttpMethod.GET, "/knowledge/feedback"},
            new Object[] {HttpMethod.GET, "/health/profile"},
            new Object[] {HttpMethod.GET, "/health/metrics"},
            new Object[] {HttpMethod.GET, "/health/statistics"},
            new Object[] {HttpMethod.POST, "/chat/messages"},
            new Object[] {HttpMethod.GET, "/chat/sessions"},
            new Object[] {HttpMethod.POST, "/chat/messages/example/feedback"},
            new Object[] {HttpMethod.GET, "/accounts/me"},
            new Object[] {HttpMethod.GET, "/accounts/users"},
            new Object[] {HttpMethod.GET, "/lifestyle/templates"},
            new Object[] {HttpMethod.GET, "/lifestyle/reminders"});
    for (Object[] path : paths) {
      mvc.perform(
              request((HttpMethod) path[0], "/api/v1" + path[1])
                  .header("X-User-ID", "admin")
                  .header("X-Role", "ADMIN"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.error.code").value("AUTH_NOT_CONFIGURED"))
          .andExpect(jsonPath("$.error.requestId").isNotEmpty())
          .andExpect(jsonPath("$.data").doesNotExist());
    }
    mvc.perform(
            post("/api/v1/accounts/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"new@example.test","displayName":"Test User","password":"test-password-123",
                     "consent":{"termsAccepted":true,"healthDataProcessingAccepted":true,
                       "nonDiagnosticAcknowledged":true,"adultConfirmed":true,"termsVersion":"v1"}}
                    """))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("REGISTRATION_UNAVAILABLE"));
  }

  @Test
  void safeErrorsResponseHeadersAndRequestSizeLimits() throws Exception {
    MvcResult missing = mvc.perform(get("/missing")).andExpect(status().isNotFound()).andReturn();
    assertThat(missing.getResponse().getHeader("X-Powered-By")).isNull();
    assertThat(missing.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(missing.getResponse().getHeader("Content-Security-Policy"))
        .isEqualTo("default-src 'none'; frame-ancestors 'none'");
    assertThat(body(missing).at("/error/requestId").asText())
        .isEqualTo(missing.getResponse().getHeader("X-Request-ID"));

    MvcResult malformed =
        mvc.perform(
                post("/api/v1/accounts/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{private-test-sentinel"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
            .andReturn();
    assertThat(malformed.getResponse().getContentAsString())
        .doesNotContain("private-test-sentinel", "Exception", "stack");

    mvc.perform(
            post("/api/v1/accounts/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + "x".repeat(40000) + "\"}"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(header().exists("X-Request-ID"));
  }

  @Test
  void unconfiguredSafetyGateYieldsNoCareDirectionAndHasExactlyFiveLabels() {
    SafetyDecision result = new UnavailableSafetyEvaluator().evaluate("synthetic test");
    assertThat(result.status()).isEqualTo(SafetyDecision.Status.UNAVAILABLE);
    assertThat(result.careLevel()).isNull();
    assertThat(CareLevel.values()).hasSize(5);
    assertThat(List.of(CareLevel.values()).stream().map(Enum::name)).doesNotContain("OUT_OF_SCOPE");
  }
}
