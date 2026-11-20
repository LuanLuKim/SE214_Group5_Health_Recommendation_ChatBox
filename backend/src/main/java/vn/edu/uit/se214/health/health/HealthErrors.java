package vn.edu.uit.se214.health.health;

import org.springframework.http.HttpStatus;
import vn.edu.uit.se214.health.shared.web.ApiException;

/** M3 error codes. Messages never contain the submitted health values (NFR-08). */
final class HealthErrors {
  private HealthErrors() {}

  static ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "HEALTH_UNAVAILABLE",
        "Hồ sơ sức khỏe chưa khả dụng trong chế độ không có cơ sở dữ liệu.");
  }

  /** UC-04 A3: names the invalid or missing fields, never their values. */
  static ApiException profileInvalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "HEALTH_PROFILE_INVALID", message);
  }

  /** UC-04 A4: the write was rolled back, so the previous profile is untouched. */
  static ApiException saveFailed() {
    return new ApiException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "HEALTH_PROFILE_SAVE_FAILED",
        "Không lưu được hồ sơ. Dữ liệu cũ vẫn được giữ nguyên, vui lòng thử lại.");
  }
}
