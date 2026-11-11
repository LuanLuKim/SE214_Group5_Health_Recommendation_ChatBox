package vn.edu.uit.se214.health.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error leaves the API as {@code {"error": {code, message, requestId}}}. No exception
 * message, stack trace, request body, prompt, credential or health data reaches clients or logs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static ResponseEntity<ApiResponse.Failure> fail(
      HttpServletRequest request, HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(new ApiResponse.Failure(new ApiResponse.Error(code, message, RequestIds.get(request))));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiResponse.Failure> api(ApiException e, HttpServletRequest request) {
    return fail(request, e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  ResponseEntity<ApiResponse.Failure> notFound(Exception e, HttpServletRequest request) {
    return fail(request, HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy đường dẫn.");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiResponse.Failure> method(Exception e, HttpServletRequest request) {
    return fail(request, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Phương thức không được hỗ trợ.");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiResponse.Failure> unreadable(
      HttpMessageNotReadableException e, HttpServletRequest request) {
    if (hasCause(e, RequestLimitFilter.PayloadTooLargeException.class))
      return fail(request, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Nội dung vượt quá giới hạn cho phép.");
    return fail(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Dữ liệu yêu cầu không hợp lệ.");
  }

  @ExceptionHandler({
    HttpMediaTypeNotSupportedException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ApiResponse.Failure> badRequest(Exception e, HttpServletRequest request) {
    return fail(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Dữ liệu yêu cầu không hợp lệ.");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiResponse.Failure> internal(Exception e, HttpServletRequest request) {
    if (hasCause(e, RequestLimitFilter.PayloadTooLargeException.class))
      return fail(request, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Nội dung vượt quá giới hạn cho phép.");
    return fail(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Không thể xử lý yêu cầu. Vui lòng thử lại.");
  }

  private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
    for (Throwable t = e; t != null; t = t.getCause()) if (type.isInstance(t)) return true;
    return false;
  }
}
