package vn.edu.uit.se214.health.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import vn.edu.uit.se214.health.accounts.api.ConsentQuery;
import vn.edu.uit.se214.health.integrations.PythonServiceClient;
import vn.edu.uit.se214.health.shared.web.ApiException;

/**
 * UC-05 error handling without Spring or a database: repository, consent and services/stats are
 * mocks. Only a stats outage may become UNAVAILABLE (A5); a programming error must surface, and a
 * database failure on delete has its own code like save and read.
 */
class HealthMetricsServiceTest {
  static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final String TRENDS_PATH = "/v1/metrics/trends";

  HealthMetricsRepository repository;
  PythonServiceClient stats;
  HealthMetricsService service;

  @BeforeEach
  void setUp() {
    repository = mock(HealthMetricsRepository.class);
    stats = mock(PythonServiceClient.class);
    ConsentQuery consents = mock(ConsentQuery.class);
    when(consents.healthDataProcessingAccepted(any())).thenReturn(true);
    when(repository.range(any(), any(), any()))
        .thenReturn(List.of(new HealthMetricPoint(LocalDate.of(2026, 11, 20), 65.1, 7.0)));
    service =
        new HealthMetricsService(
            repository, consents, stats, Clock.fixed(Instant.parse("2026-11-21T05:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void aRestClientFailureFromTheStatsServiceBecomesUnavailableButKeepsThePoints() {
    when(stats.post(eq(TRENDS_PATH), any(), eq(JsonNode.class))).thenThrow(new ResourceAccessException("timeout"));

    HealthMetricsView view = service.range(USER, "week", null);

    assertThat(view.insightsStatus()).isEqualTo("UNAVAILABLE");
    assertThat(view.points()).hasSize(1);
    assertThat(view.message()).contains("thử lại");
  }

  @Test
  void aStatsServiceThatIsNotConfiguredBecomesUnavailable() {
    when(stats.post(anyString(), any(), eq(JsonNode.class))).thenThrow(new IllegalStateException("SERVICE_NOT_CONFIGURED"));

    assertThat(service.range(USER, "week", null).insightsStatus()).isEqualTo("UNAVAILABLE");
  }

  @Test
  void aProgrammingErrorIsNotHiddenAsUnavailable() {
    when(stats.post(anyString(), any(), eq(JsonNode.class))).thenThrow(new NullPointerException("bug"));
    assertThatThrownBy(() -> service.range(USER, "week", null)).isInstanceOf(NullPointerException.class);

    when(stats.post(anyString(), any(), eq(JsonNode.class))).thenThrow(new IllegalArgumentException("bug"));
    assertThatThrownBy(() -> service.range(USER, "week", null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void restClientExceptionIsTheKindOfFailureThatIsCaught() {
    assertThat(RestClientException.class).isAssignableFrom(ResourceAccessException.class);
  }

  @Test
  void aDatabaseFailureOnDeleteHasItsOwnCode() {
    doThrow(new DataAccessResourceFailureException("db down")).when(repository).delete(any(), any());

    assertThatThrownBy(() -> service.delete(USER, "2026-11-20"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
              assertThat(e.code()).isEqualTo("HEALTH_METRICS_DELETE_FAILED");
              assertThat(e.getMessage()).contains("vẫn được giữ nguyên");
            });
  }

  @Test
  void aBadDateOnDeleteIsStillAnInvalidRequestNotAFailure() {
    assertThatThrownBy(() -> service.delete(USER, "not-a-date"))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("HEALTH_METRICS_INVALID"));
  }
}
