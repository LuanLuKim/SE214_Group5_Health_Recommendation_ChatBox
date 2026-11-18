package vn.edu.uit.se214.health.lifestyle;

import java.time.LocalDate;
import vn.edu.uit.se214.health.lifestyle.ProgressCalculator.DailyProgress;

/**
 * UC-13 answer to marking or unmarking an activity: the stored state, read back after the write,
 * so the client never shows "saved" for something the database does not hold.
 */
public record CompletionResult(
    String activityId, LocalDate date, boolean completed, int streak, DailyProgress progress) {}
