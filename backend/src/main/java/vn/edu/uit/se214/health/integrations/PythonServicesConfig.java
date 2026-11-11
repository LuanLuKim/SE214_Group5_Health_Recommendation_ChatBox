package vn.edu.uit.se214.health.integrations;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import vn.edu.uit.se214.health.shared.AppProperties;

/**
 * Clients for services/ai (FastAPI: Safety Gate, RAG, LLM, output checks) and services/stats
 * (Django: UC-05 trends, UC-19 system statistics). Inject with {@code @Qualifier("aiService")}
 * or {@code @Qualifier("statsService")}.
 */
@Configuration
public class PythonServicesConfig {
  @Bean
  @Qualifier("aiService")
  PythonServiceClient aiService(AppProperties properties, RestClient.Builder builder) {
    return create(properties.aiService(), builder);
  }

  @Bean
  @Qualifier("statsService")
  PythonServiceClient statsService(AppProperties properties, RestClient.Builder builder) {
    return create(properties.statsService(), builder);
  }

  private static PythonServiceClient create(AppProperties.PythonService service, RestClient.Builder builder) {
    int timeout = service == null ? 10_000 : Math.max(service.timeoutMs(), 100);
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(Math.min(timeout, 5_000)));
    factory.setReadTimeout(Duration.ofMillis(timeout));
    return new PythonServiceClient(service, builder.clone().requestFactory(factory));
  }
}
