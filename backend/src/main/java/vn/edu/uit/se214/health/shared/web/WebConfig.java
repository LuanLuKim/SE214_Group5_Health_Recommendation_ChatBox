package vn.edu.uit.se214.health.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vn.edu.uit.se214.health.shared.AppProperties;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AppProperties properties;

  public WebConfig(AppProperties properties) {
    this.properties = properties;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/**")
        .allowedOrigins(properties.webOrigin())
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
        .allowCredentials(true);
  }
}
