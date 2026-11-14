package vn.edu.uit.se214.health.lifestyle;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.lifestyle.*}: the time zone whose local midnight starts a new habit day (UC-13). */
@ConfigurationProperties(prefix = "app.lifestyle")
public record LifestyleProperties(String zone) {}
