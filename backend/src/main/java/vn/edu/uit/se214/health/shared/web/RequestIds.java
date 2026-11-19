package vn.edu.uit.se214.health.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/** Access to the per-request ID set by {@link RequestIdFilter}. */
public final class RequestIds {
  public static final String ATTRIBUTE = RequestIds.class.getName() + ".id";
  public static final String HEADER = "X-Request-ID";

  private RequestIds() {}

  public static String get(HttpServletRequest request) {
    Object id = request.getAttribute(ATTRIBUTE);
    return id == null ? "" : id.toString();
  }
}
