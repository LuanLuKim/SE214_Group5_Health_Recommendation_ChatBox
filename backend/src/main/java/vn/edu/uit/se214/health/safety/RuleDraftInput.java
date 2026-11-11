package vn.edu.uit.se214.health.safety;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import vn.edu.uit.se214.health.safety.api.CareLevel;
import vn.edu.uit.se214.health.shared.web.ApiException;

/**
 * UC-16 authoring input (same rules as ruleDraftInputSchema in packages/contracts).
 * A stored draft is inert until it is approved through the shared review queue (UC-17).
 */
public record RuleDraftInput(
    String name,
    Conditions conditions,
    List<String> requiredFields,
    CareLevel careLevel,
    List<String> specialtyIds,
    String sourceName,
    String sourceUrl) {

  public record Conditions(List<String> all, List<String> any) {}

  /** Returns a trimmed copy, or throws 400 INVALID_RULE_DRAFT without echoing the input. */
  public RuleDraftInput validated() {
    String cleanName = text(name, 200);
    if (conditions == null) throw invalid();
    List<String> all = terms(conditions.all(), 1);
    List<String> any = terms(conditions.any(), 0);
    List<String> required = terms(requiredFields, 0);
    List<String> specialties = terms(specialtyIds, 0);
    if (careLevel == null) throw invalid();
    String cleanSourceName = text(sourceName, 200);
    String url = sourceUrl == null ? null : sourceUrl.trim();
    if (!isHttpUrlWithoutCredentials(url)) throw invalid();
    return new RuleDraftInput(
        cleanName, new Conditions(all, any), required, careLevel, specialties, cleanSourceName, url);
  }

  private static String text(String value, int max) {
    if (value == null) throw invalid();
    String trimmed = value.trim();
    if (trimmed.isEmpty() || trimmed.length() > max) throw invalid();
    return trimmed;
  }

  private static List<String> terms(List<String> values, int min) {
    if (values == null || values.size() < min || values.size() > 20) throw invalid();
    List<String> out = new ArrayList<>(values.size());
    for (String value : values) out.add(text(value, 120));
    return List.copyOf(out);
  }

  static boolean isHttpUrlWithoutCredentials(String value) {
    if (value == null || value.isEmpty() || value.length() > 2000) return false;
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme();
      return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
          && uri.getHost() != null
          && uri.getRawUserInfo() == null;
    } catch (Exception e) {
      return false;
    }
  }

  private static ApiException invalid() {
    return ApiException.invalid("INVALID_RULE_DRAFT", "Bản nháp quy tắc không hợp lệ.");
  }
}
