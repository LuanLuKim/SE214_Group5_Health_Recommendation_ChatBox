package vn.edu.uit.se214.health.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.ResourceAccessException;
import vn.edu.uit.se214.health.accounts.auth.AccessTokenService;
import vn.edu.uit.se214.health.health.SystemStatisticsService.DayRow;
import vn.edu.uit.se214.health.health.SystemStatisticsService.StatsReply;
import vn.edu.uit.se214.health.health.SystemStatisticsService.StatsRequest;
import vn.edu.uit.se214.health.health.SystemStatisticsView.Counts;
import vn.edu.uit.se214.health.health.SystemStatisticsView.DayStats;
import vn.edu.uit.se214.health.health.SystemStatisticsView.Metric;
import vn.edu.uit.se214.health.health.api.UsageEventRecorder;
import vn.edu.uit.se214.health.health.api.UsageEventType;
import vn.edu.uit.se214.health.integrations.PythonServiceClient;
import vn.edu.uit.se214.health.support.TestDatabase;

/**
 * UC-19 and SCRUM-51 over HTTP in DATA_MODE=postgres. The Django service is replaced by a mock;
 * its own computation is tested in services/stats.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class SystemStatisticsApiTest {
  static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-000000000003");
  static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired AccessTokenService tokens;
  @Autowired UsageEventRecorder recorder;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired ObjectMapper json;
  @MockitoBean(name = "statsService") PythonServiceClient stats;

  String csrf;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (Connection c = TestDatabase.server().getPostgresDatabase().getConnection();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE system_statistics_api");
    }
    registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl("system_statistics_api"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.data-mode", () -> "postgres");
    registry.add("app.seed-synthetic", () -> "true");
    registry.add("app.accounts.auth-token.secret", () -> "test-only-auth-token-secret-at-least-32-bytes");
  }

  @BeforeEach
  void clean() throws Exception {
    jdbc.sql("DELETE FROM health.usage_events").update();
    csrf = cookie(mvc.perform(get("/api/v1/csrf")).andReturn(), "XSRF-TOKEN");
  }

  static String cookie(MvcResult result, String name) {
    return result.getResponse().getHeaders("Set-Cookie").stream()
        .filter(value -> value.startsWith(name + "="))
        .map(value -> value.substring(name.length() + 1, value.indexOf(';')))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing " + name + " cookie"));
  }

  MockHttpServletRequestBuilder as(UUID user, MockHttpServletRequestBuilder request) {
    Cookie access = new Cookie("HA_ACCESS", tokens.issue(user, Instant.now().plus(10, ChronoUnit.MINUTES)));
    return request.cookie(access, new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf);
  }

  void event(UsageEventType type, String utc) {
    jdbc.sql("INSERT INTO health.usage_events (id, event_type, occurred_at) VALUES (?, ?, ?)")
        .param(UUID.randomUUID())
        .param(type.name())
        .param(OffsetDateTime.parse(utc))
        .update();
  }

  long events(UsageEventType type) {
    return jdbc.sql("SELECT count(*) FROM health.usage_events WHERE event_type = ?")
        .param(type.name())
        .query(Long.class)
        .single();
  }

  static StatsReply reply(LocalDate from, LocalDate to) {
    return new StatsReply(
        new SystemStatisticsView(
            from,
            to,
            null,
            true,
            new Counts(1, 2, 1),
            List.of(new DayStats(from, 1, 2, 0)),
            List.of(new Metric("visits", "Lượt truy cập", "lượt")),
            null));
  }

  @Test
  void onlyAnAdminSeesTheStatistics() throws Exception {
    mvc.perform(get("/api/v1/health/statistics")).andExpect(status().isUnauthorized());
    mvc.perform(as(USER, get("/api/v1/health/statistics")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    verifyNoInteractions(stats);
  }

  @Test
  void countsEventsPerVietnameseDayAndSendsOnlyDatesAndCounts() throws Exception {
    LocalDate from = LocalDate.parse("2026-11-01");
    LocalDate to = LocalDate.parse("2026-11-03");
    event(UsageEventType.VISIT, "2026-10-31T16:59:59Z"); // 31/10 23:59 in Vietnam: before the range
    event(UsageEventType.REGISTRATION, "2026-11-01T03:00:00Z");
    event(UsageEventType.VISIT, "2026-11-01T04:00:00Z");
    event(UsageEventType.VISIT, "2026-11-01T05:00:00Z");
    event(UsageEventType.CHAT_INTERACTION, "2026-11-01T17:30:00Z"); // 2/11 00:30 in Vietnam
    event(UsageEventType.VISIT, "2026-11-03T17:00:00Z"); // 4/11 00:00 in Vietnam: after the range
    ArgumentCaptor<StatsRequest> sent = ArgumentCaptor.forClass(StatsRequest.class);
    when(stats.post(eq(SystemStatisticsService.STATS_PATH), sent.capture(), eq(StatsReply.class)))
        .thenReturn(reply(from, to));

    mvc.perform(as(ADMIN, get("/api/v1/health/statistics").param("from", "2026-11-01").param("to", "2026-11-03")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.from").value("2026-11-01"))
        .andExpect(jsonPath("$.data.to").value("2026-11-03"))
        .andExpect(jsonPath("$.data.timezone").value("Asia/Ho_Chi_Minh"))
        .andExpect(jsonPath("$.data.hasData").value(true))
        .andExpect(jsonPath("$.data.totals.visits").value(2))
        .andExpect(jsonPath("$.data.series[0].date").value("2026-11-01"))
        .andExpect(jsonPath("$.data.metrics[0].label").value("Lượt truy cập"));

    StatsRequest request = sent.getValue();
    assertThat(request.from()).isEqualTo(from);
    assertThat(request.to()).isEqualTo(to);
    assertThat(request.days())
        .containsExactly(
            new DayRow(LocalDate.parse("2026-11-01"), 1, 2, 0),
            new DayRow(LocalDate.parse("2026-11-02"), 0, 0, 1));
    assertThat(json.readTree(json.writeValueAsString(request)).path("days").get(0).fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("date", "newAccounts", "visits", "chatInteractions");
  }

  @Test
  void withoutARangeShowsTheLastThirtyDaysEndingToday() throws Exception {
    LocalDate today = LocalDate.now(VIETNAM);
    ArgumentCaptor<StatsRequest> sent = ArgumentCaptor.forClass(StatsRequest.class);
    when(stats.post(eq(SystemStatisticsService.STATS_PATH), sent.capture(), eq(StatsReply.class)))
        .thenReturn(reply(today.minusDays(29), today));

    mvc.perform(as(ADMIN, get("/api/v1/health/statistics"))).andExpect(status().isOk());

    assertThat(sent.getValue().to()).isEqualTo(today);
    assertThat(sent.getValue().from()).isEqualTo(today.minusDays(29));
    assertThat(sent.getValue().days()).isEmpty();
  }

  @Test
  void rejectsAMalformedReversedOrTooWideRangeWithoutCallingTheService() throws Exception {
    String[][] ranges = {
      {"2026-11-05", "2026-11-01"},
      {"2026/11/01", "2026-11-03"},
      {"2026-02-30", "2026-03-01"},
      {"2025-01-01", "2026-01-02"},
    };
    for (String[] range : ranges) {
      mvc.perform(as(ADMIN, get("/api/v1/health/statistics").param("from", range[0]).param("to", range[1])))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("HEALTH_STATS_RANGE_INVALID"));
    }
    verifyNoInteractions(stats);
  }

  @Test
  void aFailingStatsServiceGivesAClearMessage() throws Exception {
    when(stats.post(eq(SystemStatisticsService.STATS_PATH), any(), eq(StatsReply.class)))
        .thenThrow(new ResourceAccessException("down"));

    mvc.perform(as(ADMIN, get("/api/v1/health/statistics").param("from", "2026-11-01").param("to", "2026-11-03")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("HEALTH_STATS_UNAVAILABLE"))
        .andExpect(jsonPath("$.error.message").value("Chưa tổng hợp được thống kê. Vui lòng thử lại sau."));
    verify(stats).post(eq(SystemStatisticsService.STATS_PATH), any(), eq(StatsReply.class));
  }

  @Test
  void aSuccessfulSignInCountsOneVisitAndAFailedOneCountsNothing() throws Exception {
    jdbc.sql(
            """
            INSERT INTO accounts.users (id, email, display_name, password_hash, status, created_at)
            VALUES (?, 'uc19-visit@example.test', 'Visit test', ?, 'ACTIVE', now())
            """)
        .param(UUID.randomUUID())
        .param(passwordEncoder.encode("correct-password"))
        .update();

    mvc.perform(login("uc19-visit@example.test", "wrong-password")).andExpect(status().isUnauthorized());
    assertThat(events(UsageEventType.VISIT)).isZero();

    mvc.perform(login("uc19-visit@example.test", "correct-password")).andExpect(status().isOk());
    assertThat(events(UsageEventType.VISIT)).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM health.usage_events").query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void theRecorderStoresOnlyTheKindAndTheTime() {
    recorder.record(UsageEventType.CHAT_INTERACTION);
    assertThat(events(UsageEventType.CHAT_INTERACTION)).isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'health' AND table_name = 'usage_events' ORDER BY column_name")
                .query(String.class)
                .list())
        .containsExactly("event_type", "id", "occurred_at");
  }

  MockHttpServletRequestBuilder login(String email, String password) {
    return post("/api/v1/accounts/login")
        .cookie(new Cookie("XSRF-TOKEN", csrf))
        .header("X-XSRF-TOKEN", csrf)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
  }
}
