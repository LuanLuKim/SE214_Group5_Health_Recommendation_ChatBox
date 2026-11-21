package vn.edu.uit.se214.health.health;

import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uit.se214.health.shared.web.ApiResponse;

/**
 * M3 – UC-19 System statistics. Only roles with PERM_SYSTEM_STATS_VIEW (ADMIN) reach this route
 * (SpringSecurityConfiguration); the answer holds aggregate counts only.
 */
@RestController
@RequestMapping("/api/v1/health")
public class SystemStatisticsController {
  private final Optional<SystemStatisticsService> statistics;

  public SystemStatisticsController(Optional<SystemStatisticsService> statistics) {
    this.statistics = statistics;
  }

  /** {@code from}/{@code to} are yyyy-MM-dd, both optional (default: the last days ending today). */
  @GetMapping("/statistics")
  public ApiResponse.Success<SystemStatisticsView> statistics(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    SystemStatisticsService service = statistics.orElseThrow(StatisticsErrors::notAvailableInMockMode);
    return ApiResponse.of(service.statistics(from, to));
  }
}
