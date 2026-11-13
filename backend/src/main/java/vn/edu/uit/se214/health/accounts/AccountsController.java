package vn.edu.uit.se214.health.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
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
import vn.edu.uit.se214.health.shared.web.RequiresSession;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/**
 * M5 – UC-01, UC-02, UC-03, UC-18. M5 also owns authentication for every module: replace the
 * fail-closed {@code SessionGuard} with real session validation (e.g. Spring Security).
 * Public routes read the JSON body so malformed input is rejected with 400.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountsController {
  private final AccountRegistrationUseCase registration;

  public AccountsController(AccountRegistrationUseCase registration) {
    this.registration = registration;
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

  @PostMapping("/register/oauth")
  public ResponseEntity<ApiResponse.Success<RegistrationResult>> registerOAuth(
      @RequestBody OAuthRegistrationRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.of(registration.registerOAuth(body)));
  }

  @PostMapping("/login")
  public Object login(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @PostMapping("/forgot-password")
  public Object forgotPassword(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @PostMapping("/reset-password")
  public Object resetPassword(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @PostMapping("/confirm-deletion")
  public Object confirmDeletion(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PostMapping("/logout")
  public Object logout() {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @GetMapping("/me")
  public Object me() {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PutMapping("/me")
  public Object updateMe(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PostMapping("/me/change-password")
  public Object changePassword(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PostMapping("/me/deletion-request")
  public Object requestDeletion() {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @GetMapping("/users")
  public Object users() {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PatchMapping("/users/{id}/status")
  public Object setStatus(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PutMapping("/users/{id}/roles")
  public Object setRoles(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }
}
