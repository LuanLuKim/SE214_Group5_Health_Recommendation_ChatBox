package vn.edu.uit.se214.health.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("postgres")
public class CsrfController {
  private final CookieCsrfTokenRepository csrfTokenRepository;

  public CsrfController(CookieCsrfTokenRepository csrfTokenRepository) {
    this.csrfTokenRepository = csrfTokenRepository;
  }

  @GetMapping("/api/v1/csrf")
  public ApiResponse.Success<CsrfTokenInfo> csrfToken(
      @RequestAttribute("_csrf") CsrfToken csrfToken,
      HttpServletRequest request,
      HttpServletResponse response) {
    csrfTokenRepository.saveToken(csrfToken, request, response);
    return ApiResponse.of(new CsrfTokenInfo(csrfToken.getToken()));
  }

  public record CsrfTokenInfo(String token) {}
}
