package vn.edu.uit.se214.health.accounts.api;

import java.util.Optional;
import java.util.UUID;

/** M5 public interface: where to reach a user outside the app (UC-14 email reminders). */
public interface AccountContactQuery {

  /** The registered email of an active account; empty when the account is missing or locked. */
  Optional<String> email(UUID userId);
}
