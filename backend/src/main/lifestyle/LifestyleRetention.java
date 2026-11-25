package lifestyle;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SCRUM-164: removes M6 data past its retention period (app.lifestyle.retention.*), in batches.
 *
 * <ul>
 *   <li>SENT and SKIPPED deliveries after {@code sentDeliveries}; FAILED and PENDING ones after
 *       {@code failedDeliveries}. Both periods are far beyond the send window, so a removed row can
 *       never make a reminder be sent again.
 *   <li>Completions of plans ended longer ago than {@code endedPlanCompletions}: the counts stay on
 *       the plan (GET /plans/history keeps showing them), the rows go.
 *   <li>Reminders paused by a plan switch and unchanged for {@code pausedReminders}. Reminders the
 *       user turned off are never removed here.
 * </ul>
 *
 * Logs carry counts only.
 */
public class LifestyleRetention {
  private static final Logger log = LoggerFactory.getLogger(LifestyleRetention.class);
  private static final List<String> SENT = List.of("SENT", "SKIPPED");
  private static final List<String> NOT_SENT = List.of("FAILED", "PENDING");

  private final RetentionRepository repository;
  private final RetentionProperties properties;
  private final Clock clock;

  public LifestyleRetention(RetentionRepository repository, RetentionProperties properties, Clock clock) {
    this.repository = repository;
    this.properties = properties;
    this.clock = clock;
  }

  /** Rows (plans for {@code summarizedPlans}) handled by one run. */
  public record Result(int sentDeliveries, int failedDeliveries, int pausedReminders, int summarizedPlans) {}

  public Result runOnce() {
    Instant now = clock.instant();
    LocalDate today = LocalDate.now(clock);
    int batch = properties.batchSize();
    Result result =
        new Result(
            drain(() -> repository.deleteDeliveries(SENT, now.minus(properties.sentDeliveries()), batch)),
            drain(() -> repository.deleteDeliveries(NOT_SENT, now.minus(properties.failedDeliveries()), batch)),
            drain(() -> repository.deletePausedReminders(now.minus(properties.pausedReminders()), batch)),
            drain(
                () ->
                    repository.summarizeEndedPlans(
                        today.minusDays(properties.endedPlanCompletions().toDays()), now, batch)));
    log.info(
        "Lifestyle retention: deliveries sent {}, failed {}; paused reminders {}; plans summarized {}",
        result.sentDeliveries(),
        result.failedDeliveries(),
        result.pausedReminders(),
        result.summarizedPlans());
    return result;
  }

  /** Repeats one batched step until a batch comes back short (or empty). */
  private int drain(IntSupplier step) {
    int total = 0;
    int handled;
    do {
      handled = step.getAsInt();
      total += handled;
    } while (handled > 0 && handled >= properties.batchSize());
    return total;
  }
}
