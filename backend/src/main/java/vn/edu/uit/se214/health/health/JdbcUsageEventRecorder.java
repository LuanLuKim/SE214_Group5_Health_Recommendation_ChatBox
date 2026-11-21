package vn.edu.uit.se214.health.health;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.uit.se214.health.health.api.UsageEventRecorder;
import vn.edu.uit.se214.health.health.api.UsageEventType;

/**
 * Writes each event in its own transaction, so a caller's transaction is never aborted by a
 * failed insert and a failure here never reaches the caller.
 */
class JdbcUsageEventRecorder implements UsageEventRecorder {
  private static final Logger log = LoggerFactory.getLogger(JdbcUsageEventRecorder.class);

  private final UsageEventRepository repository;
  private final TransactionTemplate ownTransaction;
  private final Clock clock;

  JdbcUsageEventRecorder(UsageEventRepository repository, TransactionTemplate transactions, Clock clock) {
    this.repository = repository;
    this.ownTransaction = new TransactionTemplate(transactions.getTransactionManager());
    this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  @Override
  public void record(UsageEventType type) {
    try {
      ownTransaction.executeWithoutResult(status -> repository.insert(type, clock.instant()));
    } catch (DataAccessException | TransactionException e) {
      log.warn("Không ghi được sự kiện thống kê {}: {}", type, e.getClass().getSimpleName());
    }
  }
}
