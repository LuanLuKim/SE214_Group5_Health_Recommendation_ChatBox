package vn.edu.uit.se214.health.shared.web;

import org.springframework.http.HttpStatus;

/**
 * An error whose code and Vietnamese message are safe to show to the client.
 * Never put request bodies, health data, prompts or credentials in the message.
 */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  /** Private routes are unavailable in mock mode because no authentication provider is configured. */
  public static ApiException authNotConfigured() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "AUTH_NOT_CONFIGURED",
        "Chức năng tài khoản chưa được triển khai trong bản khung.");
  }

  public static ApiException notImplemented() {
    return new ApiException(
        HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", "Chức năng này đang chờ triển khai.");
  }

  public static ApiException emailInvalid() {
    return invalid("INVALID_EMAIL", "Địa chỉ email không hợp lệ.");
  }

  public static ApiException passwordInvalid() {
    return invalid("INVALID_PASSWORD", "Mật khẩu phải có từ 8 đến 72 byte UTF-8.");
  }

  public static ApiException registrationFieldInvalid() {
    return invalid("INVALID_REGISTRATION", "Thông tin đăng ký không hợp lệ.");
  }

  public static ApiException consentRequired() {
    return invalid("CONSENT_REQUIRED", "Bạn cần đồng ý các điều khoản và xác nhận bắt buộc.");
  }

  public static ApiException adultConfirmationRequired() {
    return invalid("ADULT_CONFIRMATION_REQUIRED", "Chỉ người từ đủ 18 tuổi mới được đăng ký.");
  }

  public static ApiException termsVersionInvalid() {
    return invalid("TERMS_VERSION_INVALID", "Phiên bản điều khoản không hợp lệ hoặc đã hết hiệu lực.");
  }

  public static ApiException oauthProviderUnsupported() {
    return invalid("OAUTH_PROVIDER_UNSUPPORTED", "Nhà cung cấp đăng nhập không được hỗ trợ.");
  }

  public static ApiException oauthVerificationFailed() {
    return invalid("OAUTH_VERIFICATION_FAILED", "Không thể xác minh tài khoản OAuth.");
  }

  public static ApiException emailAlreadyRegistered() {
    return new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email đã được đăng ký.");
  }

  public static ApiException oauthIdentityAlreadyRegistered() {
    return new ApiException(
        HttpStatus.CONFLICT, "OAUTH_IDENTITY_ALREADY_REGISTERED", "Tài khoản OAuth đã được đăng ký.");
  }

  public static ApiException oauthProviderUnavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "OAUTH_PROVIDER_UNAVAILABLE",
        "Nhà cung cấp đăng nhập tạm thời không khả dụng.");
  }

  public static ApiException registrationUnavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "REGISTRATION_UNAVAILABLE",
        "Đăng ký tài khoản chưa khả dụng trong chế độ không có cơ sở dữ liệu.");
  }

  public static ApiException authenticationUnavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "AUTHENTICATION_UNAVAILABLE",
        "Chức năng xác thực chưa khả dụng trong chế độ không có cơ sở dữ liệu.");
  }

  public static ApiException loginFailed() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED,
        "LOGIN_FAILED",
        "Thông tin đăng nhập không hợp lệ hoặc tài khoản tạm thời không khả dụng.");
  }

  public static ApiException sessionInvalid() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
  }

  public static ApiException refreshTokenInvalid() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED,
        "REFRESH_TOKEN_INVALID",
        "Refresh token không hợp lệ, đã hết hạn hoặc đã bị thu hồi.");
  }

  public static ApiException recoveryCodeInvalid() {
    return invalid("RECOVERY_CODE_INVALID", "Mã khôi phục không hợp lệ hoặc đã hết hạn.");
  }

  public static ApiException passwordRecoveryRequestInvalid() {
    return invalid("PASSWORD_RECOVERY_REQUEST_INVALID", "Yêu cầu khôi phục mật khẩu không hợp lệ.");
  }

  public static ApiException emailVerificationUnavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "EMAIL_VERIFICATION_UNAVAILABLE",
        "Dịch vụ xác minh email chưa được cấu hình.");
  }

  public static ApiException emailDeliveryFailed() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "EMAIL_DELIVERY_FAILED",
        "Không gửi được mã xác nhận. Vui lòng thử lại sau.");
  }

  public static ApiException registrationAlreadyPending() {
    return new ApiException(
        HttpStatus.CONFLICT,
        "REGISTRATION_ALREADY_PENDING",
        "Email đang chờ xác nhận. Hãy dùng chức năng gửi lại mã.");
  }

  public static ApiException registrationChallengeNotFound() {
    return new ApiException(
        HttpStatus.NOT_FOUND,
        "REGISTRATION_CHALLENGE_NOT_FOUND",
        "Không tìm thấy yêu cầu đăng ký cần xác nhận.");
  }

  public static ApiException otpInvalid() {
    return invalid("OTP_INVALID", "Mã xác nhận không đúng.");
  }

  public static ApiException otpExpired() {
    return invalid("OTP_EXPIRED", "Mã xác nhận đã hết hạn. Hãy bắt đầu đăng ký lại.");
  }

  public static ApiException otpAttemptsExhausted() {
    return new ApiException(
        HttpStatus.TOO_MANY_REQUESTS,
        "OTP_ATTEMPTS_EXHAUSTED",
        "Bạn đã nhập sai quá số lần cho phép. Hãy yêu cầu mã mới.");
  }

  public static ApiException otpResendTooSoon() {
    return new ApiException(
        HttpStatus.TOO_MANY_REQUESTS,
        "OTP_RESEND_TOO_SOON",
        "Vui lòng đợi trước khi yêu cầu gửi lại mã.");
  }

  public static ApiException invalid(String code, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, message);
  }

  public static ApiException invalidRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }

  public static ApiException notFound(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
  }
}
