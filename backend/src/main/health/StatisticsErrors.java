package health;

import org.springframework.http.HttpStatus;
import shared.web.ApiException;

/** UC-19 error codes. */
final class StatisticsErrors {
  private StatisticsErrors() {}

  /** No database (DATA_MODE=mock): there are no events to count. */
  static ApiException notAvailableInMockMode() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "HEALTH_STATS_UNAVAILABLE",
        "Thống kê hệ thống chưa khả dụng trong chế độ không có cơ sở dữ liệu.");
  }

  /** UC-19 A2: the date range is malformed, reversed or too wide. */
  static ApiException rangeInvalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "HEALTH_STATS_RANGE_INVALID", message);
  }

  /** UC-19 A4: reading or aggregating failed; the client shows this message, not a blank page. */
  static ApiException aggregationFailed() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "HEALTH_STATS_UNAVAILABLE",
        "Chưa tổng hợp được thống kê. Vui lòng thử lại sau.");
  }
}
