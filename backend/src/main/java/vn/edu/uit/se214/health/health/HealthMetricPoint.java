package vn.edu.uit.se214.health.health;

import java.time.LocalDate;

/** One day of health.daily_metrics. A missing metric is null. Units: kg and hours. */
public record HealthMetricPoint(LocalDate date, Double weightKg, Double sleepHours) {}
