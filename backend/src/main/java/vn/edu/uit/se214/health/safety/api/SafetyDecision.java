package vn.edu.uit.se214.health.safety.api;

import java.util.List;
import vn.edu.uit.se214.health.shared.domain.SourceReference;

/** M1 → M4. When no reviewed rule applies the status is UNAVAILABLE and careLevel is null. */
public record SafetyDecision(
    Status status,
    CareLevel careLevel,
    List<String> matchedRuleIds,
    List<String> missingFields,
    List<String> specialtyIds,
    List<SourceReference> sources) {

  public enum Status {
    EVALUATED,
    UNAVAILABLE
  }

  public static SafetyDecision unavailable() {
    return new SafetyDecision(Status.UNAVAILABLE, null, List.of(), List.of(), List.of(), List.of());
  }
}
