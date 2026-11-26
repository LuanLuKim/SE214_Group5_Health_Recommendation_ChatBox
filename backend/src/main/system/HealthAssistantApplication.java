package system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Modular monolith: one Spring Boot application, one package per module (M1–M6).
 * A module may only use another module through that module's public types
 * (its {@code api} package or service interface), never its repositories or tables.
 */
@SpringBootApplication(scanBasePackages = {
    "accounts", "health", "chat", "safety", "knowledge", "lifestyle", "shared", "integrations", "system"
})
@ConfigurationPropertiesScan(basePackages = {
    "accounts", "health", "chat", "safety", "knowledge", "lifestyle", "shared", "integrations", "system"
})
@EnableScheduling
public class HealthAssistantApplication {
  public static void main(String[] args) {
    SpringApplication.run(HealthAssistantApplication.class, args);
  }
}
