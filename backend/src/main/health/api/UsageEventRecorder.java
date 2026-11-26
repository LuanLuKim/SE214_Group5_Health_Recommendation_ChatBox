package health.api;

/**
 * Records one aggregate usage event for UC-19. Other modules may call this when a route filter
 * cannot see the event (for example a new account created inside an OAuth sign-in).
 *
 * <p>Best effort: a failure to record is logged and swallowed, so it never breaks the caller's
 * sign-in, registration or chat. Only exists with DATA_MODE=postgres.
 */
public interface UsageEventRecorder {
  void record(UsageEventType type);
}
