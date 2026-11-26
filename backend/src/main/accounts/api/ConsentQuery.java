package accounts.api;

import java.util.UUID;

/** M5 public interface: checks whether a user currently permits processing health data. */
public interface ConsentQuery {

  /** Whether the user's latest consent accepts the processing of health data. False when none. */
  boolean healthDataProcessingAccepted(UUID userId);
}
