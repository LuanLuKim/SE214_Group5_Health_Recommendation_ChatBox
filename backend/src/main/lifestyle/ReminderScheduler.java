package lifestyle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs {@link ReminderDispatcher} every app.lifestyle.reminders.poll while reminders are enabled. */
class ReminderScheduler {
  private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

  private final ReminderDispatcher dispatcher;

  ReminderScheduler(ReminderDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Scheduled(
      fixedDelayString = "${app.lifestyle.reminders.poll}",
      initialDelayString = "${app.lifestyle.reminders.poll}")
  void run() {
    try {
      dispatcher.runOnce();
    } catch (RuntimeException e) {
      // The database may be briefly unreachable; the next run tries again.
      log.warn("Reminder run failed: {}", e.getClass().getSimpleName());
    }
  }
}
