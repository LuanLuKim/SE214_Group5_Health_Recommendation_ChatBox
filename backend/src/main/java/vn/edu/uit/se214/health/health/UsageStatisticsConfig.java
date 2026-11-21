package vn.edu.uit.se214.health.health;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.uit.se214.health.health.api.UsageEventRecorder;
import vn.edu.uit.se214.health.integrations.PythonServiceClient;

/** UC-19 and SCRUM-51 beans. They exist only with DATA_MODE=postgres; in mock mode UC-19 answers 503. */
@Configuration
@Profile("postgres")
class UsageStatisticsConfig {
  @Bean
  UsageEventRepository usageEventRepository(JdbcClient jdbc) {
    return new UsageEventRepository(jdbc);
  }

  @Bean
  UsageEventRecorder usageEventRecorder(
      UsageEventRepository repository, PlatformTransactionManager transactions) {
    return new JdbcUsageEventRecorder(repository, new TransactionTemplate(transactions), Clock.systemUTC());
  }

  @Bean
  SystemStatisticsService systemStatisticsService(
      UsageEventRepository repository,
      @Qualifier("statsService") PythonServiceClient stats,
      UsageStatisticsProperties properties) {
    return new SystemStatisticsService(
        repository, stats, properties, Clock.system(ZoneId.of(properties.zone())));
  }

  /** Off when app.health.statistics.record-events=false. */
  @Bean
  FilterRegistrationBean<UsageEventFilter> usageEventFilter(
      UsageEventRecorder recorder, UsageStatisticsProperties properties) {
    FilterRegistrationBean<UsageEventFilter> registration =
        new FilterRegistrationBean<>(new UsageEventFilter(recorder, properties.eventRoutes()));
    registration.setEnabled(properties.recordEvents());
    return registration;
  }
}
