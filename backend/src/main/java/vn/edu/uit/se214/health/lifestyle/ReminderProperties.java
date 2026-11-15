package vn.edu.uit.se214.health.lifestyle;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.lifestyle.reminders.*} (UC-14).
 *
 * @param enabled run the sending worker; the reminder routes work either way
 * @param poll pause between two worker runs
 * @param window a reminder is still sent up to this long after its time; older ones are skipped,
 *     never sent late in a batch (for example after the backend was stopped)
 * @param maxAttempts sends per scheduled reminder, the first one included
 * @param maxPerUser reminders one user may keep
 * @param fromAddress sender of reminder emails (MAIL_FROM)
 */
@ConfigurationProperties(prefix = "app.lifestyle.reminders")
public record ReminderProperties(
    boolean enabled,
    Duration poll,
    Duration window,
    int maxAttempts,
    int maxPerUser,
    String fromAddress) {}
