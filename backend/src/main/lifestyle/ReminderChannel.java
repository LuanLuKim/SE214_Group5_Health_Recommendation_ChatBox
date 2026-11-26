package lifestyle;

import java.util.UUID;

/** One way of delivering a reminder (UC-14). A channel without an implementation is unavailable. */
public interface ReminderChannel {
  enum Type {
    EMAIL,
    WEB_PUSH
  }

  Type type();

  /** A2: whether this user can receive reminders on this channel now (configured, address known). */
  boolean available(UUID userId);

  /** Sends {@code content} to the user. Must not log the content or the recipient's address. */
  void send(UUID userId, String content) throws SendFailure;

  /** A3: a failed send, with a short code stored in reminder_deliveries.last_error_code. */
  final class SendFailure extends Exception {
    private final String code;

    public SendFailure(String code) {
      super(code, null, false, false);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
