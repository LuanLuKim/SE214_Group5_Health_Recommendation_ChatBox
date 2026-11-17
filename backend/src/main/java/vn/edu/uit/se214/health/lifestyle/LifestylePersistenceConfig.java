package vn.edu.uit.se214.health.lifestyle;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.uit.se214.health.accounts.api.AccountContactQuery;
import vn.edu.uit.se214.health.accounts.api.ConsentQuery;
import vn.edu.uit.se214.health.health.api.HealthProfileQuery;
import vn.edu.uit.se214.health.shared.AppProperties;

/** Database-backed beans exist only with DATA_MODE=postgres; in mock mode the routes answer 503. */
@Configuration
@Profile("postgres")
class LifestylePersistenceConfig {
  @Bean
  LifestyleRepository lifestyleRepository(JdbcClient jdbc, PlatformTransactionManager transactions) {
    return new LifestyleRepository(jdbc, new TransactionTemplate(transactions));
  }

  @Bean
  LifestyleService lifestyleService(
      LifestyleRepository repository,
      ObjectMapper json,
      LifestyleProperties properties,
      ConsentQuery consents) {
    return new LifestyleService(repository, json, clock(properties), consents);
  }

  @Bean
  LifestylePlanService lifestylePlanService(
      LifestyleRepository repository,
      ObjectMapper json,
      LifestyleProperties properties,
      HealthProfileQuery profiles,
      ConsentQuery consents,
      AppProperties app) {
    return new LifestylePlanService(
        repository, json, clock(properties), profiles, consents, app.seedSynthetic());
  }

  @Bean
  ReminderRepository reminderRepository(JdbcClient jdbc) {
    return new ReminderRepository(jdbc);
  }

  /** UC-14 channels. WEB_PUSH has none yet, so it answers REMINDER_CHANNEL_UNAVAILABLE (A2). */
  @Bean
  EmailReminderChannel emailReminderChannel(
      ObjectProvider<JavaMailSender> mailSenders,
      MailProperties mail,
      ReminderProperties properties,
      AccountContactQuery contacts) {
    return new EmailReminderChannel(mailSenders, mail, properties, contacts);
  }

  @Bean
  LifestyleReminderService lifestyleReminderService(
      ReminderRepository reminders,
      LifestyleRepository plans,
      ObjectMapper json,
      LifestyleProperties lifestyle,
      ReminderProperties properties,
      ConsentQuery consents,
      List<ReminderChannel> channels) {
    return new LifestyleReminderService(
        reminders, plans, json, clock(lifestyle), consents, channels, properties.maxPerUser());
  }

  @Bean
  ReminderDispatcher reminderDispatcher(
      ReminderRepository reminders,
      List<ReminderChannel> channels,
      LifestyleProperties lifestyle,
      ReminderProperties properties) {
    return new ReminderDispatcher(reminders, channels, clock(lifestyle), properties);
  }

  /** The sending worker; REMINDERS_ENABLED=false keeps the routes and stops sending. */
  @Bean
  @ConditionalOnProperty(name = "app.lifestyle.reminders.enabled", havingValue = "true")
  ReminderScheduler reminderScheduler(ReminderDispatcher dispatcher) {
    return new ReminderScheduler(dispatcher);
  }

  /** Not a bean, so no other module receives this zone by accident. An unknown zone id fails at startup. */
  private static Clock clock(LifestyleProperties properties) {
    return Clock.system(ZoneId.of(properties.zone()));
  }
}
