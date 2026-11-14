package vn.edu.uit.se214.health.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import vn.edu.uit.se214.health.support.TestDatabase;

/** Port of apps/api/tests/database.test.ts: every migration runs, the seed is idempotent, constraints hold. */
class DatabaseMigrationTest {
  @Test
  void allMigrationsExecuteSyntheticSeedIsIdempotentAndConstraintsHold() throws Exception {
    DataSource dataSource = TestDatabase.fresh();
    JdbcTemplate db = new JdbcTemplate(dataSource);

    int applied =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:" + TestDatabase.DATABASE_DIR.resolve("migrations"))
            .load()
            .info()
            .applied()
            .length;
    assertThat(applied).isEqualTo(12);

    List<Path> seeds = SyntheticSeedRunner.sqlFiles(TestDatabase.DATABASE_DIR.resolve("seeds"));
    for (int run = 0; run < 2; run++) {
      var populator = new ResourceDatabasePopulator();
      populator.setSqlScriptEncoding("UTF-8");
      for (Path seed : seeds) populator.addScript(new FileSystemResource(seed));
      populator.execute(dataSource);
    }

    assertThat(count(db, "SELECT count(*) FROM accounts.users")).isEqualTo(3);
    assertThat(count(db, "SELECT count(*) FROM knowledge.articles WHERE review_status = 'APPROVED'")).isEqualTo(1);
    assertThat(count(db, "SELECT count(*) FROM safety.rules WHERE review_status = 'APPROVED'")).isZero();
    // UC-16: one rule per authoring state for the UI, all synthetic, none loadable.
    assertThat(count(db, "SELECT count(*) FROM safety.rules")).isEqualTo(4);
    assertThat(count(db, "SELECT count(*) FROM safety.rules WHERE NOT is_synthetic")).isZero();
    assertThat(count(db, "SELECT count(DISTINCT review_status) FROM safety.rules")).isEqualTo(3);

    assertRejected(db, "INSERT INTO accounts.user_roles VALUES ('00000000-0000-4000-8000-999999999999','USER')");
    assertRejected(db, "INSERT INTO health.daily_metrics VALUES ('00000000-0000-4000-8000-000000000001','2026-10-26',65,30)");
    assertRejected(db, "INSERT INTO chat.feedback VALUES ('00000000-0000-4000-8000-000000000099','OTHER','', 'unpaired',NULL,now())");

    // UC-14: seeded reminder gets every day by default; invalid schedules and duplicate sends are rejected.
    Map<String, Object> reminder =
        db.queryForMap(
            "SELECT array_to_string(days_of_week, ',') AS days, enabled FROM lifestyle.reminders WHERE id = '00000000-0000-4000-8000-000000000052'");
    assertThat(reminder).containsEntry("days", "1,2,3,4,5,6,7").containsEntry("enabled", false);
    String columns =
        "INSERT INTO lifestyle.reminders (id, user_id, content, local_time, channel, days_of_week) VALUES ('00000000-0000-4000-8000-000000000098','00000000-0000-4000-8000-000000000001'";
    assertRejected(db, columns + ", '   ', '07:00', 'EMAIL', '{1}')");
    assertRejected(db, columns + ", 'Uống nước', '07:00', 'EMAIL', '{}')");
    assertRejected(db, columns + ", 'Uống nước', '07:00', 'EMAIL', '{0,8}')");
    assertRejected(db, columns + ", 'Uống nước', '07:00', 'SMS', '{1}')");

    String delivery =
        "INSERT INTO lifestyle.reminder_deliveries (id, reminder_id, scheduled_for, channel) VALUES ('%s','00000000-0000-4000-8000-000000000052','2026-11-17T13:00:00Z','EMAIL')";
    db.execute(delivery.formatted("00000000-0000-4000-8000-000000000060"));
    assertRejected(db, delivery.formatted("00000000-0000-4000-8000-000000000061"));
    assertRejected(db, "UPDATE lifestyle.reminder_deliveries SET status = 'SENT' WHERE id = '00000000-0000-4000-8000-000000000060'");
    db.execute(
        "UPDATE lifestyle.reminder_deliveries SET status = 'SENT', sent_at = now(), attempt_count = 1 WHERE id = '00000000-0000-4000-8000-000000000060'");
    db.execute("DELETE FROM lifestyle.reminders WHERE id = '00000000-0000-4000-8000-000000000052'");
    assertThat(count(db, "SELECT count(*) FROM lifestyle.reminder_deliveries")).isZero();
  }

  private static int count(JdbcTemplate db, String sql) {
    Integer value = db.queryForObject(sql, Integer.class);
    return value == null ? 0 : value;
  }

  private static void assertRejected(JdbcTemplate db, String sql) {
    assertThatThrownBy(() -> db.execute(sql)).isInstanceOf(Exception.class);
  }
}
