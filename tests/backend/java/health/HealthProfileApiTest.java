package health;

import system.HealthAssistantApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import accounts.auth.AccessTokenService;
import support.TestDatabase;

/**
 * UC-04 over HTTP in DATA_MODE=postgres with synthetic seeding: the seeded user has consent, a
 * 170 cm / 24 y / UNSPECIFIED profile and weights of 65, 65.2 and 65.1 kg (latest 65.1).
 */
@SpringBootTest(classes = HealthAssistantApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class HealthProfileApiTest {
  static final UUID SEEDED_USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final String VALID =
      "{\"heightCm\":180,\"weightKg\":75,\"age\":30,\"sex\":\"MALE\",\"foodAllergies\":[\"Đậu phộng\",\" tôm \",\"TÔM\"]}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired AccessTokenService tokens;

  String csrf;
  Cookie access;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (Connection c = TestDatabase.server().getPostgresDatabase().getConnection();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE health_profile_api");
    }
    registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl("health_profile_api"));
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

  MockHttpServletRequestBuilder save(String body) {
    return signed(put("/api/v1/health/profile")).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  void withdrawConsent() {
    assertThat(
            jdbc.sql("UPDATE accounts.consents SET health_data_processing_accepted = false WHERE user_id = ?")
                .param(SEEDED_USER)
                .update())
        .isPositive();
  }

  Double storedHeight() {
    return jdbc.sql("SELECT height_cm FROM health.profiles WHERE user_id = ?")
        .param(SEEDED_USER)
        .query(Double.class)
        .single();
  }

  @Test
  void profileRoutesNeedASignedInUser() throws Exception {
    mvc.perform(get("/api/v1/health/profile")).andExpect(status().isUnauthorized());
  }

  @Test
  @Transactional
  void readsTheProfileWithBmiFromTheLatestWeight() throws Exception {
    mvc.perform(signed(get("/api/v1/health/profile")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.heightCm").value(170.0))
        .andExpect(jsonPath("$.data.weightKg").value(65.1))
        .andExpect(jsonPath("$.data.age").value(24))
        .andExpect(jsonPath("$.data.sex").value("UNSPECIFIED"))
        .andExpect(jsonPath("$.data.foodAllergies.length()").value(0))
        .andExpect(jsonPath("$.data.bmi").value(22.53));
  }

  @Test
  @Transactional
  void withoutConsentReadAndSaveAreBlockedAndNothingChanges() throws Exception {
    withdrawConsent();
    mvc.perform(signed(get("/api/v1/health/profile")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("HEALTH_CONSENT_REQUIRED"));
    mvc.perform(save(VALID))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("HEALTH_CONSENT_REQUIRED"));
    assertThat(storedHeight()).isEqualTo(170.0);
  }

  @Test
  @Transactional
  void savesTheProfileAndTodaysWeightAndAnswersWithTheStoredValues() throws Exception {
    mvc.perform(save(VALID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.heightCm").value(180.0))
        .andExpect(jsonPath("$.data.weightKg").value(75.0))
        .andExpect(jsonPath("$.data.age").value(30))
        .andExpect(jsonPath("$.data.sex").value("MALE"))
        .andExpect(jsonPath("$.data.foodAllergies.length()").value(2))
        .andExpect(jsonPath("$.data.foodAllergies[0]").value("đậu phộng"))
        .andExpect(jsonPath("$.data.foodAllergies[1]").value("tôm"))
        .andExpect(jsonPath("$.data.bmi").value(23.15));
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    assertThat(
            jdbc.sql("SELECT weight_kg FROM health.daily_metrics WHERE user_id = ? AND recorded_on = ?")
                .params(SEEDED_USER, today)
                .query(Double.class)
                .single())
        .isEqualTo(75.0);
  }

  @Test
  @Transactional
  void savingTwiceKeepsOneProfileAndKeepsTodaysSleepHours() throws Exception {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    jdbc.sql("INSERT INTO health.daily_metrics (user_id, recorded_on, sleep_hours) VALUES (?, ?, 8)")
        .params(SEEDED_USER, today)
        .update();
    mvc.perform(save(VALID)).andExpect(status().isOk());
    mvc.perform(save(VALID)).andExpect(status().isOk());
    assertThat(jdbc.sql("SELECT count(*) FROM health.profiles WHERE user_id = ?").param(SEEDED_USER).query(Long.class).single())
        .isEqualTo(1L);
    assertThat(
            jdbc.sql("SELECT count(*) FROM health.daily_metrics WHERE user_id = ? AND recorded_on = ?")
                .params(SEEDED_USER, today)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    assertThat(
            jdbc.sql("SELECT sleep_hours FROM health.daily_metrics WHERE user_id = ? AND recorded_on = ?")
                .params(SEEDED_USER, today)
                .query(Double.class)
                .single())
        .isEqualTo(8.0);
  }

  static String body(String height, String weight, String age, String sex) {
    return "{\"heightCm\":" + height + ",\"weightKg\":" + weight + ",\"age\":" + age + ",\"sex\":" + sex + "}";
  }

  @Test
  @Transactional
  void valuesOutsideTheRangesOrOfTheWrongTypeAreRejectedAndNothingChanges() throws Exception {
    String manyAllergies =
        IntStream.range(0, 21).mapToObj(i -> "\"a" + i + "\"").collect(Collectors.joining(",", "[", "]"));
    List<String> bodies =
        List.of(
            body("49", "70", "30", "\"MALE\""),
            body("251", "70", "30", "\"MALE\""),
            body("170", "19", "30", "\"MALE\""),
            body("170", "301", "30", "\"MALE\""),
            body("170", "70", "17", "\"MALE\""),
            body("170", "70", "121", "\"MALE\""),
            body("170", "70", "30.5", "\"MALE\""),
            body("170", "70", "30", "\"ROBOT\""),
            body("\"170\"", "70", "30", "\"MALE\""),
            "{\"heightCm\":170,\"weightKg\":70,\"age\":30}",
            body("170", "70", "30", "\"MALE\"").replace("}", ",\"foodAllergies\":\"tôm\"}"),
            body("170", "70", "30", "\"MALE\"").replace("}", ",\"foodAllergies\":" + manyAllergies + "}"),
            "[]");
    for (String invalid : bodies) {
      mvc.perform(save(invalid))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("HEALTH_PROFILE_INVALID"));
    }
    assertThat(storedHeight()).isEqualTo(170.0);
  }

  @Test
  @Transactional
  void theErrorNamesEveryFieldThatIsMissingButNeverEchoesValues() throws Exception {
    mvc.perform(save("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message", containsString("heightCm")))
        .andExpect(jsonPath("$.error.message", containsString("weightKg")))
        .andExpect(jsonPath("$.error.message", containsString("age")))
        .andExpect(jsonPath("$.error.message", containsString("sex")));
    mvc.perform(save(body("12345", "70", "30", "\"MALE\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message", not(containsString("12345"))));
  }

  @Test
  @Transactional
  void deletingRemovesTheProfileKeepsDailyMetricsAndWorksWithoutConsent() throws Exception {
    withdrawConsent();
    mvc.perform(signed(delete("/api/v1/health/profile"))).andExpect(status().isOk());
    assertThat(jdbc.sql("SELECT count(*) FROM health.profiles WHERE user_id = ?").param(SEEDED_USER).query(Long.class).single())
        .isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM health.daily_metrics WHERE user_id = ?").param(SEEDED_USER).query(Long.class).single())
        .isEqualTo(3L);
  }

  @Test
  @Transactional
  void withoutAProfileTheFormIsEmptyAndThereIsNoBmi() throws Exception {
    mvc.perform(signed(delete("/api/v1/health/profile"))).andExpect(status().isOk());
    mvc.perform(signed(get("/api/v1/health/profile")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.heightCm").value(nullValue()))
        .andExpect(jsonPath("$.data.age").value(nullValue()))
        .andExpect(jsonPath("$.data.foodAllergies.length()").value(0))
        .andExpect(jsonPath("$.data.bmi").value(nullValue()));
  }
}
