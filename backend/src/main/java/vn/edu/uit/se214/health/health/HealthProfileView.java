package vn.edu.uit.se214.health.health;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-04 response. {@code weightKg} is the latest weight in health.daily_metrics (recorded on
 * {@code weightRecordedOn}); a user without a profile gets null fields and an empty allergy list,
 * and {@code bmi} is null unless both height and weight exist (UC-04 A2).
 */
public record HealthProfileView(
    Double heightCm,
    Double weightKg,
    LocalDate weightRecordedOn,
    Integer age,
    String sex,
    List<String> foodAllergies,
    Double bmi,
    String bmiNote) {}
