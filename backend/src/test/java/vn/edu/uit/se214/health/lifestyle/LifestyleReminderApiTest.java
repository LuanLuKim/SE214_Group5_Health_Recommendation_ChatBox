package vn.edu.uit.se214.health.lifestyle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.uit.se214.health.accounts.auth.AccessTokenService;
import vn.edu.uit.se214.health.support.TestDatabase;

/**
 * UC-14 over HTTP in DATA_MODE=postgres with synthetic seeding. SMTP points at a host that is
 * never contacted: the worker is off here (ReminderDispatcherTest covers sending).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class LifestyleReminderApiTest {
  static final UUID SEEDED_USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final UUID OTHER_USER = UUID.fromString("00000000-0000-4000-8000-000000000002");
  static final String SEEDED_REMINDER = "00000000-0000-4000-8000-000000000052";
  static final String REMINDERS = "/api/v1/lifestyle/reminders";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired AccessTokenService tokens;
  @Autowired ObjectMapper json;

  String csrf;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (Connection c = TestDatabase.server().getPostgresDatabase().getConnection();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE lifestyle_reminder_api");
    }
    registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl("lifestyle_reminder_api"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.data-mode", () -> "postgres");
    registry.add("app.seed-synthetic", () -> "true");
    registry.add("app.accounts.auth-token.secret", () -> "test-only-auth-token-secret-at-least-32-bytes");
    registry.add("spring.mail.host", () -> "smtp.example.invalid");
    registry.add("app.lifestyle.reminders.from-address", () -> "reminders@example.invalid");
    registry.add("app.lifestyle.reminders.enabled", () -> "false");
  }

  @BeforeEach
  void csrf() throws Exception {
    csrf = LifestyleApiTest.cookie(mvc.perform(get("/api/v1/csrf")).andReturn(), "XSRF-TOKEN");
  }

  MockHttpServletRequestBuilder as(UUID user, MockHttpServletRequestBuilder request) {
    Cookie access = new Cookie("HA_ACCESS", tokens.issue(user, Instant.now().plus(10, ChronoUnit.MINUTES)));
    return request.cookie(access, new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf);
  }

  ResultActions send(UUID user, MockHttpServletRequestBuilder request, String body) throws Exception {
    return mvc.perform(as(user, request).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  String create(String body) throws Exception {
    String response =
        send(SEEDED_USER, post(REMINDERS), body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(response).at("/data/id").asText();
  }

  @Test
  void routesNeedASessionAndWritesNeedCsrf() throws Exception {
    mvc.perform(get(REMINDERS)).andExpect(status().isUnauthorized());
    Cookie access = new Cookie("HA_ACCESS", tokens.issue(SEEDED_USER, Instant.now().plus(10, ChronoUnit.MINUTES)));
    mvc.perform(post(REMINDERS).cookie(access).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
  }

  @Test
  @Transactional
  void withoutConsentTheOwnerCanOnlySeeTurnOffAndDelete() throws Exception {
    String id = create("{\"content\":\"[TEST] Uống nước\",\"localTime\":\"07:30\"}");
    jdbc.sql("UPDATE accounts.consents SET health_data_processing_accepted = false WHERE user_id = ?")
        .param(SEEDED_USER)
        .update();
    int before = count();

    // Creating, turning back on or changing anything else still needs consent.
    send(SEEDED_USER, post(REMINDERS), "{\"content\":\"Uống nước\",\"localTime\":\"07:30\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("HEALTH_CONSENT_REQUIRED"));
    send(SEEDED_USER, patch(REMINDERS + "/" + SEEDED_REMINDER), "{\"enabled\":true}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("HEALTH_CONSENT_REQUIRED"));
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":false,\"localTime\":\"08:00\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("HEALTH_CONSENT_REQUIRED"));
    assertThat(count()).isEqualTo(before);

    mvc.perform(as(SEEDED_USER, get(REMINDERS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reminders[*].id", hasItem(id)));
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":false}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.localTime").value("07:30"));
    mvc.perform(as(SEEDED_USER, delete(REMINDERS + "/" + id))).andExpect(status().isOk());
    assertThat(count()).isEqualTo(before - 1);
  }

  @Test
  void createListChangeTurnOffAndDelete() throws Exception {
    String id = create("{\"content\":\" Uống một ly nước \",\"localTime\":\"07:30\",\"daysOfWeek\":[5,1,1,3]}");

    mvc.perform(as(SEEDED_USER, get(REMINDERS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.availableChannels").value(contains("EMAIL")))
        .andExpect(jsonPath("$.data.reminders[?(@.id == '" + id + "')].content").value(contains("Uống một ly nước")))
        .andExpect(jsonPath("$.data.reminders[?(@.id == '" + id + "')].daysOfWeek[*]").value(contains(1, 3, 5)))
        .andExpect(jsonPath("$.data.reminders[?(@.id == '" + id + "')].timezone").value(contains("Asia/Ho_Chi_Minh")))
        .andExpect(jsonPath("$.data.reminders[?(@.id == '" + id + "')].channel").value(contains("EMAIL")))
        .andExpect(jsonPath("$.data.reminders[?(@.id == '" + id + "')].enabled").value(contains(true)))
        .andExpect(jsonPath("$.data.reminders[*].id").value(hasItem(SEEDED_REMINDER)));

    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"localTime\":\"21:00\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.localTime").value("21:00"))
        .andExpect(jsonPath("$.data.content").value("Uống một ly nước"))
        .andExpect(jsonPath("$.data.nextSendAt").isNotEmpty());
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":false}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.nextSendAt").doesNotExist());

    mvc.perform(as(SEEDED_USER, delete(REMINDERS + "/" + id))).andExpect(status().isOk());
    mvc.perform(as(SEEDED_USER, get(REMINDERS)))
        .andExpect(jsonPath("$.data.reminders[*].id").value(not(hasItem(id))));
    mvc.perform(as(SEEDED_USER, delete(REMINDERS + "/" + id)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("REMINDER_NOT_FOUND"));
  }

  @Test
  void anotherUserCannotSeeChangeOrDeleteIt() throws Exception {
    jdbc.sql("INSERT INTO accounts.user_roles (user_id, role) VALUES (?, 'USER') ON CONFLICT DO NOTHING")
        .param(OTHER_USER)
        .update();
    jdbc.sql(
            """
            INSERT INTO accounts.consents
              (user_id, terms_version, adult_confirmed, accepted_at,
               health_data_processing_accepted, non_diagnostic_acknowledged)
            VALUES (?, 'test-consent', true, now(), true, true)
            ON CONFLICT DO NOTHING
            """)
        .param(OTHER_USER)
        .update();
    String id = create("{\"content\":\"[TEST] Đi bộ\",\"localTime\":\"18:00\"}");
    mvc.perform(as(OTHER_USER, get(REMINDERS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reminders[*].id").value(not(hasItem(id))));
    send(OTHER_USER, patch(REMINDERS + "/" + id), "{\"enabled\":false}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("REMINDER_NOT_FOUND"));
    mvc.perform(as(OTHER_USER, delete(REMINDERS + "/" + id))).andExpect(status().isNotFound());
    assertThat(
            jdbc.sql("SELECT enabled FROM lifestyle.reminders WHERE id = ?::uuid")
                .param(id)
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void invalidInputIsRejectedAndNothingIsSaved() throws Exception {
    int before = count();
    for (String body :
        new String[] {
          "{\"content\":\"Uống nước\",\"localTime\":\"7:00\"}",
          "{\"content\":\"Uống nước\",\"localTime\":\"24:00\"}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00:00\"}",
          "{\"content\":\"Uống nước\"}",
          "{\"content\":\"   \",\"localTime\":\"07:00\"}",
          "{\"content\":\"" + "a".repeat(201) + "\",\"localTime\":\"07:00\"}",
          "{\"content\":\"Uống\\u0007 nước\",\"localTime\":\"07:00\"}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00\",\"daysOfWeek\":[]}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00\",\"daysOfWeek\":[0,8]}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00\",\"timezone\":\"Mars/Base\"}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00\",\"channel\":\"SMS\"}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00\",\"enabled\":\"yes\"}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00\",\"userId\":\"" + OTHER_USER + "\"}",
          "[]"
        }) {
      send(SEEDED_USER, post(REMINDERS), body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("INVALID_REMINDER"));
    }
    assertThat(count()).isEqualTo(before);
  }

  @Test
  void webPushIsNotAvailableYetButMayBeSavedOff() throws Exception {
    send(SEEDED_USER, post(REMINDERS), "{\"content\":\"Ngủ sớm\",\"localTime\":\"22:00\",\"channel\":\"WEB_PUSH\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("REMINDER_CHANNEL_UNAVAILABLE"));
    String id = create("{\"content\":\"Ngủ sớm\",\"localTime\":\"22:00\",\"channel\":\"WEB_PUSH\",\"enabled\":false}");
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("REMINDER_CHANNEL_UNAVAILABLE"));
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true,\"channel\":\"EMAIL\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.channel").value("EMAIL"));
  }

  @Test
  void aReminderCanFollowAnActivityOfTheActivePlan() throws Exception {
    // The seeded plan's template has one activity, "demo".
    send(SEEDED_USER, post(REMINDERS), "{\"activityId\":\"demo\",\"localTime\":\"06:00\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.activityId").value("demo"))
        .andExpect(jsonPath("$.data.content").value("Hoạt động giả lập"));
    send(SEEDED_USER, post(REMINDERS), "{\"activityId\":\"swim\",\"localTime\":\"06:00\"}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_IN_PLAN"));
  }

  /** After a plan switch turned it off, a reminder of an old activity comes back only relinked or unlinked. */
  @Test
  @Transactional
  void aReminderOfAnActivityNoLongerInThePlanIsTurnedOnOnlyRelinkedOrUnlinked() throws Exception {
    String id = create("{\"activityId\":\"demo\",\"localTime\":\"06:00\"}");
    // What the plan switch leaves behind: off, still pointing at the old plan's activity.
    jdbc.sql("UPDATE lifestyle.reminders SET enabled = false, activity_id = 'old-plan-walk' WHERE id = ?::uuid")
        .param(id)
        .update();

    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_IN_PLAN"));
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"content\":\"[TEST] Đi bộ\"}")
        .andExpect(status().isOk()); // still off: no check needed
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true,\"activityId\":\"demo\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(true))
        .andExpect(jsonPath("$.data.activityId").value("demo"));
    jdbc.sql("UPDATE lifestyle.reminders SET enabled = false, activity_id = 'old-plan-walk' WHERE id = ?::uuid")
        .param(id)
        .update();
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true,\"activityId\":null}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(true))
        .andExpect(jsonPath("$.data.activityId").doesNotExist());
  }

  int count() {
    return jdbc.sql("SELECT count(*) FROM lifestyle.reminders").query(Integer.class).single();
  }
}
