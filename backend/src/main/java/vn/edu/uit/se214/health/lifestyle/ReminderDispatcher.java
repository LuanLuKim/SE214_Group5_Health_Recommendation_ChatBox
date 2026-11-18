package vn.edu.uit.se214.health.lifestyle;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.uit.se214.health.accounts.api.ConsentQuery;
import vn.edu.uit.se214.health.lifestyle.ReminderRepository.Retry;

/**
 * UC-14 sending: one run finds due reminders, reserves each send in reminder_deliveries, then
 * sends it and records SENT or FAILED (A3). Running twice, retrying or several backend instances at
 * once never sends the same reminder twice: only the run that reserved a row sends it.
 *
 * <p>A reminder that is off is never reserved or retried, and neither is one whose owner no longer
 * consents to health-data processing: no delivery row is written for it. One failing reminder or
 * channel does not stop the others. Logs carry ids and error codes only, never the content or an
 * address.
 */
public class ReminderDispatcher {
  private static final Logger log = LoggerFactory.getLogger(ReminderDispatcher.class);

  private final ReminderRepository repository;
  private final ConsentQuery consents;
  private final Map<ReminderChannel.Type, ReminderChannel> channels;
  private final Clock clock;
  private final ReminderProperties properties;

  public ReminderDispatcher(
      ReminderRepository repository,
      ConsentQuery consents,
      List<ReminderChannel> channels,
      Clock clock,
      ReminderProperties properties) {
    this.repository = repository;
    this.consents = consents;
    this.channels = channels.stream().collect(Collectors.toMap(ReminderChannel::type, Function.identity()));
    this.clock = clock;
    this.properties = properties;
  }

  /** Counts of one run, for tests and the log. */
  public record Result(int sent, int failed) {}

  /**
   * Retries first, then new sends, so a send that fails now is retried on the next run rather than
   * again within the same second.
   */
  public Result runOnce() {
    Instant now = clock.instant();
    Map<UUID, Boolean> consented = new HashMap<>();
    int sent = 0;
    int failed = 0;
    for (Retry retry : repository.retryable(properties.maxAttempts(), now.minus(properties.window()))) {
      try {
        if (!consented(consented, retry.userId())) continue;
        if (!repository.claimRetry(retry.deliveryId(), properties.maxAttempts(), now)) continue;
        if (deliver(retry.deliveryId(), retry.userId(), retry.content(), retry.channel(), now)) sent++;
        else failed++;
      } catch (RuntimeException e) {
        failed++;
        log.warn("Reminder delivery {} could not be retried: {}", retry.deliveryId(), e.getClass().getSimpleName());
      }
    }
    for (ReminderRow reminder : repository.enabled()) {
      try {
        Optional<Instant> due = ReminderSchedule.due(reminder, now, properties.window());
        if (due.isEmpty()) continue;
        if (!consented(consented, reminder.userId())) continue;
        Optional<UUID> delivery = repository.claim(reminder.id(), due.get(), reminder.channel(), now);
        if (delivery.isEmpty()) continue;
        if (deliver(delivery.get(), reminder.userId(), reminder.content(), reminder.channel(), now)) sent++;
        else failed++;
      } catch (RuntimeException e) {
        failed++;
        log.warn("Reminder {} could not be processed: {}", reminder.id(), e.getClass().getSimpleName());
      }
    }
    if (sent + failed > 0) log.info("Reminders sent: {}, failed: {}", sent, failed);
    return new Result(sent, failed);
  }

  /** Read once per user and run, before anything is reserved for that user. */
  private boolean consented(Map<UUID, Boolean> cache, UUID userId) {
    Boolean known = cache.get(userId);
    if (known != null) return known;
    boolean accepted = consents.healthDataProcessingAccepted(userId);
    cache.put(userId, accepted);
    return accepted;
  }

  /**
   * Marking SENT stays outside the try: if only that write fails, the row stays PENDING and is
   * never retried, so a reminder that went out is not sent again.
   */
  private boolean deliver(UUID deliveryId, UUID userId, String content, ReminderChannel.Type type, Instant now) {
    ReminderChannel channel = channels.get(type);
    try {
      if (channel == null) throw new ReminderChannel.SendFailure("CHANNEL_UNAVAILABLE");
      channel.send(userId, content);
    } catch (ReminderChannel.SendFailure e) {
      repository.markFailed(deliveryId, e.code(), now);
      log.warn("Reminder delivery {} failed: {}", deliveryId, e.code());
      return false;
    } catch (RuntimeException e) {
      repository.markFailed(deliveryId, "UNEXPECTED_ERROR", now);
      log.warn("Reminder delivery {} failed: {}", deliveryId, e.getClass().getSimpleName());
      return false;
    }
    repository.markSent(deliveryId, now);
    return true;
  }
}
