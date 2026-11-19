package vn.edu.uit.se214.health.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Port of apps/api/tests/health.test.ts (UC-04). */
class BmiCalculatorTest {
  @Test
  void calculatesBmiAndRoundsToTwoDecimals() {
    assertThat(BmiCalculator.calculate(170.0, 65.0)).isEqualTo(22.49);
    assertThat(BmiCalculator.calculate(160.0, 64.0)).isEqualTo(25.0);
  }

  static Stream<Arguments> invalid() {
    double nan = Double.NaN;
    double inf = Double.POSITIVE_INFINITY;
    return Stream.of(
        Arguments.of("missing height", null, 65.0),
        Arguments.of("missing weight", 170.0, null),
        Arguments.of("both values missing", null, null),
        Arguments.of("zero height", 0.0, 65.0),
        Arguments.of("zero weight", 170.0, 0.0),
        Arguments.of("negative height", -170.0, 65.0),
        Arguments.of("negative weight", 170.0, -65.0),
        Arguments.of("NaN height", nan, 65.0),
        Arguments.of("NaN weight", 170.0, nan),
        Arguments.of("infinite height", inf, 65.0),
        Arguments.of("infinite weight", 170.0, inf));
  }

  @ParameterizedTest(name = "returns null for {0}")
  @MethodSource("invalid")
  void returnsNullForInvalidInput(String description, Double heightCm, Double weightKg) {
    assertThat(BmiCalculator.calculate(heightCm, weightKg)).isNull();
  }
}
