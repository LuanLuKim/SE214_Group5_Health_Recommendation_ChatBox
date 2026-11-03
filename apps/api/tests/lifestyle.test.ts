import assert from 'node:assert/strict';
import test from 'node:test';
import {
  calculateCurrentStreak,
  calculateDailyProgress,
} from '../src/modules/lifestyle/service.js';

// Synthetic fixture. The same activity ID can occur in multiple plans.
const planId = '00000000-0000-4000-8000-000000000001';
const today = '2026-10-30';
function row(activity_id: string, completed_on = today, plan_id = planId) {
  return { plan_id, activity_id, completed_on };
}

for (const [scheduled, completed, rate] of [
  [['a', 'b', 'c', 'd'], ['a', 'c'], 50],
  [['a', 'b', 'c'], ['b'], 33.33],
  [['a', 'b', 'c'], ['a', 'b', 'c'], 100],
  [['a'], [], 0],
  [[], ['a'], 0],
] as const) {
  test(`daily progress: ${scheduled.length} scheduled, rate ${rate}%`, () => {
    assert.deepEqual(
      calculateDailyProgress(
        planId,
        today,
        scheduled,
        completed.map((id) => row(id)),
      ),
      {
        totalActivities: scheduled.length,
        completedActivities: scheduled.length ? completed.length : 0,
        completionRate: rate,
      },
    );
  });
}

test('daily progress deduplicates both lists and excludes other dates, plans and activities', () => {
  const result = calculateDailyProgress(
    planId,
    today,
    ['a', 'a', 'b'],
    [
      row('a'),
      row('a'),
      row('b', '2026-10-29'),
      row('b', today, '00000000-0000-4000-8000-000000000002'),
      row('c'),
    ],
  );
  assert.deepEqual(result, { totalActivities: 2, completedActivities: 1, completionRate: 50 });
});

for (const [description, dates, asOf, expected] of [
  ['includes today', ['2026-10-28', '2026-10-29', today], today, 3],
  ['starts yesterday', ['2026-10-28', '2026-10-29'], today, 2],
  ['expired', ['2026-10-28'], today, 0],
  ['stops at the first gap', ['2026-10-28', today], today, 1],
  ['empty', [], today, 0],
  [
    'unordered with duplicates and a future day',
    [today, '2026-10-28', '2026-10-31', today, '2026-10-29'],
    today,
    3,
  ],
  ['future only', ['2026-10-31'], today, 0],
  ['crosses New Year', ['2025-12-31', '2026-01-01'], '2026-01-01', 2],
  ['includes leap day', ['2024-02-28', '2024-02-29', '2024-03-01'], '2024-03-01', 3],
  ['missing leap day breaks streak', ['2024-02-28', '2024-03-01'], '2024-03-01', 1],
  ['February in a common year', ['2026-02-28', '2026-03-01'], '2026-03-01', 2],
] as const) {
  test(`current streak: ${description}`, () => {
    assert.equal(calculateCurrentStreak(dates, asOf), expected);
  });
}

test('local midnight separates consecutive days even one second apart', () => {
  const beforeMidnight = new Date('2026-10-29T16:59:59Z');
  const midnight = new Date('2026-10-29T17:00:00Z');
  const dates = [beforeMidnight, midnight, today];
  assert.equal(calculateCurrentStreak(dates, beforeMidnight), 1);
  assert.equal(calculateCurrentStreak(dates, midnight), 2);
});
