package vn.edu.uit.se214.health.accounts;

import org.springframework.http.HttpStatus;
import vn.edu.uit.se214.health.shared.web.ApiException;

/** Client-safe errors specific to account and registration operations. */
public class AccountApiException extends ApiException {
  public AccountApiException(HttpStatus status, String code, String message) {
    super(status, code, message);
  }

  public static AccountApiException emailInvalid() {
    return invalidRequest("INVALID_EMAIL", "Địa chỉ email không hợp lệ.");
  }

  public static AccountApiException passwordInvalid() {
    return invalidRequest("INVALID_PASSWORD", "Mật khẩu phải có từ 8 đến 72 byte UTF-8.");
  }

  public static AccountApiException currentPasswordInvalid() {
    return invalidRequest("CURRENT_PASSWORD_INVALID", "Mật khẩu hiện tại không chính xác.");
  }

  public static AccountApiException passwordChangeUnavailable() {
    return new AccountApiException(
        HttpStatus.CONFLICT,
        "PASSWORD_CHANGE_UNAVAILABLE",
        "Tài khoản này chưa thiết lập mật khẩu đăng nhập.");
  }

  public static AccountApiException profileInvalid() {
    return invalidRequest("INVALID_PROFILE", "Thông tin cá nhân không hợp lệ.");
  }

  public static AccountApiException registrationFieldInvalid() {
    return invalidRequest("INVALID_REGISTRATION", "Thông tin đăng ký không hợp lệ.");
  }

  public static AccountApiException consentRequired() {
    return invalidRequest("CONSENT_REQUIRED", "Bạn cần đồng ý các điều khoản và xác nhận bắt buộc.");
  }

  public static AccountApiException adultConfirmationRequired() {
    return invalidRequest("ADULT_CONFIRMATION_REQUIRED", "Chỉ người từ đủ 18 tuổi mới được đăng ký.");
  }

  public static AccountApiException termsVersionInvalid() {
    return invalidRequest(
        "TERMS_VERSION_INVALID", "Phiên bản điều khoản không hợp lệ hoặc đã hết hiệu lực.");
  }

  public static AccountApiException oauthProviderUnsupported() {
    return invalidRequest("OAUTH_PROVIDER_UNSUPPORTED", "Nhà cung cấp đăng nhập không được hỗ trợ.");
  }

  public static AccountApiException oauthVerificationFailed() {
    return invalidRequest("OAUTH_VERIFICATION_FAILED", "Không thể xác minh tài khoản OAuth.");
  }

  public static AccountApiException emailAlreadyRegistered() {
    return new AccountApiException(
        HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email đã được đăng ký.");
  }

  public static AccountApiException oauthIdentityAlreadyRegistered() {
    return new AccountApiException(
        HttpStatus.CONFLICT, "OAUTH_IDENTITY_ALREADY_REGISTERED", "Tài khoản OAuth đã được đăng ký.");
  }

  public static AccountApiException oauthProviderUnavailable() {
    return new AccountApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "OAUTH_PROVIDER_UNAVAILABLE",
        "Nhà cung cấp đăng nhập tạm thời không khả dụng.");
  }

  public static AccountApiException registrationUnavailable() {
    return new AccountApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "REGISTRATION_UNAVAILABLE",
        "Đăng ký tài khoản chưa khả dụng trong chế độ không có cơ sở dữ liệu.");
  }

  public static AccountApiException emailVerificationUnavailable() {
    return new AccountApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "EMAIL_VERIFICATION_UNAVAILABLE",
        "Dịch vụ xác minh email chưa được cấu hình.");
  }

  public static AccountApiException emailDeliveryFailed() {
    return new AccountApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "EMAIL_DELIVERY_FAILED",
        "Không gửi được mã xác nhận. Vui lòng thử lại sau.");
  }

  public static AccountApiException registrationAlreadyPending() {
    return new AccountApiException(
        HttpStatus.CONFLICT,
        "REGISTRATION_ALREADY_PENDING",
        "Email đang chờ xác nhận. Hãy dùng chức năng gửi lại mã.");
  }

  public static AccountApiException registrationChallengeNotFound() {
    return new AccountApiException(
        HttpStatus.NOT_FOUND,
        "REGISTRATION_CHALLENGE_NOT_FOUND",
        "Không tìm thấy yêu cầu đăng ký cần xác nhận.");
  }

  public static AccountApiException otpInvalid() {
    return invalidRequest("OTP_INVALID", "Mã xác nhận không đúng.");
  }

  public static AccountApiException otpExpired() {
    return invalidRequest("OTP_EXPIRED", "Mã xác nhận đã hết hạn. Hãy bắt đầu đăng ký lại.");
  }

  public static AccountApiException otpAttemptsExhausted() {
    return new AccountApiException(
        HttpStatus.TOO_MANY_REQUESTS,
        "OTP_ATTEMPTS_EXHAUSTED",
        "Bạn đã nhập sai quá số lần cho phép. Hãy yêu cầu mã mới.");
  }

  public static AccountApiException otpResendTooSoon() {
    return new AccountApiException(
        HttpStatus.TOO_MANY_REQUESTS,
        "OTP_RESEND_TOO_SOON",
        "Vui lòng đợi trước khi yêu cầu gửi lại mã.");
  }

  public static AccountApiException otpResendLimitReached() {
    return new AccountApiException(
        HttpStatus.TOO_MANY_REQUESTS,
        "OTP_RESEND_LIMIT_REACHED",
        "Bạn đã yêu cầu gửi lại mã quá số lần cho phép. Hãy bắt đầu đăng ký lại.");
  }

  private static AccountApiException invalidRequest(String code, String message) {
    return new AccountApiException(HttpStatus.BAD_REQUEST, code, message);
  }
}
