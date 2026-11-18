package vn.edu.uit.se214.health.safety.api;

import java.util.Set;

/**
 * Public interface of M1 for M4 (UC-06 include UC-07). The real implementation will call the
 * Safety Gate in the FastAPI service (services/ai) with approved rules only.
 */
public interface SafetyEvaluator {
  SafetyDecision evaluate(String text);

  /** Only facts explicitly extracted from normalized input may influence the care direction. */
  default SafetyDecision evaluateFacts(String normalizedText, Set<String> observedFacts) {
    return evaluate(normalizedText);
  }
}
