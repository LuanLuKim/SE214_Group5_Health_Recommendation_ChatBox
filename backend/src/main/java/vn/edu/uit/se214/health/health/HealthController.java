package vn.edu.uit.se214.health.health;

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
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.shared.web.ApiResponse;
import vn.edu.uit.se214.health.shared.web.CurrentUser;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/**
 * M3 – UC-04, UC-05, UC-19. Profile and daily metrics stay here; weekly/monthly trends (UC-05)
 * and system statistics (UC-19) are computed by the Django service (services/stats).
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
  private final Optional<HealthProfileService> profiles;

  public HealthController(Optional<HealthProfileService> profiles) {
    this.profiles = profiles;
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
  public Object metrics() {
    throw ApiException.notImplemented();
  }

  @PutMapping("/metrics/{date}")
  public Object saveMetric(@PathVariable String date, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @DeleteMapping("/metrics/{date}")
  public Object deleteMetric(@PathVariable String date) {
    throw ApiException.notImplemented();
  }

  @GetMapping("/statistics")
  public Object statistics() {
    throw ApiException.notImplemented();
  }
}
