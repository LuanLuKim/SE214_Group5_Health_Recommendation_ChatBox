package vn.edu.uit.se214.health.knowledge.authoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.uit.se214.health.accounts.auth.AccessTokenService;
import vn.edu.uit.se214.health.support.TestDatabase;

/** UC-15 over HTTP: permission CONTENT_MANAGE, status codes, the editor taken from the access token. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class ContentAuthoringApiTest {
  static final String ROOT = "/api/v1/knowledge";
  static final String ARTICLE = """
      {"title":"[TEST] Bài nháp","topic":"Kiểm thử","content":"Văn bản giả lập.",
       "sourceName":"Synthetic fixture","sourceUrl":"https://example.invalid/article"}""";
  static final String TEMPLATE = """
      {"title":"[TEST] Mẫu","goal":"SLEEP","activities":[{"id":"demo","title":"Hoạt động giả lập"}],
       "durationDays":7,"applicability":"Kiểm thử.","advisory":"Hỏi ý kiến nhân viên y tế.",
       "sourceName":"Synthetic fixture","sourceUrl":"https://example.invalid/template"}""";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired AccessTokenService tokens;
  @Autowired ObjectMapper json;

  UUID expert;
  UUID admin;
  UUID user;
  String csrf;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (Connection c = TestDatabase.server().getPostgresDatabase().getConnection();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE content_authoring");
    }
    registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl("content_authoring"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.data-mode", () -> "postgres");
    registry.add("app.seed-synthetic", () -> "true");
    registry.add("app.accounts.terms-version", () -> "v1");
    registry.add("app.accounts.auth-token.secret", () -> "test-only-auth-token-secret-at-least-32-bytes");
    registry.add("app.rate-limit-per-minute", () -> "10000");
  }

  UUID account(String role) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO accounts.users (id, email, display_name) VALUES (?, ?, 'Synthetic editor')",
        id, id + "@example.invalid");
    jdbc.update("INSERT INTO accounts.user_roles (user_id, role) VALUES (?, ?)", id, role);
    return id;
  }

  @BeforeEach
  void accounts() throws Exception {
    expert = account("CONTENT_EXPERT");
    admin = account("ADMIN");
    user = account("USER");
    csrf = mvc.perform(get("/api/v1/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN").getValue();
  }

  MockHttpServletRequestBuilder as(UUID account, MockHttpServletRequestBuilder request) {
    Cookie access = new Cookie("HA_ACCESS", tokens.issue(account, Instant.now().plus(10, ChronoUnit.MINUTES)));
    return request.cookie(access, new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf)
        .contentType(MediaType.APPLICATION_JSON);
  }

  String id(String response) throws Exception {
    return json.readTree(response).at("/data/id").asText();
  }

  @Test
  void articleLifecycleOverHttp() throws Exception {
    String created = mvc.perform(as(expert, post(ROOT + "/articles")).content(ARTICLE))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.reviewStatus").value("DRAFT"))
        .andExpect(jsonPath("$.data.verifiedAt").doesNotExist())
        .andExpect(jsonPath("$.data.createdBy").value(expert.toString()))
        .andReturn().getResponse().getContentAsString();
    String id = id(created);
    mvc.perform(as(admin, get(ROOT + "/authoring/articles/" + id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").value("Văn bản giả lập."));
    mvc.perform(as(admin, get(ROOT + "/authoring/articles")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.meta.total").isNumber());
    mvc.perform(as(admin, patch(ROOT + "/articles/" + id)).content("{\"title\":\"[TEST] Đã sửa\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.updatedBy").value(admin.toString()))
        .andExpect(jsonPath("$.data.revision").value(2));
    // a draft is not public
    mvc.perform(get(ROOT + "/articles/" + id)).andExpect(status().isNotFound());
    mvc.perform(as(expert, post(ROOT + "/articles/" + id + "/submit")).content("{\"reviewStatus\":\"APPROVED\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    mvc.perform(as(expert, post(ROOT + "/articles/" + id + "/submit")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reviewStatus").value("PENDING_REVIEW"))
        .andExpect(jsonPath("$.data.submittedBy").value(expert.toString()));
    mvc.perform(as(expert, delete(ROOT + "/articles/" + id)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("CONTENT_NOT_EDITABLE"));

    String other = id(mvc.perform(as(expert, post(ROOT + "/articles")).content(ARTICLE)).andReturn().getResponse()
        .getContentAsString());
    mvc.perform(as(expert, delete(ROOT + "/articles/" + other)))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    for (String bad : List.of("not-a-uuid", "00000000-0000-4000-8000-999999999999")) {
      mvc.perform(as(expert, get(ROOT + "/authoring/articles/" + bad)))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
    mvc.perform(as(expert, post(ROOT + "/articles")).content("{\"title\":\"x\",\"reviewStatus\":\"APPROVED\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
  }

  @Test
  void templateLifecycleOverHttp() throws Exception {
    String id = id(mvc.perform(as(admin, post(ROOT + "/templates")).content(TEMPLATE))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.activities[0].id").value("demo"))
        .andExpect(jsonPath("$.data.createdBy").value(admin.toString()))
        .andReturn().getResponse().getContentAsString());
    mvc.perform(as(expert, patch(ROOT + "/templates/" + id)).content("{\"goal\":\"RUN\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    mvc.perform(as(expert, post(ROOT + "/templates/" + id + "/submit")).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reviewStatus").value("PENDING_REVIEW"));
    // the synthetic seed template is used by a seeded plan
    mvc.perform(as(expert, delete(ROOT + "/templates/00000000-0000-4000-8000-000000000050")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("TEMPLATE_IN_USE"));
  }

  @Test
  void onlyContentManagersReachAuthoring() throws Exception {
    List<Object[]> routes = List.of(
        new Object[] {HttpMethod.GET, "/authoring/articles"},
        new Object[] {HttpMethod.GET, "/authoring/templates/00000000-0000-4000-8000-999999999999"},
        new Object[] {HttpMethod.POST, "/articles"},
        new Object[] {HttpMethod.PATCH, "/templates/00000000-0000-4000-8000-999999999999"},
        new Object[] {HttpMethod.DELETE, "/articles/00000000-0000-4000-8000-999999999999"},
        new Object[] {HttpMethod.POST, "/templates/00000000-0000-4000-8000-999999999999/submit"});
    for (Object[] route : routes) {
      mvc.perform(as(user, request((HttpMethod) route[0], ROOT + route[1])).content("{}"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
      mvc.perform(request((HttpMethod) route[0], ROOT + route[1])
              .header("X-User-ID", admin.toString())
              .header("X-Role", "ADMIN")
              .cookie(new Cookie("XSRF-TOKEN", csrf))
              .header("X-XSRF-TOKEN", csrf))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("SESSION_INVALID"));
    }
  }
}
