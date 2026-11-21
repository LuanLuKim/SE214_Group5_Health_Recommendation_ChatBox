package vn.edu.uit.se214.health.health;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-19 answer for an ADMIN: counts per calendar day and totals for a date range. Aggregate
 * numbers only, never a user, an email, a chat message or a health value (M-11).
 *
 * @param timezone the zone whose calendar days the counts are grouped by
 * @param hasData false when the range holds no event (A3: empty chart plus {@code message})
 * @param metrics label and unit of each counted series, for the chart legend and axes
 */
public record SystemStatisticsView(
    LocalDate from,
    LocalDate to,
    String timezone,
    boolean hasData,
    Counts totals,
    List<DayStats> series,
    List<Metric> metrics,
    String message) {

  public record Counts(long newAccounts, long visits, long chatInteractions) {}

  public record DayStats(LocalDate date, long newAccounts, long visits, long chatInteractions) {}

  public record Metric(String key, String label, String unit) {}

  SystemStatisticsView inZone(String zone) {
    return new SystemStatisticsView(from, to, zone, hasData, totals, series, metrics, message);
  }
}
