package vn.edu.uit.se214.health.shared.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes the error envelope from servlet filters, where controller advice does not apply. */
@Component
public class ErrorWriter {
  private final ObjectMapper objectMapper;

  public ErrorWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void write(
      HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    var body = new ApiResponse.Failure(new ApiResponse.Error(code, message, RequestIds.get(request)));
    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
