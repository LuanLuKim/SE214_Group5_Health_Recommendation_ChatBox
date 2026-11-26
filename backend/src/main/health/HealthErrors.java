package health;

import org.springframework.http.HttpStatus;
import shared.web.ApiException;

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

  /** UC-05 A1: names the invalid fields, never their values. */
  static ApiException metricsInvalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "HEALTH_METRICS_INVALID", message);
  }

  /** UC-05 save failed: a single upsert, so the day keeps its previous values. */
  static ApiException metricsSaveFailed() {
    return new ApiException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "HEALTH_METRICS_SAVE_FAILED",
        "Không lưu được chỉ số. Dữ liệu cũ vẫn được giữ nguyên, vui lòng thử lại.");
  }

  /** UC-05 A5: the chart cannot be built from the database. */
  static ApiException metricsReadFailed() {
    return new ApiException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "HEALTH_METRICS_READ_FAILED",
        "Không tải được chỉ số lúc này. Vui lòng thử lại.");
  }

  /** UC-05 delete failed: a single DELETE, so the day keeps its values. */
  static ApiException metricsDeleteFailed() {
    return new ApiException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "HEALTH_METRICS_DELETE_FAILED",
        "Không xóa được chỉ số. Dữ liệu vẫn được giữ nguyên, vui lòng thử lại.");
  }
}
