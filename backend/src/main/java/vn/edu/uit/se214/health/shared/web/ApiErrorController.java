package vn.edu.uit.se214.health.shared.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's default {@code /error} page. The servlet container forwards here for
 * errors raised outside a controller (for example in a filter); those keep their status and get
 * the same envelope as every other error. Opening {@code /error} directly is a plain 404.
 */
@RestController
public class ApiErrorController implements ErrorController {
  @RequestMapping("${server.error.path:/error}")
  ResponseEntity<ApiResponse.Failure> error(HttpServletRequest request) {
    HttpStatus status =
        request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code
            ? HttpStatus.resolve(code)
            : HttpStatus.NOT_FOUND;
    if (status == null || !status.isError()) status = HttpStatus.INTERNAL_SERVER_ERROR;
    return switch (status) {
      case NOT_FOUND ->
          GlobalExceptionHandler.fail(request, status, "NOT_FOUND", "Không tìm thấy đường dẫn.");
      case METHOD_NOT_ALLOWED ->
          GlobalExceptionHandler.fail(request, status, "METHOD_NOT_ALLOWED", "Phương thức không được hỗ trợ.");
      case PAYLOAD_TOO_LARGE ->
          GlobalExceptionHandler.fail(request, status, "PAYLOAD_TOO_LARGE", "Nội dung vượt quá giới hạn cho phép.");
      default ->
          status.is4xxClientError()
              ? GlobalExceptionHandler.fail(request, status, "INVALID_REQUEST", "Dữ liệu yêu cầu không hợp lệ.")
              : GlobalExceptionHandler.fail(request, status, "INTERNAL_ERROR", "Không thể xử lý yêu cầu. Vui lòng thử lại.");
    };
  }
}
