package health;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import accounts.api.ConsentQuery;
import health.HealthProfileRepository.ProfileRow;
import health.HealthProfileRepository.WeightRow;
import shared.web.ApiException;

/**
 * UC-04 Quản lý hồ sơ sức khỏe. Reading and saving need the user's consent to health data
 * processing (A1); deleting does not, so a user who withdrew consent can still remove the data.
 * Weight lives in health.daily_metrics: saving the profile records today's weight there and
 * reading returns the latest weight.
 */
public class HealthProfileService {
  static final int HEIGHT_MIN = 50;
  static final int HEIGHT_MAX = 250;
  static final int WEIGHT_MIN = 20;
  static final int WEIGHT_MAX = 300;
  static final int AGE_MIN = 18;
  static final int AGE_MAX = 120;
  static final int ALLERGY_MAX_ITEMS = 20;
  static final int ALLERGY_MAX_LENGTH = 50;
  static final Set<String> SEXES = Set.of("FEMALE", "MALE", "OTHER", "UNSPECIFIED");
  static final String BMI_NOTE = "BMI chỉ là chỉ số tham khảo, không thay thế tư vấn của nhân viên y tế.";

  private final HealthProfileRepository repository;
  private final ConsentQuery consents;
  private final Clock clock;

  public HealthProfileService(HealthProfileRepository repository, ConsentQuery consents, Clock clock) {
    this.repository = repository;
    this.consents = consents;
    this.clock = clock;
  }

  public HealthProfileView get(UUID userId) {
    requireConsent(userId);
    return view(userId);
  }

  /** Full replace. Body: {heightCm, weightKg, age, sex, foodAllergies?}. */
  public HealthProfileView save(UUID userId, JsonNode body) {
    requireConsent(userId);
    Valid input = validate(body);
    try {
      repository.save(
          userId,
          input.heightCm(),
          input.age(),
          input.sex(),
          input.foodAllergies(),
          input.weightKg(),
          LocalDate.now(clock));
    } catch (DataAccessException e) {
      throw HealthErrors.saveFailed();
    }
    return view(userId);
  }

  public void delete(UUID userId) {
    repository.deleteProfile(userId);
  }

  private void requireConsent(UUID userId) {
    if (!consents.healthDataProcessingAccepted(userId)) throw ApiException.healthConsentRequired();
  }

  /** Read back after the write: the answer reflects what is stored, not what was asked. */
  private HealthProfileView view(UUID userId) {
    Optional<ProfileRow> profile = repository.findProfile(userId);
    Optional<WeightRow> weight = repository.latestWeight(userId);
    Double height = profile.map(ProfileRow::heightCm).orElse(null);
    Double weightKg = weight.map(WeightRow::weightKg).orElse(null);
    Double bmi = BmiCalculator.calculate(height, weightKg);
    return new HealthProfileView(
        height,
        weightKg,
        weight.map(WeightRow::recordedOn).orElse(null),
        profile.map(ProfileRow::age).orElse(null),
        profile.map(ProfileRow::sex).orElse(null),
        profile.map(ProfileRow::foodAllergies).orElse(List.of()),
        bmi,
        bmi == null ? null : BMI_NOTE);
  }

  private record Valid(double heightCm, double weightKg, int age, String sex, List<String> foodAllergies) {}

  /** A3: every problem is reported by field name in one message; submitted values are never echoed. */
  private static Valid validate(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw HealthErrors.profileInvalid(
          "Hồ sơ phải là một đối tượng JSON gồm heightCm, weightKg, age, sex và foodAllergies (tùy chọn).");
    }
    List<String> errors = new ArrayList<>();
    Double height =
        number(body, "heightCm", HEIGHT_MIN, HEIGHT_MAX, "Chiều cao (heightCm) là bắt buộc, từ 50 đến 250 cm.", errors);
    Double weight =
        number(body, "weightKg", WEIGHT_MIN, WEIGHT_MAX, "Cân nặng (weightKg) là bắt buộc, từ 20 đến 300 kg.", errors);
    Integer age = age(body, errors);
    String sex = sex(body, errors);
    List<String> allergies = allergies(body, errors);
    if (!errors.isEmpty()) throw HealthErrors.profileInvalid(String.join(" ", errors));
    return new Valid(height, weight, age, sex, allergies);
  }

  private static Double number(JsonNode body, String field, int min, int max, String error, List<String> errors) {
    JsonNode node = body.get(field);
    if (node == null || !node.isNumber()) {
      errors.add(error);
      return null;
    }
    double value = node.asDouble();
    if (!Double.isFinite(value) || value < min || value > max) {
      errors.add(error);
      return null;
    }
    return value;
  }

  private static Integer age(JsonNode body, List<String> errors) {
    JsonNode node = body.get("age");
    if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()
        || node.asInt() < AGE_MIN || node.asInt() > AGE_MAX) {
      errors.add("Tuổi (age) là bắt buộc, số nguyên từ 18 đến 120.");
      return null;
    }
    return node.asInt();
  }

  private static String sex(JsonNode body, List<String> errors) {
    JsonNode node = body.get("sex");
    if (node == null || !node.isTextual() || !SEXES.contains(node.asText())) {
      errors.add("Giới tính (sex) là bắt buộc: FEMALE, MALE, OTHER hoặc UNSPECIFIED.");
      return null;
    }
    return node.asText();
  }

  /** Optional. Trimmed, lower-cased (how UC-12 matches allergens) and de-duplicated. */
  private static List<String> allergies(JsonNode body, List<String> errors) {
    JsonNode node = body.get("foodAllergies");
    if (node == null || node.isNull()) return List.of();
    String error =
        "Dị ứng thực phẩm (foodAllergies) phải là danh sách tối đa 20 mục, mỗi mục từ 1 đến 50 ký tự.";
    if (!node.isArray() || node.size() > ALLERGY_MAX_ITEMS) {
      errors.add(error);
      return List.of();
    }
    Set<String> values = new LinkedHashSet<>();
    for (JsonNode item : node) {
      String text = item.isTextual() ? item.asText().trim().toLowerCase(Locale.ROOT) : "";
      if (text.isEmpty() || text.length() > ALLERGY_MAX_LENGTH) {
        errors.add(error);
        return List.of();
      }
      values.add(text);
    }
    return List.copyOf(values);
  }
}
