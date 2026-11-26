package knowledge.authoring;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import shared.domain.Instants;
import shared.web.ApiException;

/** Validation shared by article and template drafts (UC-15). Every failure is the same 400, input not echoed. */
final class DraftFields {
  private DraftFields() {}

  static ApiException invalid() {
    return ApiException.invalid("INVALID_INPUT", "Nội dung hoặc nguồn tham khảo không hợp lệ.");
  }

  static ApiException notFound(String message) {
    return ApiException.notFound(message);
  }

  static ApiException notEditable() {
    return new ApiException(HttpStatus.CONFLICT, "CONTENT_NOT_EDITABLE",
        "Chỉ được sửa hoặc xóa bản nháp và bản được trả về.");
  }

  static ApiException notSubmittable() {
    return new ApiException(HttpStatus.CONFLICT, "CONTENT_NOT_SUBMITTABLE",
        "Nội dung không ở trạng thái được gửi duyệt.");
  }

  static ApiException changed() {
    return new ApiException(HttpStatus.CONFLICT, "CONTENT_CHANGED", "Nội dung đã thay đổi. Vui lòng tải lại.");
  }

  /** Required text: trimmed, 1..max characters. */
  static String text(String value, int max) {
    if (value == null) throw invalid();
    String trimmed = value.trim();
    if (trimmed.isEmpty() || trimmed.length() > max) throw invalid();
    return trimmed;
  }

  /** Optional text: trimmed, at most max characters, "" when absent. */
  static String optionalText(String value, int max) {
    if (value == null) return "";
    String trimmed = value.trim();
    if (trimmed.length() > max) throw invalid();
    return trimmed;
  }

  /** A list of required texts (each 1..maxEach), at most maxItems; absent is empty. */
  static List<String> texts(List<String> values, int maxItems, int maxEach) {
    if (values == null) return List.of();
    if (values.size() > maxItems) throw invalid();
    List<String> out = new ArrayList<>(values.size());
    for (String value : values) out.add(text(value, maxEach));
    return List.copyOf(out);
  }

  /** HTTP or HTTPS URL with a host, without user name or password, at most 2000 characters. */
  static String url(String value) {
    String trimmed = value == null ? null : value.trim();
    if (trimmed == null || trimmed.isEmpty() || trimmed.length() > 2000) throw invalid();
    try {
      URI uri = new URI(trimmed);
      String scheme = uri.getScheme();
      if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
          && uri.getHost() != null
          && uri.getRawUserInfo() == null) return trimmed;
    } catch (Exception e) {
      // falls through to invalid
    }
    throw invalid();
  }

  /**
   * Date the source was last updated: an ISO date ({@code 2026-10-29}, read as midnight UTC) or an ISO
   * date-time with offset; null when unknown. Returned in the API's ISO form, an impossible date is 400.
   */
  static String sourceDate(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    try {
      OffsetDateTime parsed = trimmed.length() == 10
          ? LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay().atOffset(ZoneOffset.UTC)
          : OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      return Instants.iso(parsed);
    } catch (DateTimeParseException e) {
      throw invalid();
    }
  }

  static OffsetDateTime timestamp(String iso) {
    return iso == null ? null : OffsetDateTime.parse(iso);
  }
}
