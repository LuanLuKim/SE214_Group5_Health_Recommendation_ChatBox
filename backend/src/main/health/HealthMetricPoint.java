package health;

import java.time.LocalDate;

/** One day of health.daily_metrics. A missing metric is null. Units: kg and hours. */
public record HealthMetricPoint(LocalDate date, Double weightKg, Double sleepHours) {}
