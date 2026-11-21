package vn.edu.uit.se214.health.health;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.client.RestClientException;
import vn.edu.uit.se214.health.integrations.PythonServiceClient;

/**
 * UC-19: Spring Boot counts health.usage_events per day for the range, then the Django service
 * (services/stats) fills the empty days and computes the totals. Only dates and counts are sent.
 */
class SystemStatisticsService {
  private static final Logger log = LoggerFactory.getLogger(SystemStatisticsService.class);
  private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  static final String STATS_PATH = "/v1/system/statistics";

  /** Body sent to the Django service. */
  record StatsRequest(LocalDate from, LocalDate to, List<DayRow> days) {}

  record DayRow(LocalDate date, long newAccounts, long visits, long chatInteractions) {}

  /** Envelope of the Django answer. */
  record StatsReply(SystemStatisticsView data) {}

  private final UsageEventRepository events;
  private final PythonServiceClient stats;
  private final UsageStatisticsProperties properties;
  private final ZoneId zone;
  private final Clock clock;

  SystemStatisticsService(
      UsageEventRepository events,
      PythonServiceClient stats,
      UsageStatisticsProperties properties,
      Clock clock) {
    this.events = events;
    this.stats = stats;
    this.properties = properties;
    this.zone = ZoneId.of(properties.zone());
    this.clock = clock;
  }

  SystemStatisticsView statistics(String fromParam, String toParam) {
    LocalDate today = LocalDate.now(clock.withZone(zone));
    LocalDate from = date(fromParam, "from");
    LocalDate to = date(toParam, "to");
    if (to == null) to = from == null ? today : today.isBefore(from) ? from : today;
    if (from == null) from = to.minusDays(properties.defaultDays() - 1L);
    if (from.isAfter(to)) throw StatisticsErrors.rangeInvalid("Ngày bắt đầu không được sau ngày kết thúc.");
    long span = ChronoUnit.DAYS.between(from, to) + 1;
    if (span > properties.maxDays())
      throw StatisticsErrors.rangeInvalid(
          "Khoảng thời gian tối đa là " + properties.maxDays() + " ngày.");

    List<DayRow> days = countDays(from, to);
    StatsReply reply;
    try {
      reply = stats.post(STATS_PATH, new StatsRequest(from, to, days), StatsReply.class);
    } catch (RestClientException | IllegalStateException e) {
      log.warn("Dịch vụ thống kê không trả lời: {}", e.getClass().getSimpleName());
      throw StatisticsErrors.aggregationFailed();
    }
    if (reply == null || reply.data() == null) throw StatisticsErrors.aggregationFailed();
    return reply.data().inZone(zone.getId());
  }

  private List<DayRow> countDays(LocalDate from, LocalDate to) {
    List<UsageEventRepository.DayCount> counts;
    try {
      counts =
          events.countByDay(
              from.atStartOfDay(zone).toInstant(),
              to.plusDays(1).atStartOfDay(zone).toInstant(),
              zone.getId());
    } catch (DataAccessException e) {
      log.warn("Không đọc được sự kiện thống kê: {}", e.getClass().getSimpleName());
      throw StatisticsErrors.aggregationFailed();
    }
    Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
    for (UsageEventRepository.DayCount count : counts) {
      long[] row = byDay.computeIfAbsent(count.day(), d -> new long[3]);
      int column =
          switch (count.type()) {
            case REGISTRATION -> 0;
            case VISIT -> 1;
            case CHAT_INTERACTION -> 2;
          };
      row[column] += count.count();
    }
    List<DayRow> days = new ArrayList<>(byDay.size());
    byDay.forEach((day, row) -> days.add(new DayRow(day, row[0], row[1], row[2])));
    return days;
  }

  /** UC-19 A2. Names the parameter, never echoes its value. */
  private static LocalDate date(String value, String name) {
    if (value == null || value.isBlank()) return null;
    if (!ISO_DATE.matcher(value).matches())
      throw StatisticsErrors.rangeInvalid("Tham số " + name + " phải là ngày theo định dạng yyyy-MM-dd.");
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw StatisticsErrors.rangeInvalid("Tham số " + name + " phải là ngày theo định dạng yyyy-MM-dd.");
    }
  }
}
