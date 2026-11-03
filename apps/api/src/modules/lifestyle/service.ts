// Read PostgreSQL DATE values as YYYY-MM-DD. A Date represents an instant instead.
type CalendarInput = string | Date;

interface Completion {
  plan_id: string;
  activity_id: string;
  completed_on: string;
}

const localDate = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Ho_Chi_Minh',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

function calendarDate(value: CalendarInput): string {
  return typeof value === 'string' ? value : localDate.format(value);
}

export function calculateDailyProgress(
  planId: string,
  day: CalendarInput,
  scheduledActivityIds: readonly string[],
  completions: readonly Completion[],
) {
  const targetDay = calendarDate(day);
  const scheduled = new Set(scheduledActivityIds);
  const completed = new Set<string>();
  for (const row of completions) {
    if (
      row.plan_id === planId &&
      row.completed_on === targetDay &&
      scheduled.has(row.activity_id)
    ) {
      completed.add(row.activity_id);
    }
  }

  return {
    totalActivities: scheduled.size,
    completedActivities: completed.size,
    completionRate:
      scheduled.size === 0 ? 0 : Math.round((completed.size * 10_000) / scheduled.size) / 100,
  };
}

// Use UTC midnight only to number calendar days, after resolving the local date.
function dayNumber(value: CalendarInput): number {
  return Date.parse(calendarDate(value) + 'T00:00:00Z') / 86_400_000;
}

/** completionDates must belong to a single plan and activity. */
export function calculateCurrentStreak(
  completionDates: readonly CalendarInput[],
  today: CalendarInput,
): number {
  const currentDay = dayNumber(today);
  const completed = new Set<number>();
  for (const value of completionDates) {
    const day = dayNumber(value);
    if (day <= currentDay) completed.add(day);
  }

  let cursor = completed.has(currentDay) ? currentDay : currentDay - 1;
  let streak = 0;
  while (completed.has(cursor)) {
    streak++;
    cursor--;
  }
  return streak;
}
