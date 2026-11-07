// M3: UC-04, UC-05, UC-19
// Implement business logic here after the module's access checks and repository are defined.

export function calculateBmi(
  heightCm: number | null | undefined,
  weightKg: number | null | undefined,
): number | null {
  if (
    heightCm == null ||
    weightKg == null ||
    !Number.isFinite(heightCm) ||
    !Number.isFinite(weightKg) ||
    heightCm <= 0 ||
    weightKg <= 0
  ) {
    return null;
  }

  const bmi = weightKg / (heightCm / 100) ** 2;
  if (!Number.isFinite(bmi)) return null;

  const roundedBmi = Math.round(bmi * 100) / 100;
  return Number.isFinite(roundedBmi) ? roundedBmi : null;
}
