import type { SafetyEvaluator } from '@health/contracts';

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
