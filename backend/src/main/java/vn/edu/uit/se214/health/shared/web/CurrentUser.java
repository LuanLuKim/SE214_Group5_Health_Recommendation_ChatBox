package vn.edu.uit.se214.health.shared.web;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The signed-in user of a private route: the subject of the access token that Spring Security has
 * already verified. Controllers take it with {@code @AuthenticationPrincipal Jwt principal}; the
 * user is never read from a header, body or path the client controls.
 */
public final class CurrentUser {
  private CurrentUser() {}

  /** Fail closed: without a verified UUID subject the request is rejected, never mapped to a default user. */
  public static UUID id(Jwt principal) {
    if (principal == null || principal.getSubject() == null) throw ApiException.sessionInvalid();
    try {
      return UUID.fromString(principal.getSubject());
    } catch (IllegalArgumentException e) {
      throw ApiException.sessionInvalid();
    }
  }
}
