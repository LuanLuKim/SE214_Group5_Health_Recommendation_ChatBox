package health.api;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * M3 public interface: what another module may read from a user's health profile. Other modules
 * never query health.profiles themselves.
 */
public interface HealthProfileQuery {

  /**
   * The user's self-declared food allergies, normalised with {@link #normaliseAllergen}. Empty
   * when the user has no health profile yet (UC-12 A1), so "no profile" and "no allergies" stay
   * distinguishable.
   */
  Optional<Set<String>> foodAllergies(UUID userId);

  /** One way to compare allergen names everywhere: trimmed, lower case. */
  static String normaliseAllergen(String value) {
    return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
  }
}
