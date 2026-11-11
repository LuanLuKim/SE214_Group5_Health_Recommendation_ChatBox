package vn.edu.uit.se214.health.safety.api;

/**
 * Public interface of M1 for M4 (UC-06 include UC-07). The real implementation will call the
 * Safety Gate in the FastAPI service (services/ai) with approved rules only.
 */
public interface SafetyEvaluator {
  SafetyDecision evaluate(String text);
}
