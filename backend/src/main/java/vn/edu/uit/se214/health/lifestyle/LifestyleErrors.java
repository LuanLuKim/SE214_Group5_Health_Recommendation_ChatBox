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

  /** UC-12 precondition: templates are filtered with the user's health data. */
  static ApiException consentRequired() {
    return new ApiException(
        HttpStatus.FORBIDDEN,
        "HEALTH_CONSENT_REQUIRED",
        "Bạn cần đồng ý cho phép xử lý dữ liệu sức khỏe trước khi chọn lộ trình sinh hoạt.");
  }

  static ApiException invalidGoal() {
    return ApiException.invalid(
        "INVALID_GOAL", "Mục tiêu phải là SLEEP, MAINTAIN, GAIN hoặc LOSE.");
  }

  static ApiException invalidTemplateId() {
    return ApiException.invalid("INVALID_TEMPLATE_ID", "Mã mẫu lộ trình không hợp lệ.");
  }

  /** Unknown, not approved or not offered here: the same answer, so drafts are never revealed. */
  static ApiException templateNotFound() {
    return new ApiException(
        HttpStatus.NOT_FOUND, "LIFESTYLE_TEMPLATE_NOT_FOUND", "Không tìm thấy mẫu lộ trình đã được duyệt.");
  }

  /** UC-12 A2: the template contains a declared allergen and cannot be applied. */
  static ApiException templateHasAllergen() {
    return new ApiException(
        HttpStatus.CONFLICT,
        "TEMPLATE_HAS_ALLERGEN",
        "Mẫu lộ trình này chứa tác nhân bạn đã khai báo dị ứng nên không thể áp dụng.");
  }

  /** UC-12 A1: this template needs a health profile. */
  static ApiException profileRequired() {
    return new ApiException(
        HttpStatus.CONFLICT,
        "HEALTH_PROFILE_REQUIRED",
        "Mẫu lộ trình này cần hồ sơ sức khỏe. Hãy cập nhật hồ sơ trước khi áp dụng.");
  }

  /** Two requests changed the plan at the same time; the client reloads and tries again. */
  static ApiException planConflict() {
    return new ApiException(
        HttpStatus.CONFLICT, "LIFESTYLE_PLAN_CONFLICT", "Lộ trình vừa được thay đổi ở nơi khác. Vui lòng thử lại.");
  }
}
