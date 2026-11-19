package vn.edu.uit.se214.health.lifestyle;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.lifestyle.retention.*}: how long M6 keeps data before the cleanup job removes it
 * (SCRUM-164). Reminders the user turned off are never removed by the job.
 *
 * @param enabled run the daily cleanup job
 * @param cron when it runs, in app.lifestyle.zone
 * @param sentDeliveries SENT and SKIPPED deliveries are removed once scheduled longer ago than this
 * @param failedDeliveries FAILED and PENDING deliveries are removed once scheduled longer ago than this
 * @param endedPlanCompletions completions of a plan ended longer ago than this are summarized on the
 *     plan, then removed
 * @param pausedReminders reminders paused by a plan switch and not changed for this long are removed
 * @param batchSize rows per delete, so no statement holds locks for long
 */
@ConfigurationProperties(prefix = "app.lifestyle.retention")
public record RetentionProperties(
    boolean enabled,
    String cron,
    Duration sentDeliveries,
    Duration failedDeliveries,
    Duration endedPlanCompletions,
    Duration pausedReminders,
    int batchSize) {}
