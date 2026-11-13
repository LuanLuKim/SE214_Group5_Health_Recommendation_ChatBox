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

  /** Private routes fail closed until M5 provides trusted sessions. */
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
