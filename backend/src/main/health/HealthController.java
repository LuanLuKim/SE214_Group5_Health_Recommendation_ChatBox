package health;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shared.web.ApiException;
import shared.web.ApiResponse;
import shared.web.CurrentUser;
import system.ModuleDescriptor;
import system.ModuleRegistry;

/**
 * M3 – UC-04, UC-05, UC-19. Profile and daily metrics stay here; weekly/monthly trends (UC-05)
 * and system statistics (UC-19) are computed by the Django service (services/stats).
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
  private final Optional<HealthProfileService> profiles;
  private final Optional<HealthMetricsService> metrics;

  public HealthController(Optional<HealthProfileService> profiles, Optional<HealthMetricsService> metrics) {
    this.profiles = profiles;
    this.metrics = metrics;
  }

  private HealthMetricsService metricsService() {
    return metrics.orElseThrow(HealthErrors::unavailable);
  }

  private HealthProfileService profileService() {
    return profiles.orElseThrow(HealthErrors::unavailable);
  }

  @GetMapping
  public ApiResponse.Success<ModuleDescriptor> module() {
    return ApiResponse.of(ModuleRegistry.get("health"));
  }

  @GetMapping("/profile")
  public ApiResponse.Success<HealthProfileView> profile(@AuthenticationPrincipal Jwt principal) {
    HealthProfileService service = profileService();
    return ApiResponse.of(service.get(CurrentUser.id(principal)));
  }

  @PutMapping("/profile")
  public ApiResponse.Success<HealthProfileView> saveProfile(
      @AuthenticationPrincipal Jwt principal, @RequestBody(required = false) JsonNode body) {
    HealthProfileService service = profileService();
    return ApiResponse.of(service.save(CurrentUser.id(principal), body));
  }

  @DeleteMapping("/profile")
  public ApiResponse.Success<Void> deleteProfile(@AuthenticationPrincipal Jwt principal) {
    HealthProfileService service = profileService();
    service.delete(CurrentUser.id(principal));
    return ApiResponse.empty();
  }

  @GetMapping("/metrics")
  public ApiResponse.Success<HealthMetricsView> metrics(
      @AuthenticationPrincipal Jwt principal,
      @RequestParam(name = "period", required = false) String period,
      @RequestParam(name = "to", required = false) String to) {
    HealthMetricsService service = metricsService();
    return ApiResponse.of(service.range(CurrentUser.id(principal), period, to));
  }

  @PutMapping("/metrics/{date}")
  public ApiResponse.Success<HealthMetricPoint> saveMetric(
      @AuthenticationPrincipal Jwt principal,
      @PathVariable String date,
      @RequestBody(required = false) JsonNode body) {
    HealthMetricsService service = metricsService();
    return ApiResponse.of(service.save(CurrentUser.id(principal), date, body));
  }

  @DeleteMapping("/metrics/{date}")
  public ApiResponse.Success<Void> deleteMetric(
      @AuthenticationPrincipal Jwt principal, @PathVariable String date) {
    HealthMetricsService service = metricsService();
    service.delete(CurrentUser.id(principal), date);
    return ApiResponse.empty();
  }

  // GET /statistics (UC-19) is served by SystemStatisticsController.
}
