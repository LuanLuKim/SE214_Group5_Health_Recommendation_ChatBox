package health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import health.UsageStatisticsProperties.EventRoute;
import health.api.UsageEventType;

/** SCRUM-51: one event per successful configured route, nothing for failures or other routes. */
class UsageEventFilterTest {
  final List<UsageEventType> recorded = new ArrayList<>();
  final UsageEventFilter filter =
      new UsageEventFilter(
          recorded::add,
          List.of(
              new EventRoute("POST", "/api/v1/accounts/login", 200, UsageEventType.VISIT),
              new EventRoute("POST", "/api/v1/accounts/register/verify", 201, UsageEventType.REGISTRATION)));

  void run(String method, String uri, String contextPath, int status) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setContextPath(contextPath);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        new MockFilterChain() {
          @Override
          public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            ((MockHttpServletResponse) res).setStatus(status);
          }
        });
  }

  @Test
  void countsASuccessfulConfiguredRouteOnce() throws Exception {
    run("POST", "/api/v1/accounts/login", "", 200);
    run("POST", "/api/v1/accounts/register/verify", "", 201);
    assertThat(recorded).containsExactly(UsageEventType.VISIT, UsageEventType.REGISTRATION);
  }

  @Test
  void ignoresFailuresOtherMethodsAndOtherRoutes() throws Exception {
    run("POST", "/api/v1/accounts/login", "", 401);
    run("POST", "/api/v1/accounts/register/verify", "", 200);
    run("GET", "/api/v1/accounts/login", "", 200);
    run("POST", "/api/v1/accounts/login/extra", "", 200);
    assertThat(recorded).isEmpty();
  }

  @Test
  void matchesThePathWithoutTheContextPath() throws Exception {
    run("POST", "/app/api/v1/accounts/login", "/app", 200);
    assertThat(recorded).containsExactly(UsageEventType.VISIT);
  }
}
