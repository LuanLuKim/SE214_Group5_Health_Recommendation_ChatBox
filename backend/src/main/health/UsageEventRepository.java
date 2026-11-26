package health;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import health.api.UsageEventType;

/** SQL for health.usage_events. Rows hold a kind and a time only (no user, no content). */
class UsageEventRepository {
  /** Count of one event kind on one calendar day. */
  record DayCount(LocalDate day, UsageEventType type, long count) {}

  private final JdbcClient jdbc;

  UsageEventRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void insert(UsageEventType type, Instant at) {
    jdbc.sql("INSERT INTO health.usage_events (id, event_type, occurred_at) VALUES (?, ?, ?)")
        .param(UUID.randomUUID())
        .param(type.name())
        .param(OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
        .update();
  }

  /** Events in [from, to) grouped by calendar day in {@code zone} and by kind. */
  List<DayCount> countByDay(Instant from, Instant to, String zone) {
    return jdbc.sql(
            """
            SELECT (occurred_at AT TIME ZONE ?)::date AS day, event_type, count(*) AS total
            FROM health.usage_events
            WHERE occurred_at >= ? AND occurred_at < ?
            GROUP BY 1, 2
            ORDER BY 1, 2
            """)
        .param(zone)
        .param(OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
        .param(OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
        .query(
            (rs, row) ->
                new DayCount(
                    rs.getObject("day", LocalDate.class),
                    UsageEventType.valueOf(rs.getString("event_type")),
                    rs.getLong("total")))
        .list();
  }
}
