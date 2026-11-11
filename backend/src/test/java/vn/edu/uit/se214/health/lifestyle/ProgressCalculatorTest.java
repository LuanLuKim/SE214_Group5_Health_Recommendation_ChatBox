package vn.edu.uit.se214.health.lifestyle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import vn.edu.uit.se214.health.lifestyle.ProgressCalculator.Completion;
import vn.edu.uit.se214.health.lifestyle.ProgressCalculator.DailyProgress;

/** Port of apps/api/tests/lifestyle.test.ts (UC-13). Synthetic fixtures only. */
class ProgressCalculatorTest {
  static final String PLAN = "00000000-0000-4000-8000-000000000001";
  static final LocalDate TODAY = LocalDate.parse("2026-10-30");

  static Completion row(String activity) {
    return new Completion(PLAN, activity, TODAY);
  }

  static Completion row(String activity, String day, String plan) {
    return new Completion(plan, activity, LocalDate.parse(day));
  }

  static Stream<Arguments> rates() {
    return Stream.of(
        Arguments.of(List.of("a", "b", "c", "d"), List.of("a", "c"), 50.0),
        Arguments.of(List.of("a", "b", "c"), List.of("b"), 33.33),
        Arguments.of(List.of("a", "b", "c"), List.of("a", "b", "c"), 100.0),
        Arguments.of(List.of("a"), List.of(), 0.0),
        Arguments.of(List.of(), List.of("a"), 0.0));
  }

  @ParameterizedTest(name = "daily progress: {0} scheduled, rate {2}%")
  @MethodSource("rates")
  void dailyProgress(List<String> scheduled, List<String> completed, double rate) {
    DailyProgress result =
        ProgressCalculator.dailyProgress(
            PLAN, TODAY, scheduled, completed.stream().map(ProgressCalculatorTest::row).toList());
    assertThat(result)
        .isEqualTo(new DailyProgress(scheduled.size(), scheduled.isEmpty() ? 0 : completed.size(), rate));
  }

  @Test
  void dailyProgressDeduplicatesAndExcludesOtherDatesPlansAndActivities() {
    DailyProgress result =
        ProgressCalculator.dailyProgress(
            PLAN,
            TODAY,
            List.of("a", "a", "b"),
            List.of(
                row("a"),
                row("a"),
                row("b", "2026-10-29", PLAN),
                row("b", "2026-10-30", "00000000-0000-4000-8000-000000000002"),
                row("c")));
    assertThat(result).isEqualTo(new DailyProgress(2, 1, 50.0));
  }

  static Stream<Arguments> streaks() {
    return Stream.of(
        Arguments.of("includes today", "2026-10-28,2026-10-29,2026-10-30", "2026-10-30", 3),
        Arguments.of("starts yesterday", "2026-10-28,2026-10-29", "2026-10-30", 2),
        Arguments.of("expired", "2026-10-28", "2026-10-30", 0),
        Arguments.of("stops at the first gap", "2026-10-28,2026-10-30", "2026-10-30", 1),
        Arguments.of("empty", "", "2026-10-30", 0),
        Arguments.of(
            "unordered with duplicates and a future day",
            "2026-10-30,2026-10-28,2026-10-31,2026-10-30,2026-10-29",
            "2026-10-30",
            3),
        Arguments.of("future only", "2026-10-31", "2026-10-30", 0),
        Arguments.of("crosses New Year", "2025-12-31,2026-01-01", "2026-01-01", 2),
        Arguments.of("includes leap day", "2024-02-28,2024-02-29,2024-03-01", "2024-03-01", 3),
        Arguments.of("missing leap day breaks streak", "2024-02-28,2024-03-01", "2024-03-01", 1),
        Arguments.of("February in a common year", "2026-02-28,2026-03-01", "2026-03-01", 2));
  }

  @ParameterizedTest(name = "current streak: {0}")
  @MethodSource("streaks")
  void currentStreak(String description, String dates, String today, int expected) {
    List<LocalDate> days =
        dates.isEmpty() ? List.of() : Arrays.stream(dates.split(",")).map(LocalDate::parse).toList();
    assertThat(ProgressCalculator.currentStreak(days, LocalDate.parse(today))).isEqualTo(expected);
  }

  @Test
  void localMidnightSeparatesConsecutiveDaysEvenOneSecondApart() {
    LocalDate beforeMidnight = ProgressCalculator.localDate(Instant.parse("2026-10-29T16:59:59Z"));
    LocalDate midnight = ProgressCalculator.localDate(Instant.parse("2026-10-29T17:00:00Z"));
    List<LocalDate> dates = List.of(beforeMidnight, midnight, TODAY);
    assertThat(ProgressCalculator.currentStreak(dates, beforeMidnight)).isEqualTo(1);
    assertThat(ProgressCalculator.currentStreak(dates, midnight)).isEqualTo(2);
  }
}
