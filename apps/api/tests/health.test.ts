import assert from 'node:assert/strict';
import test from 'node:test';
import { calculateBmi } from '../src/modules/health/service.js';

test('calculates BMI and rounds to two decimal places', () => {
  assert.equal(calculateBmi(170, 65), 22.49);
  assert.equal(calculateBmi(160, 64), 25);
});

for (const [description, heightCm, weightKg] of [
  ['missing height', undefined, 65],
  ['missing weight', 170, undefined],
  ['both values missing', undefined, undefined],
  ['null height', null, 65],
  ['null weight', 170, null],
  ['zero height', 0, 65],
  ['zero weight', 170, 0],
  ['negative height', -170, 65],
  ['negative weight', 170, -65],
  ['NaN height', Number.NaN, 65],
  ['NaN weight', 170, Number.NaN],
  ['infinite height', Number.POSITIVE_INFINITY, 65],
  ['infinite weight', 170, Number.POSITIVE_INFINITY],
] as const) {
  test(`returns null for ${description}`, () => {
    assert.equal(calculateBmi(heightCm, weightKg), null);
  });
}
