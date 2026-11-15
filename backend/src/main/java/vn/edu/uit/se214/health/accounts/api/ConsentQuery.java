package vn.edu.uit.se214.health.accounts.api;

import java.util.UUID;

/** M5 public interface: consent checks for modules that process health data (UC-12 precondition). */
public interface ConsentQuery {

  /** Whether the user's latest consent accepts the processing of health data. False when none. */
  boolean healthDataProcessingAccepted(UUID userId);
}
