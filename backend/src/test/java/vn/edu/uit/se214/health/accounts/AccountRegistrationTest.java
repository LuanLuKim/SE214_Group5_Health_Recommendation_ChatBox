package vn.edu.uit.se214.health.accounts;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.support.TestDatabase;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class AccountRegistrationTest {
  private static final String DATABASE = "accounts_registration_test";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @MockitoBean OAuthProviderVerifier oauthVerifier;
  @MockitoBean EmailOtpSender emailOtpSender;

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
  }

  @BeforeEach
  void cleanTestAccounts() {
    reset(oauthVerifier);
    reset(emailOtpSender);
    jdbc.sql("DELETE FROM accounts.users WHERE email LIKE 'uc01-%@example.test'").update();
    jdbc.sql("DELETE FROM accounts.registration_challenges WHERE email LIKE 'uc01-%@example.test'").update();
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
  void oauthProviderFailureDoesNotCreateAnAccount() throws Exception {
    doThrow(ApiException.oauthVerificationFailed())
        .when(oauthVerifier)
        .verify(OAuthProvider.GOOGLE, "invalid-provider-token");

    mvc.perform(
            post("/api/v1/accounts/register/oauth")
                .contentType(MediaType.APPLICATION_JSON)
                .content(oauthRequest("GOOGLE", "invalid-provider-token", consent(true, "v1"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("OAUTH_VERIFICATION_FAILED"));

    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT count(*) FROM accounts.users WHERE email LIKE 'uc01-%@example.test'")
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void oauthRegistrationStoresVerifiedIdentityAndRollsBackWhenThatIdentityAlreadyExists()
      throws Exception {
    VerifiedOAuthIdentity identity =
        new VerifiedOAuthIdentity(
            OAuthProvider.FACEBOOK, "facebook-subject-1", "UC01-OAuth@Example.Test", "OAuth Test");
    doReturn(
            identity)
        .when(oauthVerifier)
        .verify(OAuthProvider.FACEBOOK, "valid-provider-token");

    mvc.perform(
            post("/api/v1/accounts/register/oauth")
                .contentType(MediaType.APPLICATION_JSON)
                .content(oauthRequest("FACEBOOK", "valid-provider-token", consent(true, "v1"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.email").value("uc01-oauth@example.test"));

    doReturn(
            new VerifiedOAuthIdentity(
                OAuthProvider.FACEBOOK,
                "facebook-subject-1",
                "uc01-second@example.test",
                "Second OAuth Test"))
        .when(oauthVerifier)
        .verify(OAuthProvider.FACEBOOK, "same-provider-identity");
    mvc.perform(
            post("/api/v1/accounts/register/oauth")
                .contentType(MediaType.APPLICATION_JSON)
                .content(oauthRequest("FACEBOOK", "same-provider-identity", consent(true, "v1"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("OAUTH_IDENTITY_ALREADY_REGISTERED"));

    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql(
                    "SELECT password_hash FROM accounts.users WHERE email = :email")
                .param("email", "uc01-oauth@example.test")
                .query(String.class)
                .optional())
        .isEmpty();
    org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT count(*) FROM accounts.users WHERE email LIKE 'uc01-%@example.test'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
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

  private static String oauthRequest(String provider, String token, String consent) {
    return """
        {"provider":"%s","token":"%s","consent":%s}
        """
        .formatted(provider, token, consent);
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
