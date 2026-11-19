package vn.edu.uit.se214.health.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.edu.uit.se214.health.shared.AppProperties;

class PythonServiceClientTest {
  @Test
  void sendsTheServiceKeyToTheConfiguredService() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    var client =
        new PythonServiceClient(
            new AppProperties.PythonService("http://ai.internal:8001", "synthetic-key", 1000), builder);
    server
        .expect(requestTo("http://ai.internal:8001/v1/safety/evaluate"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(PythonServiceClient.KEY_HEADER, "synthetic-key"))
        .andRespond(withSuccess("{\"status\":\"UNAVAILABLE\"}", MediaType.APPLICATION_JSON));

    Map<?, ?> result = client.post("/v1/safety/evaluate", Map.of("text", "synthetic"), Map.class);
    assertThat(result.get("status")).isEqualTo("UNAVAILABLE");
    server.verify();
  }

  @Test
  void streamsLinesFromEndpoint() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    var client =
        new PythonServiceClient(
            new AppProperties.PythonService("http://ai.internal:8001", "synthetic-key", 1000), builder);
    server
        .expect(requestTo("http://ai.internal:8001/v1/chat/answer"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(PythonServiceClient.KEY_HEADER, "synthetic-key"))
        .andRespond(
            withSuccess(
                "data: {\"type\":\"token\",\"data\":\"Hello\"}\n\ndata: {\"type\":\"done\"}\n\n",
                MediaType.TEXT_EVENT_STREAM));

    java.util.List<String> lines = new java.util.ArrayList<>();
    client.postStream("/v1/chat/answer", Map.of("message", "hi"), lines::add);
    assertThat(lines).contains("data: {\"type\":\"token\",\"data\":\"Hello\"}", "data: {\"type\":\"done\"}");
    server.verify();
  }

  @Test
  void refusesToCallWithoutAKey() {
    var client =
        new PythonServiceClient(new AppProperties.PythonService("http://ai.internal:8001", "", 1000), RestClient.builder());
    assertThat(client.configured()).isFalse();
    assertThatThrownBy(() -> client.get("/healthz", Map.class)).hasMessage("SERVICE_NOT_CONFIGURED");
  }
}
