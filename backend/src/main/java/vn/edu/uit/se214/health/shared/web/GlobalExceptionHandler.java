package vn.edu.uit.se214.health.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error leaves the API as {@code {"error": {code, message, requestId}}}, always as JSON.
 * No exception message, stack trace, request body, prompt, credential or health data reaches
 * clients. Server faults are logged with the request ID, the exception types and the code
 * location only: exception messages can quote user data (for example a database constraint
 * error), so they are never written to the log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final String OWN_PACKAGE = "vn.edu.uit.se214.health.";

  static ResponseEntity<ApiResponse.Failure> fail(
      HttpServletRequest request, HttpStatus status, String code, String message) {
    // An explicit content type keeps the envelope JSON whatever the client's Accept header says.
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
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

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<ApiResponse.Failure> notAcceptable(Exception e, HttpServletRequest request) {
    return fail(request, HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "API chỉ trả dữ liệu dạng JSON.");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiResponse.Failure> unreadable(
      HttpMessageNotReadableException e, HttpServletRequest request) {
    if (hasCause(e, RequestLimitFilter.PayloadTooLargeException.class)) return tooLarge(request);
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

  /** The database cannot be reached (down, restarting, pool exhausted): retryable, not a bug. */
  @ExceptionHandler(DataAccessResourceFailureException.class)
  ResponseEntity<ApiResponse.Failure> databaseUnavailable(Exception e, HttpServletRequest request) {
    log.error("Database unavailable {}", describe(e, request));
    return fail(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "DATABASE_UNAVAILABLE",
        "Hệ thống tạm thời gián đoạn. Vui lòng thử lại sau.");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiResponse.Failure> internal(Exception e, HttpServletRequest request) {
    if (hasCause(e, RequestLimitFilter.PayloadTooLargeException.class)) return tooLarge(request);
    log.error("Unhandled error {}", describe(e, request));
    return fail(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Không thể xử lý yêu cầu. Vui lòng thử lại.");
  }

  private static ResponseEntity<ApiResponse.Failure> tooLarge(HttpServletRequest request) {
    return fail(request, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Nội dung vượt quá giới hạn cho phép.");
  }

  private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
    for (Throwable t = e; t != null; t = t.getCause()) if (type.isInstance(t)) return true;
    return false;
  }

  /**
   * What an operator needs to find the fault, and nothing a user typed: request ID, method,
   * path without the query string, the exception types (outermost first) and the first frame
   * inside this application.
   */
  static String describe(Throwable e, HttpServletRequest request) {
    var types = new StringBuilder();
    Throwable root = e;
    int depth = 0;
    for (Throwable t = e; t != null && depth < 6; t = t.getCause(), depth++) {
      if (depth > 0) types.append(" <- ");
      types.append(t.getClass().getName());
      root = t;
    }
    return "requestId=%s method=%s path=%s exception=%s at=%s"
        .formatted(
            RequestIds.get(request), request.getMethod(), safePath(request), types, location(e, root));
  }

  private static String safePath(HttpServletRequest request) {
    String path = request.getRequestURI();
    if (path == null) return "";
    String printable = path.replaceAll("[^\\x21-\\x7E]", "?");
    return printable.length() > 200 ? printable.substring(0, 200) : printable;
  }

  /** First stack frame in this application, searching the root cause first. */
  private static String location(Throwable outer, Throwable root) {
    for (Throwable t : new Throwable[] {root, outer})
      for (StackTraceElement frame : t.getStackTrace())
        if (frame.getClassName().startsWith(OWN_PACKAGE) && !frame.getClassName().contains(".shared.web."))
          return simpleName(frame.getClassName()) + "." + frame.getMethodName() + ":" + frame.getLineNumber();
    StackTraceElement[] frames = root.getStackTrace();
    return frames.length == 0 ? "unknown" : frames[0].getClassName() + "." + frames[0].getMethodName();
  }

  private static String simpleName(String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }
}
