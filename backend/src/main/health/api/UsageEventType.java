package health.api;

/**
 * Kinds of aggregate usage events for UC-19 (values of {@code health.usage_events.event_type}).
 * An event is a kind and a time only: never a user ID, email, message or health value.
 */
public enum UsageEventType {
  /** An account finished registering. */
  REGISTRATION,
  /** A successful sign-in (password or OAuth). */
  VISIT,
  /** A chat question was accepted by the assistant. */
  CHAT_INTERACTION
}
