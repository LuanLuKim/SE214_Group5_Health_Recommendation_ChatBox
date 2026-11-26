package lifestyle;

import java.util.Locale;
import java.util.Optional;

/** FR-2.6 goals (CHECK templates_goal_check in V013). */
public enum LifestyleGoal {
  /** Cải thiện giấc ngủ. */
  SLEEP,
  /** Duy trì vóc dáng. */
  MAINTAIN,
  /** Tăng cân. */
  GAIN,
  /** Giảm cân. */
  LOSE;

  /** No value means every goal; an unknown value is a client error. */
  static Optional<LifestyleGoal> parse(String value) {
    if (value == null || value.isBlank()) return Optional.empty();
    try {
      return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      throw LifestyleErrors.invalidGoal();
    }
  }
}
