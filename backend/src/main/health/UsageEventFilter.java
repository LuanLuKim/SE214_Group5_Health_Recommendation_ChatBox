package health;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;
import health.api.UsageEventRecorder;

/**
 * SCRUM-51: counts usage events for UC-19 from the outcome of other modules' routes, so M5 and
 * M4 code stays unchanged. A route counts once when it answers with its configured success
 * status (app.health.statistics.event-routes). Only the kind and the time are stored: no user,
 * no request body, no response body.
 *
 * <p>The chat route streams its answer; the initial dispatch already carries 200 once the
 * question was accepted, and validation errors answer 4xx before that, so they are not counted.
 */
class UsageEventFilter extends OncePerRequestFilter {
  private final UsageEventRecorder recorder;
  private final List<UsageStatisticsProperties.EventRoute> routes;

  UsageEventFilter(UsageEventRecorder recorder, List<UsageStatisticsProperties.EventRoute> routes) {
    this.recorder = recorder;
    this.routes = List.copyOf(routes);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String method = request.getMethod();
    String path = path(request);
    return routes.stream().noneMatch(r -> r.method().equalsIgnoreCase(method) && r.path().equals(path));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(request, response);
    String method = request.getMethod();
    String path = path(request);
    int status = response.getStatus();
    routes.stream()
        .filter(route -> route.matches(method, path, status))
        .findFirst()
        .ifPresent(route -> recorder.record(route.type()));
  }

  private static String path(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    return context != null && !context.isEmpty() && uri.startsWith(context)
        ? uri.substring(context.length())
        : uri;
  }
}
