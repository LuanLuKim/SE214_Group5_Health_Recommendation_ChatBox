package vn.edu.uit.se214.health.health;

/** UC-04: BMI from height (cm) and weight (kg), rounded to two decimals. */
public final class BmiCalculator {
  private BmiCalculator() {}

  /** Returns null when a value is missing, not finite, or not positive. */
  public static Double calculate(Double heightCm, Double weightKg) {
    if (heightCm == null
        || weightKg == null
        || !Double.isFinite(heightCm)
        || !Double.isFinite(weightKg)
        || heightCm <= 0
        || weightKg <= 0) return null;
    double meters = heightCm / 100;
    double bmi = weightKg / (meters * meters);
    if (!Double.isFinite(bmi)) return null;
    double rounded = Math.round(bmi * 100) / 100.0;
    return Double.isFinite(rounded) ? rounded : null;
  }
}
