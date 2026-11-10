import { ruleDraftInputSchema, type SafetyEvaluator } from '@health/contracts';
import { createRuleRepository, type RuleDatabase } from './repository.js';

// No medical rules have been supplied or approved. This adapter MUST NOT return low risk.
export const unavailableSafetyEvaluator: SafetyEvaluator = {
  async evaluate(_input) {
    return {
      status: 'UNAVAILABLE',
      careLevel: null,
      matchedRuleIds: [],
      missingFields: [],
      specialtyIds: [],
      sources: [],
    };
  },
};

export function createRuleDraftService(db: RuleDatabase) {
  const repository = createRuleRepository(db);
  return {
    create(input: unknown) {
      return repository.create(ruleDraftInputSchema.parse(input));
    },
    list() {
      return repository.list();
    },
    get(id: string) {
      return repository.get(id);
    },
    update(id: string, input: unknown) {
      return repository.update(id, ruleDraftInputSchema.parse(input));
    },
  };
}
