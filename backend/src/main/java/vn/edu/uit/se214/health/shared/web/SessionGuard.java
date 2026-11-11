package vn.edu.uit.se214.health.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Deliberately fails closed. M5 replaces the body of {@link #preHandle} with real session
 * validation (and ownership/role checks stay in each module's service).
 */
@Component
public class SessionGuard implements HandlerInterceptor {
  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (handler instanceof HandlerMethod method
        && (method.hasMethodAnnotation(RequiresSession.class)
            || method.getBeanType().isAnnotationPresent(RequiresSession.class))) {
      throw ApiException.authNotConfigured();
    }
    return true;
  }
}
