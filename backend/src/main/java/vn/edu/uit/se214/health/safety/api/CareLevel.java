package vn.edu.uit.se214.health.safety.api;

/** The five care directions. Only reviewed rules choose one, never an LLM. */
public enum CareLevel {
  EMERGENCY_NOW,
  SEEK_CARE_SOON,
  ROUTINE_CONSULT,
  GENERAL_SELF_CARE_INFO,
  INSUFFICIENT_INFO
}
