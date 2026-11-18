package vn.edu.uit.se214.health.health;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.shared.web.ApiResponse;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/**
 * M3 – UC-04, UC-05, UC-19. Profile and daily metrics stay here; weekly/monthly trends (UC-05)
 * and system statistics (UC-19) are computed by the Django service (services/stats).
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
  @GetMapping
  public ApiResponse.Success<ModuleDescriptor> module() {
    return ApiResponse.of(ModuleRegistry.get("health"));
  }

  @GetMapping("/profile")
  public Object profile() {
    throw ApiException.notImplemented();
  }

  @PutMapping("/profile")
  public Object saveProfile(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @DeleteMapping("/profile")
  public Object deleteProfile() {
    throw ApiException.notImplemented();
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
