package vn.edu.uit.se214.health.accounts.web;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import vn.edu.uit.se214.health.accounts.auth.AccountLoginRequest;
import vn.edu.uit.se214.health.accounts.auth.AccountAuthenticationUseCase;
import vn.edu.uit.se214.health.accounts.auth.AccountLoginResult;
import vn.edu.uit.se214.health.accounts.auth.IssuedAuthTokens;
import vn.edu.uit.se214.health.accounts.auth.PasswordRecoveryAccepted;
import vn.edu.uit.se214.health.accounts.auth.PasswordRecoveryRequest;
import vn.edu.uit.se214.health.accounts.auth.PasswordResetRequest;
import vn.edu.uit.se214.health.accounts.oauth.OAuthSignInRequest;
import vn.edu.uit.se214.health.accounts.registration.AccountRegistrationUseCase;
import vn.edu.uit.se214.health.accounts.registration.EmailOtpResendRequest;
import vn.edu.uit.se214.health.accounts.registration.EmailOtpVerifyRequest;
import vn.edu.uit.se214.health.accounts.registration.EmailRegistrationRequest;
import vn.edu.uit.se214.health.accounts.registration.RegistrationInitiation;
import vn.edu.uit.se214.health.accounts.registration.RegistrationResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.shared.web.ApiResponse;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/**
 * M5 – UC-01, UC-02, UC-03, UC-18. M5 also owns authentication for every module.
 * Public routes read the JSON body so malformed input is rejected with 400.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountsController {
  private final AccountRegistrationUseCase registration;
  private final AccountAuthenticationUseCase authentication;

  public AccountsController(
      AccountRegistrationUseCase registration, AccountAuthenticationUseCase authentication) {
    this.registration = registration;
    this.authentication = authentication;
  }

  @GetMapping
  public ApiResponse.Success<ModuleDescriptor> module() {
    return ApiResponse.of(ModuleRegistry.get("accounts"));
  }

  @PostMapping("/register")
  public ResponseEntity<ApiResponse.Success<RegistrationInitiation>> register(
      @RequestBody EmailRegistrationRequest body) {
    RegistrationInitiation result = registration.startEmailRegistration(body);
    return ResponseEntity.accepted().body(ApiResponse.of(result));
  }

  @PostMapping("/register/verify")
  public ResponseEntity<ApiResponse.Success<RegistrationResult>> verifyEmail(
      @RequestBody EmailOtpVerifyRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.of(registration.verifyEmailRegistration(body)));
  }

  @PostMapping("/register/resend")
  public ResponseEntity<ApiResponse.Success<RegistrationInitiation>> resendEmailVerification(
      @RequestBody EmailOtpResendRequest body) {
    return ResponseEntity.accepted()
        .body(ApiResponse.of(registration.resendEmailVerification(body)));
  }

  @PostMapping("/login")
  public ResponseEntity<ApiResponse.Success<AccountLoginResult>> login(
      @RequestBody AccountLoginRequest body, HttpServletRequest request) {
    return tokenResponse(authentication.login(body), request);
  }

  @PostMapping("/oauth")
  public ResponseEntity<ApiResponse.Success<AccountLoginResult>> oauthSignIn(
      @RequestBody OAuthSignInRequest body, HttpServletRequest request) {
    return tokenResponse(authentication.oauthSignIn(body), request);
  }

  @PostMapping("/refresh")
  public ResponseEntity<ApiResponse.Success<AccountLoginResult>> refresh(
      HttpServletRequest request) {
    String refreshToken = cookieValue(request, REFRESH_COOKIE);
    return tokenResponse(authentication.refresh(refreshToken), request);
  }

  @PostMapping("/forgot-password")
  public ResponseEntity<ApiResponse.Success<PasswordRecoveryAccepted>> forgotPassword(
      @RequestBody PasswordRecoveryRequest body) {
    return ResponseEntity.accepted()
        .body(ApiResponse.of(authentication.requestPasswordRecovery(body)));
  }

  @PostMapping("/reset-password")
  public ResponseEntity<ApiResponse.Success<Void>> resetPassword(
      @RequestBody PasswordResetRequest body) {
    authentication.resetPassword(body);
    return ResponseEntity.ok(ApiResponse.of(null));
  }

  @PostMapping("/confirm-deletion")
  public Object confirmDeletion(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @PostMapping("/logout")
  public ResponseEntity<ApiResponse.Success<Void>> logout(HttpServletRequest request) {
    authentication.logout(cookieValue(request, REFRESH_COOKIE));
    return ResponseEntity.ok()
        .header("Set-Cookie", clearCookie(ACCESS_COOKIE, "/api/v1", request).toString())
        .header(
            "Set-Cookie",
            clearCookie(REFRESH_COOKIE, "/api/v1/accounts", request).toString())
        .body(ApiResponse.of(null));
  }

  @GetMapping("/me")
  public Object me() {
    throw ApiException.notImplemented();
  }

  @PutMapping("/me")
  public Object updateMe(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @PostMapping("/me/change-password")
  public Object changePassword(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @PostMapping("/me/deletion-request")
  public Object requestDeletion() {
    throw ApiException.notImplemented();
  }

  @GetMapping("/users")
  public Object users() {
    throw ApiException.notImplemented();
  }

  @PatchMapping("/users/{id}/status")
  public Object setStatus(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @PutMapping("/users/{id}/roles")
  public Object setRoles(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  private static final String ACCESS_COOKIE = "HA_ACCESS";
  private static final String REFRESH_COOKIE = "HA_REFRESH";

  private static ResponseEntity<ApiResponse.Success<AccountLoginResult>> tokenResponse(
      IssuedAuthTokens tokens, HttpServletRequest request) {
    ResponseCookie access =
        ResponseCookie.from(ACCESS_COOKIE, tokens.accessToken())
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite("Lax")
            .path("/api/v1")
            .maxAge(
                Math.max(
                    0,
                    Math.min(
                        15 * 60,
                        Duration.between(Instant.now(), tokens.account().refreshExpiresAt()).getSeconds())))
            .build();
    ResponseCookie refresh =
        ResponseCookie.from(REFRESH_COOKIE, tokens.refreshToken())
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite("Lax")
            .path("/api/v1/accounts")
            .maxAge(
                Math.max(
                    0,
                    Duration.between(Instant.now(), tokens.account().refreshExpiresAt()).getSeconds()))
            .build();
    return ResponseEntity.ok()
        .header("Set-Cookie", access.toString())
        .header("Set-Cookie", refresh.toString())
        .body(ApiResponse.of(tokens.account()));
  }

  private static ResponseCookie clearCookie(
      String name, String path, HttpServletRequest request) {
    return ResponseCookie.from(name, "")
        .httpOnly(true)
        .secure(request.isSecure())
        .sameSite("Lax")
        .path(path)
        .maxAge(0)
        .build();
  }

  private static String cookieValue(HttpServletRequest request, String name) {
    if (request.getCookies() == null) return null;
    for (var cookie : request.getCookies()) {
      if (name.equals(cookie.getName())) return cookie.getValue();
    }
    return null;
  }
}
