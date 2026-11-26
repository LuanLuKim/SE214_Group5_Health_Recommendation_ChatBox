package health;

import system.HealthAssistantApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import accounts.auth.AccessTokenService;
import integrations.PythonServiceClient;
import support.TestDatabase;

/**
 * UC-05 over HTTP in DATA_MODE=postgres with synthetic seeding. The seeded user has consent and
 * three days: 2026-10-26 (65 kg, 7 h), 2026-10-27 (65.2 kg, 7.5 h), 2026-10-28 (65.1 kg, 6.5 h).
 * services/stats is replaced by a mock, so no Python service is needed.
 */
@SpringBootTest(classes = HealthAssistantApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class HealthMetricsApiTest {
  static final UUID SEEDED_USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final String TRENDS_PATH = "/v1/metrics/trends";
  static final String STATS_OK =
      "{\"data\":{\"status\":\"OK\",\"summary\":{\"weight\":{\"unit\":\"kg\",\"average\":65.1}},"
          + "\"insights\":[{\"code\":\"STABLE\",\"severity\":\"INFO\",\"symbol\":\"ℹ\",\"message\":\"Ổn định\"}]}}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired AccessTokenService tokens;
  @Autowired ObjectMapper mapper;

  @MockitoBean(name = "statsService")
  PythonServiceClient stats;

  String csrf;
  Cookie access;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (Connection c = TestDatabase.server().getPostgresDatabase().getConnection();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE health_metrics_api");
    }
    registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl("health_metrics_api"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.data-mode", () -> "postgres");
    registry.add("app.seed-synthetic", () -> "true");
    registry.add("app.accounts.auth-token.secret", () -> "test-only-auth-token-secret-at-least-32-bytes");
  }

  @BeforeEach
  void signIn() throws Exception {
    access = new Cookie("HA_ACCESS", tokens.issue(SEEDED_USER, Instant.now().plus(10, ChronoUnit.MINUTES)));
    csrf = cookie(mvc.perform(get("/api/v1/csrf")).andReturn(), "XSRF-TOKEN");
  }

  static String cookie(MvcResult result, String name) {
    return result.getResponse().getHeaders("Set-Cookie").stream()
        .filter(value -> value.startsWith(name + "="))
        .map(value -> value.substring(name.length() + 1, value.indexOf(';')))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing " + name + " cookie"));
  }

  MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request) {
    return request.cookie(access, new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf);
  }

  MockHttpServletRequestBuilder save(String date, String body) {
    return signed(put("/api/v1/health/metrics/" + date)).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  void statsAnswers(String json) throws Exception {
    JsonNode node = mapper.readTree(json);
    when(stats.post(eq(TRENDS_PATH), any(), eq(JsonNode.class))).thenReturn(node);
  }

  long rows(String extraWhere) {
    return jdbc.sql("SELECT count(*) FROM health.daily_metrics WHERE user_id = ? " + extraWhere)
        .param(SEEDED_USER)
        .query(Long.class)
        .single();
  }

  void withdrawConsent() {
    assertThat(
            jdbc.sql("UPDATE accounts.consents SET health_data_processing_accepted = false WHERE user_id = ?")
                .param(SEEDED_USER)
                .update())
        .isPositive();
  }

  // ===== xem biểu đồ và nhận xét =====

  @Test
  @Transactional
  void aWeekEndingOnTheChosenDayHasThatWeeksPointsAndTheStatsAnswer() throws Exception {
    statsAnswers(STATS_OK);
    mvc.perform(signed(get("/api/v1/health/metrics?period=week&to=2026-10-28")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.period").value("week"))
        .andExpect(jsonPath("$.data.from").value("2026-10-22"))
        .andExpect(jsonPath("$.data.to").value("2026-10-28"))
        .andExpect(jsonPath("$.data.points.length()").value(3))
        .andExpect(jsonPath("$.data.points[0].date").value("2026-10-26"))
        .andExpect(jsonPath("$.data.points[2].weightKg").value(65.1))
        .andExpect(jsonPath("$.data.points[2].sleepHours").value(6.5))
        .andExpect(jsonPath("$.data.units.weightKg").value("kg"))
        .andExpect(jsonPath("$.data.units.sleepHours").value("giờ"))
        .andExpect(jsonPath("$.data.insightsStatus").value("OK"))
        .andExpect(jsonPath("$.data.summary.weight.average").value(65.1))
        .andExpect(jsonPath("$.data.insights[0].code").value("STABLE"));
  }

  @Test
  @Transactional
  @SuppressWarnings("unchecked")
  void theStatsServiceGetsOnlyTheWindowAndTheDayValues() throws Exception {
    statsAnswers(STATS_OK);
    mvc.perform(signed(get("/api/v1/health/metrics?period=week&to=2026-10-28"))).andExpect(status().isOk());
    ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
    verify(stats).post(eq(TRENDS_PATH), sent.capture(), eq(JsonNode.class));
    Map<String, Object> body = (Map<String, Object>) sent.getValue();
    assertThat(body.keySet()).containsExactlyInAnyOrder("period", "from", "to", "points");
    assertThat(body.get("from")).isEqualTo("2026-10-22");
    List<Map<String, Object>> points = (List<Map<String, Object>>) body.get("points");
    assertThat(points).hasSize(3);
    assertThat(points.get(0).keySet()).containsExactlyInAnyOrder("date", "weightKg", "sleepHours");
  }

  @Test
  @Transactional
  void aMonthIsTheThirtyDaysEndingOnTheChosenDay() throws Exception {
    statsAnswers(STATS_OK);
    mvc.perform(signed(get("/api/v1/health/metrics?period=month&to=2026-10-28")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.from").value("2026-09-29"))
        .andExpect(jsonPath("$.data.points.length()").value(3));
  }

  @Test
  @Transactional
  void aWindowWithoutDataIsEmptyHasNoCommentAndDoesNotCallTheStatsService() throws Exception {
    mvc.perform(signed(get("/api/v1/health/metrics?period=week&to=2026-09-01")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.points.length()").value(0))
        .andExpect(jsonPath("$.data.insightsStatus").value("NO_DATA"))
        .andExpect(jsonPath("$.data.insights.length()").value(0))
        .andExpect(jsonPath("$.data.message", containsString("Chưa có dữ liệu")));
    verifyNoInteractions(stats);
  }

  @Test
  @Transactional
  void whenTheStatsServiceFailsTheChartStillWorksAndSaysSoInPlainWords() throws Exception {
    when(stats.post(anyString(), any(), eq(JsonNode.class))).thenThrow(new IllegalStateException("down"));
    mvc.perform(signed(get("/api/v1/health/metrics?period=week&to=2026-10-28")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.points.length()").value(3))
        .andExpect(jsonPath("$.data.insightsStatus").value("UNAVAILABLE"))
        .andExpect(jsonPath("$.data.insights.length()").value(0))
        .andExpect(jsonPath("$.data.message", containsString("thử lại")));
  }

  @Test
  @Transactional
  void anUnusableStatsAnswerIsTreatedAsUnavailable() throws Exception {
    statsAnswers("{\"data\":{}}");
    mvc.perform(signed(get("/api/v1/health/metrics?period=week&to=2026-10-28")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.insightsStatus").value("UNAVAILABLE"));
  }

  @Test
  @Transactional
  void badPeriodOrEndDayIsRejected() throws Exception {
    for (String query : List.of("period=year", "to=2026-13-40", "to=28/10/2026", "to=2999-01-01")) {
      mvc.perform(signed(get("/api/v1/health/metrics?" + query)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("HEALTH_METRICS_INVALID"));
    }
    verifyNoInteractions(stats);
  }

  @Test
  @Transactional
  void withoutConsentReadingAndSavingAreBlockedAndNothingChanges() throws Exception {
    withdrawConsent();
    mvc.perform(signed(get("/api/v1/health/metrics")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("HEALTH_CONSENT_REQUIRED"));
    mvc.perform(save("2026-11-01", "{\"weightKg\":70}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("HEALTH_CONSENT_REQUIRED"));
    assertThat(rows("")).isEqualTo(3L);
  }

  // ===== ghi nhận theo ngày =====

  @Test
  @Transactional
  void savingANewDayCreatesItAndAnswersWithTheStoredValues() throws Exception {
    mvc.perform(save("2026-11-01", "{\"weightKg\":70.5,\"sleepHours\":7}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.date").value("2026-11-01"))
        .andExpect(jsonPath("$.data.weightKg").value(70.5))
        .andExpect(jsonPath("$.data.sleepHours").value(7.0));
    assertThat(rows("AND recorded_on = DATE '2026-11-01'")).isEqualTo(1L);
  }

  @Test
  @Transactional
  void savingTheSameDayAgainUpdatesItWithoutDuplicatesAndKeepsTheMetricNotSent() throws Exception {
    mvc.perform(save("2026-11-01", "{\"weightKg\":70.5,\"sleepHours\":7}")).andExpect(status().isOk());
    mvc.perform(save("2026-11-01", "{\"sleepHours\":6}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.weightKg").value(70.5))
        .andExpect(jsonPath("$.data.sleepHours").value(6.0));
    mvc.perform(save("2026-11-01", "{\"sleepHours\":6}")).andExpect(status().isOk());
    assertThat(rows("AND recorded_on = DATE '2026-11-01'")).isEqualTo(1L);
    assertThat(rows("")).isEqualTo(4L);
  }

  @Test
  @Transactional
  void valuesOutsideTheRangesOrOfTheWrongTypeAreRejectedAndNothingChanges() throws Exception {
    List<String> bodies =
        List.of(
            "{}",
            "{\"weightKg\":19.9}",
            "{\"weightKg\":300.1}",
            "{\"sleepHours\":-0.5}",
            "{\"sleepHours\":24.5}",
            "{\"weightKg\":\"70\"}",
            "{\"sleepHours\":true}",
            "{\"weightKg\":null,\"sleepHours\":null}",
            "[]");
    for (String invalid : bodies) {
      mvc.perform(save("2026-11-01", invalid))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("HEALTH_METRICS_INVALID"));
    }
    assertThat(rows("")).isEqualTo(3L);
  }

  @Test
  @Transactional
  void aFutureOrMalformedDayIsRejected() throws Exception {
    for (String date : List.of("2999-01-01", "2026-02-30", "01-11-2026", "abc")) {
      mvc.perform(save(date, "{\"weightKg\":70}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("HEALTH_METRICS_INVALID"));
    }
    assertThat(rows("")).isEqualTo(3L);
  }

  @Test
  @Transactional
  void theErrorNamesTheFieldButNeverEchoesTheValue() throws Exception {
    mvc.perform(save("2026-11-01", "{\"weightKg\":98765}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message", containsString("weightKg")))
        .andExpect(jsonPath("$.error.message", not(containsString("98765"))));
  }

  // ===== xóa =====

  @Test
  @Transactional
  void deletingADayRemovesItAndDeletingItAgainIsNotAnError() throws Exception {
    mvc.perform(signed(delete("/api/v1/health/metrics/2026-10-27"))).andExpect(status().isOk());
    assertThat(rows("")).isEqualTo(2L);
    mvc.perform(signed(delete("/api/v1/health/metrics/2026-10-27"))).andExpect(status().isOk());
    assertThat(rows("")).isEqualTo(2L);
  }

  @Test
  @Transactional
  void deletingWorksWithoutConsentButNeedsAValidDay() throws Exception {
    withdrawConsent();
    mvc.perform(signed(delete("/api/v1/health/metrics/2026-10-26"))).andExpect(status().isOk());
    assertThat(rows("")).isEqualTo(2L);
    mvc.perform(signed(delete("/api/v1/health/metrics/not-a-date")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("HEALTH_METRICS_INVALID"));
  }

  @Test
  void metricsRoutesNeedASignedInUser() throws Exception {
    mvc.perform(get("/api/v1/health/metrics")).andExpect(status().isUnauthorized());
  }
}
