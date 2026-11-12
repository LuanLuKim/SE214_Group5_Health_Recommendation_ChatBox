package vn.edu.uit.se214.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * FORWARD_HEADERS=native (behind a reverse proxy): the rate limit counts the client address
 * from X-Forwarded-For, not the proxy's. Otherwise every user would share one limit.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"server.forward-headers-strategy=native", "app.rate-limit-per-minute=1"})
class ForwardedClientIpTest {
  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  private int status(String forwardedFor) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz"))
            .header("X-Forwarded-For", forwardedFor)
            .build();
    return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  @Test
  void rateLimitCountsTheForwardedClientNotTheProxy() throws Exception {
    // The limiter uses one-minute windows; retry once if the minute changes mid-test.
    for (int attempt = 0; ; attempt++) {
      long minute = System.currentTimeMillis() / 60_000;
      String first = "203.0.113." + (10 + attempt * 2);
      String second = "203.0.113." + (11 + attempt * 2);
      int[] statuses = {status(first), status(second), status(first)};
      if (System.currentTimeMillis() / 60_000 != minute && attempt == 0) continue;
      assertThat(statuses).containsExactly(200, 200, 429);
      return;
    }
  }
}
