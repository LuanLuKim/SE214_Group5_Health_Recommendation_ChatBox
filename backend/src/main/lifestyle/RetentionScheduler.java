package lifestyle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs {@link LifestyleRetention} on app.lifestyle.retention.cron while retention is enabled. */
class RetentionScheduler {
  private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

  private final LifestyleRetention retention;

  RetentionScheduler(LifestyleRetention retention) {
    this.retention = retention;
  }

  @Scheduled(cron = "${app.lifestyle.retention.cron}", zone = "${app.lifestyle.zone}")
  void run() {
    try {
      retention.runOnce();
    } catch (RuntimeException e) {
      // The database may be briefly unreachable; tomorrow's run continues where this one stopped.
      log.warn("Lifestyle retention run failed: {}", e.getClass().getSimpleName());
    }
  }
}
