package vn.edu.uit.se214.health.lifestyle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.uit.se214.health.lifestyle.ProgressCalculator.Completion;
import vn.edu.uit.se214.health.lifestyle.ProgressCalculator.DailyProgress;
import vn.edu.uit.se214.health.lifestyle.TodayPlan.ActivityToday;

/**
 * UC-13 Theo dõi thói quen: today's activities of the active plan, marking them done per local
 * day, and streaks. "Today" is the calendar day of {@code clock}'s zone (app.lifestyle.zone).
 *
 * <p>Policy: an activity can be marked for any day from the plan's start up to today; it can only
 * be unmarked today, so past days stay as recorded.
 */
public class LifestyleService {
  private static final Logger log = LoggerFactory.getLogger(LifestyleService.class);
  private static final TypeReference<List<PlanActivity>> ACTIVITIES = new TypeReference<>() {};

  private final LifestyleRepository repository;
  private final ObjectMapper json;
  private final Clock clock;

  public LifestyleService(LifestyleRepository repository, ObjectMapper json, Clock clock) {
    this.repository = repository;
    this.json = json;
    this.clock = clock;
  }

  public TodayPlan today(UUID userId) {
    ActivePlan plan = activePlan(userId);
    LocalDate today = LocalDate.now(clock);
    List<PlanActivity> activities = activities(plan);
    List<Completion> completions = history(plan, today);

    List<ActivityToday> rows = new ArrayList<>();
    for (PlanActivity activity : activities) {
      List<LocalDate> days = daysOf(activity.id(), completions);
      rows.add(
          new ActivityToday(
              activity.id(),
              activity.title(),
              days.contains(today),
              ProgressCalculator.currentStreak(days, today)));
    }
    return new TodayPlan(
        plan.id().toString(),
        plan.templateId().toString(),
        plan.templateTitle(),
        plan.startedOn(),
        today,
        progress(plan, today, activities, completions),
        ProgressCalculator.currentStreak(fullyCompletedDays(activities, completions), today),
        rows);
  }

  public CompletionResult complete(UUID userId, String activityId, String date) {
    ActivePlan plan = activePlan(userId);
    LocalDate today = LocalDate.now(clock);
    LocalDate day = completionDay(date, plan, today);
    List<PlanActivity> activities = activities(plan);
    requireActivity(activities, activityId);
    repository.insertCompletion(plan.id(), activityId, day);
    return result(plan, activities, activityId, day, today);
  }

  public CompletionResult uncomplete(UUID userId, String activityId, String date) {
    ActivePlan plan = activePlan(userId);
    LocalDate today = LocalDate.now(clock);
    LocalDate day = completionDay(date, plan, today);
    List<PlanActivity> activities = activities(plan);
    requireActivity(activities, activityId);
    if (!day.equals(today)) throw LifestyleErrors.completionLocked();
    repository.deleteCompletion(plan.id(), activityId, day);
    return result(plan, activities, activityId, day, today);
  }

  private ActivePlan activePlan(UUID userId) {
    return repository.findActivePlan(userId).orElseThrow(LifestyleErrors::planNotFound);
  }

  /** Read back after the write: the answer reflects what is stored, not what was asked. */
  private CompletionResult result(
      ActivePlan plan, List<PlanActivity> activities, String activityId, LocalDate day, LocalDate today) {
    List<Completion> completions = history(plan, today);
    List<LocalDate> days = daysOf(activityId, completions);
    return new CompletionResult(
        activityId,
        day,
        days.contains(day),
        ProgressCalculator.currentStreak(days, today),
        progress(plan, day, activities, completions));
  }

  private List<Completion> history(ActivePlan plan, LocalDate today) {
    if (plan.startedOn().isAfter(today)) return List.of();
    return repository.completions(plan.id(), plan.startedOn(), today);
  }

  private static DailyProgress progress(
      ActivePlan plan, LocalDate day, List<PlanActivity> activities, List<Completion> completions) {
    return ProgressCalculator.dailyProgress(
        plan.id().toString(), day, activities.stream().map(PlanActivity::id).toList(), completions);
  }

  private static LocalDate completionDay(String value, ActivePlan plan, LocalDate today) {
    LocalDate day;
    try {
      day = LocalDate.parse(value == null ? "" : value);
    } catch (DateTimeParseException e) {
      throw LifestyleErrors.invalidDate();
    }
    if (day.isAfter(today) || day.isBefore(plan.startedOn())) throw LifestyleErrors.invalidDate();
    return day;
  }

  private static void requireActivity(List<PlanActivity> activities, String activityId) {
    if (activities.stream().noneMatch(a -> a.id().equals(activityId)))
      throw LifestyleErrors.activityNotInPlan();
  }

  /**
   * Activities with an id, first occurrence wins. A template whose JSON cannot be read has no
   * activities; only its id is logged, never its content.
   */
  private List<PlanActivity> activities(ActivePlan plan) {
    List<PlanActivity> parsed;
    try {
      parsed = plan.activitiesJson() == null ? List.of() : json.readValue(plan.activitiesJson(), ACTIVITIES);
    } catch (Exception e) {
      log.warn("Lifestyle template {} has unreadable activities", plan.templateId());
      return List.of();
    }
    Map<String, PlanActivity> unique = new LinkedHashMap<>();
    for (PlanActivity activity : parsed) {
      if (activity == null || activity.id() == null || activity.id().isBlank()) continue;
      unique.putIfAbsent(activity.id(), activity);
    }
    return List.copyOf(unique.values());
  }

  private static List<LocalDate> daysOf(String activityId, List<Completion> completions) {
    return completions.stream()
        .filter(c -> c.activityId().equals(activityId))
        .map(Completion::completedOn)
        .toList();
  }

  /** Days on which every current activity of the plan was completed. */
  private static List<LocalDate> fullyCompletedDays(
      List<PlanActivity> activities, List<Completion> completions) {
    if (activities.isEmpty()) return List.of();
    Set<String> required = new HashSet<>(activities.stream().map(PlanActivity::id).toList());
    Map<LocalDate, Set<String>> done = new HashMap<>();
    for (Completion c : completions)
      if (required.contains(c.activityId()))
        done.computeIfAbsent(c.completedOn(), d -> new HashSet<>()).add(c.activityId());
    return done.entrySet().stream()
        .filter(e -> e.getValue().size() == required.size())
        .map(Map.Entry::getKey)
        .toList();
  }
}
