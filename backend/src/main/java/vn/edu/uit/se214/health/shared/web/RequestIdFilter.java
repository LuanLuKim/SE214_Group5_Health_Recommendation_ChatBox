package vn.edu.uit.se214.health.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Request ID, no-store caching and baseline security headers on every response. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
  private static final String DEFAULT_CSP = "default-src 'none'; frame-ancestors 'none'";
  private static final String SWAGGER_CSP =
      "default-src 'self';"
          + " script-src 'self' 'unsafe-inline';"
          + " style-src 'self' 'unsafe-inline';"
          + " img-src 'self' data:;"
          + " font-src 'self' data:;"
          + " connect-src 'self';"
          + " object-src 'none';"
          + " base-uri 'self';"
          + " frame-ancestors 'none'";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    request.setAttribute(RequestIds.ATTRIBUTE, id);
    response.setHeader(RequestIds.HEADER, id);
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader(
        "Content-Security-Policy", isSwaggerUiRequest(request) ? SWAGGER_CSP : DEFAULT_CSP);
    response.setHeader("Cross-Origin-Resource-Policy", "same-site");
    chain.doFilter(request, response);
  }

  private static boolean isSwaggerUiRequest(HttpServletRequest request) {
    String path = request.getRequestURI();
    return "/swagger-ui.html".equals(path) || path.startsWith("/swagger-ui/");
  }
}
