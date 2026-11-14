package vn.edu.uit.se214.health.accounts;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.http.MediaType;
import vn.edu.uit.se214.health.accounts.oauth.OAuthProvider;
import vn.edu.uit.se214.health.accounts.oauth.OAuthProviderVerifier;
import vn.edu.uit.se214.health.accounts.oauth.VerifiedOAuthIdentity;
import vn.edu.uit.se214.health.accounts.registration.EmailOtpSender;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.support.TestDatabase;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class AccountRegistrationTest {
  private static final String DATABASE = "accounts_registration_test";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired PasswordEncoder passwordEncoder;
  @MockitoBean OAuthProviderVerifier oauthVerifier;
  @MockitoBean EmailOtpSender emailOtpSender;
  private String csrfToken;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (Connection connection = TestDatabase.server().getPostgresDatabase().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + DATABASE);
    }
    registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl(DATABASE));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.data-mode", () -> "postgres");
    registry.add("app.seed-synthetic", () -> "false");
    registry.add("app.accounts.terms-version", () -> "v1");
    registry.add("app.accounts.google-client-id", () -> "test-google-client");
    registry.add("app.accounts.facebook-app-id", () -> "test-facebook-app");
    registry.add("app.accounts.facebook-app-secret", () -> "test-facebook-secret");
    registry.add("app.accounts.email-otp.hash-secret", () -> "test-only-hmac-secret-with-at-least-32-bytes");
    registry.add("app.accounts.email-otp.from-address", () -> "no-reply@example.test");
    registry.add("app.accounts.auth-token.secret", () -> "test-only-auth-token-secret-at-least-32-bytes");
  }

  @BeforeEach
  void cleanTestAccounts() throws Exception {
    reset(oauthVerifier);
    reset(emailOtpSender);
    jdbc.sql("DELETE FROM accounts.users WHERE email LIKE 'uc01-%@example.test'").update();
    jdbc.sql("DELETE FROM accounts.users WHERE email LIKE 'uc02-%@example.test'").update();
    jdbc.sql("DELETE FROM accounts.registration_challenges WHERE email LIKE 'uc01-%@example.test'").update();
    csrfToken =
        cookieValue(
            mvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/csrf"))
                .andReturn(),
            "XSRF-TOKEN");
  }

  @Test
  void springSecurityRequiresValidAccessJwtCookieForPrivateRoutes() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/accounts/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("SESSION_INVALID"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/accounts/me")
                .cookie(new Cookie("HA_ACCESS", "invalid-token")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("SESSION_INVALID"));
  }

  @Test
  void invalidAccessCookieDoesNotBlockPublicLogin() throws Exception {
    insertAccount("uc02-stale-cookie@example.test", "correct-password");
    mvc.perform(
            post("/api/v1/accounts/login")
                .cookie(new Cookie("HA_ACCESS", "expired-token"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"uc02-stale-cookie@example.test\","
                        + "\"password\":\"correct-password\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void csrfIsRequiredEvenWhenAccessJwtCookieIsPresent() throws Exception {
    insertAccount("uc02-csrf@example.test", "correct-password");
    MvcResult loginResult =
        login("uc02-csrf@example.test", "correct-password")
            .andExpect(status().isOk())
            .andReturn();

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/accounts/me")
                .cookie(new Cookie("HA_ACCESS", cookieValue(loginResult, "HA_ACCESS")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
  }

  @Test
  void csrfEndpointIssuesCookieAndUnsafeRequestsRequireCsrfToken() throws Exception {
    MvcResult csrfResult =
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/csrf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.token").isNotEmpty())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie()
                .exists("XSRF-TOKEN"))
        .andReturn();

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/accounts/logout")
                .cookie(new Cookie("HA_REFRESH", "not-a-real-token")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));

    String csrfToken = cookieValue(csrfResult, "XSRF-TOKEN");
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/accounts/logout")
                .cookie(new Cookie("HA_REFRESH", "not-a-real-token"))
                .cookie(new Cookie("XSRF-TOKEN", csrfToken))
                .header("X-XSRF-TOKEN", csrfToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_INVALID"));
  }

  @Test
  void emailRegistrationNormalizesEmailHashesPasswordAndPersistsConsentAndUserRole()
      throws Exception {
    postEmail(emailRequest("  UC01-Email@Example.Test  ", consent(true, "v1")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.email").value("uc01-email@example.test"))
        .andExpect(jsonPath("$.data.expiresAt").exists())
        .andExpect(jsonPath("$.data.code").doesNotExist());

    String passwordHash =
        jdbc.sql("SELECT password_hash FROM accounts.registration_challenges WHERE email = :email")
            .param("email", "uc01-email@example.test")
            .query(String.class)
            .single();
    org.assertj.core.api.Assertions.assertThat(passwordHash)
        .startsWith("$2")
        .doesNotContain("test-password-123");

    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT count(*) FROM accounts.users WHERE email = :email")
                .param("email", "uc01-email@example.test")
                .query(Integer.class)
                .single())
        .isZero();
    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    verify(emailOtpSender)
        .sendVerificationCode(eq("uc01-email@example.test"), code.capture(), eq(10));
    org.assertj.core.api.Assertions.assertThat(code.getValue()).matches("\\d{6}");
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT otp_hash FROM accounts.registration_challenges WHERE email = :email")
                .param("email", "uc01-email@example.test")
                .query(String.class)
                .single())
        .doesNotContain(code.getValue());

    verifyEmail("uc01-email@example.test", code.getValue())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.email").value("uc01-email@example.test"))
        .andExpect(jsonPath("$.data.password").doesNotExist());
    passwordHash =
        jdbc.sql("SELECT password_hash FROM accounts.users WHERE email = :email")
            .param("email", "uc01-email@example.test")
            .query(String.class)
            .single();
    org.assertj.core.api.Assertions.assertThat(passwordHash)
        .startsWith("$2")
        .doesNotContain("test-password-123");
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM accounts.consents
                    WHERE user_id = (SELECT id FROM accounts.users WHERE email = :email)
                      AND terms_version = 'v1' AND adult_confirmed
                      AND health_data_processing_accepted AND non_diagnostic_acknowledged
                      AND accepted_at IS NOT NULL
                    """)
                .param("email", "uc01-email@example.test")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM accounts.user_roles
                    WHERE user_id = (SELECT id FROM accounts.users WHERE email = :email)
                      AND role = 'USER'
                    """)
                .param("email", "uc01-email@example.test")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void normalizedDuplicateEmailIsRejected() throws Exception {
    postEmail(emailRequest("uc01-duplicate@example.test", consent(true, "v1")))
        .andExpect(status().isAccepted());
    verifyEmail("uc01-duplicate@example.test", sentCode("uc01-duplicate@example.test"))
        .andExpect(status().isCreated());

    postEmail(emailRequest(" UC01-DUPLICATE@EXAMPLE.TEST ", consent(true, "v1")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));
  }

  @Test
  void invalidEmailMissingConsentUnderageAndWrongTermsVersionAreRejected() throws Exception {
    postEmail("null")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    postEmail(emailRequest("not-an-email", consent(true, "v1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_EMAIL"));
    postEmail(emailRequest("uc01-no-consent@example.test", consent(false, "v1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("CONSENT_REQUIRED"));
    postEmail(emailRequest("uc01-underage@example.test", consent(true, "v1", false)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("ADULT_CONFIRMATION_REQUIRED"));
    postEmail(emailRequest("uc01-wrong-terms@example.test", consent(true, "old-version")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("TERMS_VERSION_INVALID"));

    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT count(*) FROM accounts.users WHERE email LIKE 'uc01-%@example.test'")
                .query(Integer.class)
                .single())
        .isZero();
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM accounts.registration_challenges WHERE email LIKE 'uc01-%@example.test'")
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void verificationEnforcesAttemptLimitAndResendCooldown() throws Exception {
    postEmail(emailRequest("uc01-attempts@example.test", consent(true, "v1")))
        .andExpect(status().isAccepted());
    String email = "uc01-attempts@example.test";
    for (int attempt = 1; attempt < 5; attempt++)
      verifyEmail(email, "wrong-code")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("OTP_INVALID"));
    verifyEmail(email, "wrong-code")
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code").value("OTP_ATTEMPTS_EXHAUSTED"));
    verifyEmail(email, "wrong-code")
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code").value("OTP_ATTEMPTS_EXHAUSTED"));
    mvc.perform(
            post("/api/v1/accounts/register/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"uc01-attempts@example.test\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code").value("OTP_RESEND_TOO_SOON"));

    jdbc.sql(
            "UPDATE accounts.registration_challenges SET resend_after = now() - interval '1 second' WHERE email = :email")
        .param("email", email)
        .update();
    mvc.perform(
            post("/api/v1/accounts/register/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"uc01-attempts@example.test\"}"))
        .andExpect(status().isAccepted());
    String resentCode = sentLatestCode(email);
    verifyEmail(email, resentCode).andExpect(status().isCreated());
  }

  @Test
  void expiredOtpIsRejectedAndEmailDeliveryFailureLeavesNoChallenge() throws Exception {
    String email = "uc01-expired@example.test";
    postEmail(emailRequest(email, consent(true, "v1"))).andExpect(status().isAccepted());
    jdbc.sql(
            "UPDATE accounts.registration_challenges SET expires_at = now() - interval '1 second' WHERE email = :email")
        .param("email", email)
        .update();
    verifyEmail(email, sentCode(email))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("OTP_EXPIRED"));

    doThrow(ApiException.emailDeliveryFailed())
        .when(emailOtpSender)
        .sendVerificationCode(eq("uc01-mail-failure@example.test"), org.mockito.ArgumentMatchers.any(), eq(10));
    postEmail(emailRequest("uc01-mail-failure@example.test", consent(true, "v1")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("EMAIL_DELIVERY_FAILED"));
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM accounts.registration_challenges
                    WHERE email IN ('uc01-expired@example.test', 'uc01-mail-failure@example.test')
                    """)
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void emailLoginRotatesRefreshTokenAndLogoutRevokesRefreshButNotIssuedAccessJwt() throws Exception {
    UUID userId = insertAccount("uc02-token@example.test", "correct-password");
    MvcResult login =
        login("uc02-token@example.test", "correct-password")
            .andExpect(status().isOk())
            .andReturn();
    String accessToken = cookieValue(login, "HA_ACCESS");
    String refreshToken = cookieValue(login, "HA_REFRESH");
    org.assertj.core.api.Assertions.assertThat(accessToken).isNotBlank();
    org.assertj.core.api.Assertions.assertThat(refreshToken).isNotBlank();
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT count(*) FROM accounts.refresh_tokens WHERE user_id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single())
        .isEqualTo(1);

    MvcResult refreshed =
        mvc.perform(post("/api/v1/accounts/refresh").cookie(new Cookie("HA_REFRESH", refreshToken)))
            .andExpect(status().isOk())
            .andReturn();
    String rotatedRefreshToken = cookieValue(refreshed, "HA_REFRESH");
    org.assertj.core.api.Assertions.assertThat(rotatedRefreshToken).isNotEqualTo(refreshToken);
    mvc.perform(post("/api/v1/accounts/refresh").cookie(new Cookie("HA_REFRESH", refreshToken)))
        .andExpect(status().isUnauthorized());

    mvc.perform(
            post("/api/v1/accounts/logout")
                .cookie(new Cookie("HA_REFRESH", rotatedRefreshToken)))
        .andExpect(status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/accounts/me")
                .cookie(new Cookie("HA_ACCESS", accessToken)))
        .andExpect(status().isNotImplemented());
    mvc.perform(
            post("/api/v1/accounts/refresh")
                .cookie(new Cookie("HA_REFRESH", rotatedRefreshToken)))
        .andExpect(status().isUnauthorized());
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    "SELECT revoked_at IS NOT NULL FROM accounts.refresh_tokens WHERE user_id = :userId")
                .param("userId", userId)
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void failedLoginLimitLocksAccountAndLockedAccountDoesNotAccumulateAttempts() throws Exception {
    UUID userId = insertAccount("uc02-lock@example.test", "correct-password");
    for (int attempt = 0; attempt < 5; attempt++)
      login("uc02-lock@example.test", "wrong-password").andExpect(status().isUnauthorized());

    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    "SELECT failed_login_count = 5 AND login_locked_until > now() FROM accounts.users WHERE id = :userId")
                .param("userId", userId)
                .query(Boolean.class)
                .single())
        .isTrue();
    login("uc02-lock@example.test", "correct-password").andExpect(status().isUnauthorized());
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT failed_login_count FROM accounts.users WHERE id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single())
        .isEqualTo(5);

    UUID disabledUserId =
        insertAccount("uc02-disabled@example.test", "correct-password", "LOCKED");
    login("uc02-disabled@example.test", "correct-password").andExpect(status().isUnauthorized());
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT failed_login_count FROM accounts.users WHERE id = :userId")
                .param("userId", disabledUserId)
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void recoveryCodeResetsPasswordOnceAndRevokesRefreshTokens() throws Exception {
    UUID userId = insertAccount("uc02-recovery@example.test", "old-password");
    MvcResult login =
        login("uc02-recovery@example.test", "old-password")
            .andExpect(status().isOk())
            .andReturn();
    String accessToken = cookieValue(login, "HA_ACCESS");
    String refreshToken = cookieValue(login, "HA_REFRESH");

    mvc.perform(
            post("/api/v1/accounts/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"uc02-recovery@example.test\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.message").exists());
    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    verify(emailOtpSender)
        .sendPasswordRecoveryCode(eq("uc02-recovery@example.test"), code.capture(), eq(10));
    org.assertj.core.api.Assertions.assertThat(code.getValue()).matches("\\d{6}");

    String resetBody =
        """
        {"email":"uc02-recovery@example.test","code":"%s","newPassword":"new-password-123"}
        """
            .formatted(code.getValue());
    mvc.perform(
            post("/api/v1/accounts/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody))
        .andExpect(status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/accounts/me")
                .cookie(new Cookie("HA_ACCESS", accessToken)))
        .andExpect(status().isNotImplemented());
    mvc.perform(post("/api/v1/accounts/refresh").cookie(new Cookie("HA_REFRESH", refreshToken)))
        .andExpect(status().isUnauthorized());
    login("uc02-recovery@example.test", "old-password").andExpect(status().isUnauthorized());
    login("uc02-recovery@example.test", "new-password-123").andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/accounts/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("RECOVERY_CODE_INVALID"));
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM accounts.refresh_tokens WHERE user_id = :userId AND revoked_at IS NOT NULL")
                .param("userId", userId)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void recoveryResponseIsGenericAndExpiredRefreshTokenCannotBeRenewed()
      throws Exception {
    mvc.perform(
            post("/api/v1/accounts/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"uc02-unknown@example.test\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.message").exists());

    UUID userId = insertAccount("uc02-expired@example.test", "correct-password");
    MvcResult login =
        login("uc02-expired@example.test", "correct-password")
            .andExpect(status().isOk())
            .andReturn();
    String accessToken = cookieValue(login, "HA_ACCESS");
    String refreshToken = cookieValue(login, "HA_REFRESH");
    jdbc.sql(
            "UPDATE accounts.refresh_tokens SET expires_at = now() - interval '1 second' WHERE user_id = :userId")
        .param("userId", userId)
        .update();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/accounts/me")
                .cookie(new Cookie("HA_ACCESS", accessToken)))
        .andExpect(status().isNotImplemented());
    mvc.perform(post("/api/v1/accounts/refresh").cookie(new Cookie("HA_REFRESH", refreshToken)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void recoveryCodeExpiresAndCannotBeUsedAfterFiveIncorrectAttempts() throws Exception {
    String attemptsEmail = "uc02-recovery-attempts@example.test";
    insertAccount(attemptsEmail, "old-password");
    requestRecovery(attemptsEmail).andExpect(status().isAccepted());
    ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
    verify(emailOtpSender)
        .sendPasswordRecoveryCode(eq(attemptsEmail), codes.capture(), eq(10));
    for (int attempt = 0; attempt < 5; attempt++)
      resetPassword(attemptsEmail, "wrong-code", "new-password-123")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("RECOVERY_CODE_INVALID"));
    resetPassword(attemptsEmail, codes.getValue(), "new-password-123")
        .andExpect(status().isBadRequest());
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    "SELECT attempt_count FROM accounts.password_recovery_challenges WHERE user_id = (SELECT id FROM accounts.users WHERE email = :email)")
                .param("email", attemptsEmail)
                .query(Integer.class)
                .single())
        .isEqualTo(5);

    String expiredEmail = "uc02-recovery-expired@example.test";
    UUID expiredUserId = insertAccount(expiredEmail, "old-password");
    requestRecovery(expiredEmail).andExpect(status().isAccepted());
    ArgumentCaptor<String> expiredCodes = ArgumentCaptor.forClass(String.class);
    verify(emailOtpSender)
        .sendPasswordRecoveryCode(eq(expiredEmail), expiredCodes.capture(), eq(10));
    String expiredCode = expiredCodes.getValue();
    jdbc.sql(
            "UPDATE accounts.password_recovery_challenges SET expires_at = now() - interval '1 second' WHERE user_id = :userId")
        .param("userId", expiredUserId)
        .update();
    resetPassword(expiredEmail, expiredCode, "new-password-123")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("RECOVERY_CODE_INVALID"));
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM accounts.password_recovery_challenges WHERE user_id = :userId")
                .param("userId", expiredUserId)
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void oauthOneButtonCreatesThenAuthenticatesIndependentOAuthAccount() throws Exception {
    VerifiedOAuthIdentity identity =
        new VerifiedOAuthIdentity(
            OAuthProvider.GOOGLE, "uc02-google-subject", "uc02-oauth@example.test", "OAuth Test");
    doReturn(identity).when(oauthVerifier).verify(OAuthProvider.GOOGLE, "google-token");
    mvc.perform(
            post("/api/v1/accounts/oauth")
                .contentType(MediaType.APPLICATION_JSON)
                .content(oauthSignInRequest("GOOGLE", "google-token", consent(true, "v1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value("uc02-oauth@example.test"));

    mvc.perform(
            post("/api/v1/accounts/oauth")
                .contentType(MediaType.APPLICATION_JSON)
                .content(oauthSignInRequest("GOOGLE", "google-token", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value("uc02-oauth@example.test"));
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM accounts.users u
                    JOIN accounts.oauth_identities oi ON oi.user_id = u.id
                    WHERE u.email = 'uc02-oauth@example.test' AND u.password_hash IS NULL
                    """)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT count(*) FROM accounts.refresh_tokens WHERE user_id = (SELECT id FROM accounts.users WHERE email = 'uc02-oauth@example.test')")
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void oauthDoesNotLinkToAnExistingLocalAccountWithSameEmail() throws Exception {
    insertAccount("uc02-local-oauth-collision@example.test", "local-password");
    doReturn(
            new VerifiedOAuthIdentity(
                OAuthProvider.GOOGLE,
                "separate-google-subject",
                "uc02-local-oauth-collision@example.test",
                "Other"))
        .when(oauthVerifier).verify(OAuthProvider.GOOGLE, "collision-token");
    mvc.perform(
            post("/api/v1/accounts/oauth")
                .contentType(MediaType.APPLICATION_JSON)
                .content(oauthSignInRequest("GOOGLE", "collision-token", consent(true, "v1"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT count(*) FROM accounts.oauth_identities WHERE provider_subject = 'separate-google-subject'")
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void oauthOnlyAccountCannotUseLocalPasswordLoginOrRecovery() throws Exception {
    UUID userId = insertAccount("uc02-oauth-only@example.test", null);
    jdbc.sql(
            """
            INSERT INTO accounts.oauth_identities (provider, provider_subject, user_id)
            VALUES ('GOOGLE', 'oauth-only-subject', :userId)
            """)
        .param("userId", userId)
        .update();

    login("uc02-oauth-only@example.test", "some-password").andExpect(status().isUnauthorized());
    requestRecovery("uc02-oauth-only@example.test").andExpect(status().isAccepted());
    resetPassword("uc02-oauth-only@example.test", "123456", "some-new-password")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("RECOVERY_CODE_INVALID"));
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT count(*) FROM accounts.password_recovery_challenges WHERE user_id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single())
        .isZero();
    org.mockito.Mockito.verify(emailOtpSender, org.mockito.Mockito.never())
        .sendPasswordRecoveryCode(eq("uc02-oauth-only@example.test"), org.mockito.ArgumentMatchers.any(), eq(10));
  }

  private org.springframework.test.web.servlet.ResultActions login(String email, String password)
      throws Exception {
    return mvc.perform(
        post("/api/v1/accounts/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
  }

  private MockHttpServletRequestBuilder post(String uri) {
    return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri)
        .cookie(new Cookie("XSRF-TOKEN", csrfToken))
        .header("X-XSRF-TOKEN", csrfToken);
  }

  private UUID insertAccount(String email, String password) {
    return insertAccount(email, password, "ACTIVE");
  }

  private UUID insertAccount(String email, String password, String status) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO accounts.users (id, email, display_name, password_hash, status, created_at)
            VALUES (:id, :email, 'UC-02 Test', :passwordHash, :status, now())
            """)
        .param("id", id)
        .param("email", email)
        .param("passwordHash", password == null ? null : passwordEncoder.encode(password))
        .param("status", status)
        .update();
    return id;
  }

  private org.springframework.test.web.servlet.ResultActions requestRecovery(String email)
      throws Exception {
    return mvc.perform(
        post("/api/v1/accounts/forgot-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\"}".formatted(email)));
  }

  private org.springframework.test.web.servlet.ResultActions resetPassword(
      String email, String code, String newPassword) throws Exception {
    return mvc.perform(
        post("/api/v1/accounts/reset-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"email":"%s","code":"%s","newPassword":"%s"}
                """
                    .formatted(email, code, newPassword)));
  }

  private static String cookieValue(MvcResult result, String name) {
    return result.getResponse().getHeaders("Set-Cookie").stream()
        .filter(value -> value.startsWith(name + "="))
        .map(value -> value.substring(name.length() + 1, value.indexOf(';')))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing " + name + " cookie"));
  }

  private org.springframework.test.web.servlet.ResultActions postEmail(String body)
      throws Exception {
    return mvc.perform(
        post("/api/v1/accounts/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private org.springframework.test.web.servlet.ResultActions verifyEmail(String email, String code)
      throws Exception {
    return mvc.perform(
        post("/api/v1/accounts/register/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)));
  }

  private String sentCode(String email) {
    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    verify(emailOtpSender).sendVerificationCode(eq(email), code.capture(), eq(10));
    return code.getValue();
  }

  private String sentLatestCode(String email) {
    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    verify(emailOtpSender, times(2)).sendVerificationCode(eq(email), code.capture(), eq(10));
    return code.getAllValues().get(1);
  }

  private static String emailRequest(String email, String consent) {
    return """
        {"email":"%s","displayName":"Test User","password":"test-password-123","consent":%s}
        """
        .formatted(email, consent);
  }

  private static String oauthSignInRequest(String provider, String token, String consent) {
    return """
        {"provider":"%s","token":"%s","consent":%s}
        """
        .formatted(provider, token, consent == null ? "null" : consent);
  }

  private static String consent(boolean accepted, String version) {
    return consent(accepted, version, true);
  }

  private static String consent(boolean accepted, String version, boolean adult) {
    return """
        {"termsAccepted":%s,"healthDataProcessingAccepted":%s,
         "nonDiagnosticAcknowledged":%s,"adultConfirmed":%s,"termsVersion":"%s"}
        """
        .formatted(accepted, accepted, accepted, adult, version);
  }
}
