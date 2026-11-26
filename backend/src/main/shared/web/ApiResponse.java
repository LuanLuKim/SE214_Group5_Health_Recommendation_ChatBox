package shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Response envelopes shared with the frontend ({@code packages/contracts}: ApiSuccess, ApiFailure). */
public final class ApiResponse {
  private ApiResponse() {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Success<T>(T data, Meta meta) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Meta(String status, Integer total, Integer limit, Long offset, Integer totalPages) {
    public Meta(String status, Integer total) {
      this(status, total, null, null, null);
    }
  }

  public record Failure(Error error) {}

  public record Error(String code, String message, String requestId) {}

  public static <T> Success<T> of(T data) {
    return new Success<>(data, null);
  }

  public static <T> Success<T> of(T data, Meta meta) {
    return new Success<>(data, meta);
  }

  public static <T> Success<T> empty() {
    return new Success<>(null, null);
  }
}
