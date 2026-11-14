package vn.edu.uit.se214.health.lifestyle;

import org.springframework.http.HttpStatus;
import vn.edu.uit.se214.health.shared.web.ApiException;

/** M6 error codes. Kept in this module so shared/web does not grow with every module's errors. */
final class LifestyleErrors {
  private LifestyleErrors() {}

  static ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "LIFESTYLE_UNAVAILABLE",
        "Lộ trình sinh hoạt chưa khả dụng trong chế độ không có cơ sở dữ liệu.");
  }

  /** UC-13 A1: no plan yet, the client should send the user to UC-12. */
  static ApiException planNotFound() {
    return new ApiException(
        HttpStatus.NOT_FOUND,
        "LIFESTYLE_PLAN_NOT_FOUND",
        "Bạn chưa áp dụng lộ trình sinh hoạt nào. Hãy chọn một lộ trình trước.");
  }

  static ApiException activityNotInPlan() {
    return new ApiException(
        HttpStatus.NOT_FOUND, "ACTIVITY_NOT_IN_PLAN", "Hoạt động không thuộc lộ trình đang áp dụng.");
  }

  static ApiException invalidDate() {
    return ApiException.invalid(
        "INVALID_COMPLETION_DATE",
        "Ngày không hợp lệ: phải theo dạng yyyy-mm-dd, không ở tương lai và không trước ngày bắt đầu lộ trình.");
  }

  static ApiException completionLocked() {
    return new ApiException(
        HttpStatus.CONFLICT, "COMPLETION_LOCKED", "Chỉ bỏ đánh dấu được hoạt động của hôm nay.");
  }
}
