package vn.edu.uit.se214.health.accounts.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import vn.edu.uit.se214.health.accounts.config.AccountsProperties;
import vn.edu.uit.se214.health.accounts.oauth.OAuthProvider;
import vn.edu.uit.se214.health.accounts.oauth.OAuthProviderVerifier;
import vn.edu.uit.se214.health.accounts.oauth.OAuthSignInRequest;
import vn.edu.uit.se214.health.accounts.oauth.VerifiedOAuthIdentity;
import vn.edu.uit.se214.health.accounts.persistence.AccountRegistrationRepository;
import vn.edu.uit.se214.health.accounts.persistence.AccountRegistrationRepository.AuthAccount;
import vn.edu.uit.se214.health.accounts.persistence.AccountRegistrationRepository.PasswordRecoveryChallenge;
import vn.edu.uit.se214.health.accounts.persistence.AccountRegistrationRepository.RefreshTokenAccount;
import vn.edu.uit.se214.health.accounts.registration.EmailOtpSender;
import vn.edu.uit.se214.health.accounts.registration.OtpCodeGenerator;
import vn.edu.uit.se214.health.accounts.registration.OtpHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.uit.se214.health.shared.web.ApiException;

@Service
@Profile("postgres")
public class AccountAuthenticationService implements AccountAuthenticationUseCase {
  private static final Logger log = LoggerFactory.getLogger(AccountAuthenticationService.class);
  private static final Pattern EMAIL =
      Pattern.compile(
          "^[A-Za-z0-9.!#$%&'*+/=?_`{|}~-]+@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$");
  private static final int PASSWORD_MAX_BYTES = 72;
  private static final int LOGIN_FAILURE_LIMIT = 5;
  private static final Duration LOGIN_FAILURE_WINDOW = Duration.ofMinutes(15);
  private static final Duration LOGIN_LOCK_DURATION = Duration.ofMinutes(15);
  private static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(15);
  private static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofHours(8);
  private static final Duration RECOVERY_CODE_LIFETIME = Duration.ofMinutes(10);
  private static final Duration RECOVERY_RESEND_COOLDOWN = Duration.ofSeconds(60);
  private static final int RECOVERY_ATTEMPT_LIMIT = 5;
  private static final String GENERIC_RECOVERY_MESSAGE =
      "Nếu email được đăng ký, mã khôi phục sẽ được gửi đến hộp thư.";

  private final AccountRegistrationRepository repository;
  private final OAuthProviderVerifier oauthVerifier;
  private final EmailOtpSender emailOtpSender;
  private final OtpCodeGenerator otpCodeGenerator;
  private final OtpHasher otpHasher;
  private final PasswordEncoder passwordEncoder;
  private final AccessTokenService accessTokens;
  private final AccountsProperties accountsProperties;
  private final TransactionTemplate transaction;
  private final String dummyPasswordHash;

  public AccountAuthenticationService(
      AccountRegistrationRepository repository,
      OAuthProviderVerifier oauthVerifier,
      EmailOtpSender emailOtpSender,
      OtpCodeGenerator otpCodeGenerator,
      OtpHasher otpHasher,
      PasswordEncoder passwordEncoder,
      AccessTokenService accessTokens,
      AccountsProperties accountsProperties,
      TransactionTemplate transaction) {
    this.repository = repository;
    this.oauthVerifier = oauthVerifier;
    this.emailOtpSender = emailOtpSender;
    this.otpCodeGenerator = otpCodeGenerator;
    this.otpHasher = otpHasher;
    this.passwordEncoder = passwordEncoder;
    this.accessTokens = accessTokens;
    this.accountsProperties = accountsProperties;
    this.transaction = transaction;
    this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  @Override
  public IssuedAuthTokens login(AccountLoginRequest request) {
    if (request == null) throw ApiException.registrationFieldInvalid();
    if (request.email() == null || request.password() == null)
      throw ApiException.registrationFieldInvalid();

    String email = normalizeEmail(request.email());
    String password = request.password();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    LoginOutcome outcome =
        transaction.execute(
            status -> {
              Optional<AuthAccount> optional = repository.findAccountForLogin(email);
              if (optional.isEmpty()) {
                passwordEncoder.matches(password, dummyPasswordHash);
                return LoginOutcome.failed();
              }
              AuthAccount account = optional.get();
              if (account.passwordHash() == null) {
                passwordEncoder.matches(password, dummyPasswordHash);
                return LoginOutcome.failed();
              }
              boolean locked =
                  !"ACTIVE".equals(account.status())
                      || (account.loginLockedUntil() != null
                          && account.loginLockedUntil().isAfter(now));
              boolean validPassword =
                  password.getBytes(StandardCharsets.UTF_8).length <= PASSWORD_MAX_BYTES;
              boolean matches =
                  !locked
                      && validPassword
                      && passwordEncoder.matches(password, account.passwordHash());
              if (!matches) {
                if (!locked) recordLoginFailure(account, now);
                if (locked || !validPassword) passwordEncoder.matches(password, dummyPasswordHash);
                return LoginOutcome.failed();
              }

              repository.clearLoginFailures(account.id());
              return LoginOutcome.succeeded(issueTokens(account, now));
            });
    if (outcome.tokens() == null) throw ApiException.loginFailed();
    return outcome.tokens();
  }

  @Override
  public IssuedAuthTokens oauthSignIn(OAuthSignInRequest request) {
    if (request == null) throw ApiException.oauthVerificationFailed();
    OAuthProvider provider = parseProvider(request.provider());
    String token = validateOAuthToken(request.token());
    VerifiedOAuthIdentity identity = oauthVerifier.verify(provider, token);
    if (identity == null
        || identity.provider() != provider
        || identity.subject() == null
        || identity.subject().isBlank()) {
      throw ApiException.oauthVerificationFailed();
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    IssuedAuthTokens issuedTokens =
        transaction.execute(
            status -> {
              Optional<AuthAccount> account =
                  repository.findAccountForOAuthLogin(provider, identity.subject());
              if (account.isPresent()) {
                if (!"ACTIVE".equals(account.get().status())) return null;
                return issueTokens(account.get(), now);
              }

              AcceptedConsent consent = validateConsent(request.consent());
              String email = normalizeEmail(identity.email());
              String displayName = normalizeDisplayName(identity.displayName());
              UUID userId = UUID.randomUUID();
              if (!repository.insertUser(userId, email, displayName, null, now))
                throw ApiException.emailAlreadyRegistered();
              repository.insertDefaultRole(userId);
              repository.insertConsent(userId, consent.termsVersion(), true, true, true, now);
              if (!repository.insertOAuthIdentity(provider, identity.subject(), userId, now))
                throw ApiException.oauthIdentityAlreadyRegistered();

              AuthAccount newAccount =
                  new AuthAccount(userId, email, displayName, null, "ACTIVE", null, 0, null);
              return issueTokens(newAccount, now);
            });
    if (issuedTokens == null) throw ApiException.loginFailed();
    return issuedTokens;
  }

  @Override
  public IssuedAuthTokens refresh(String refreshToken) {
    if (!isRefreshTokenShapeValid(refreshToken)) throw ApiException.refreshTokenInvalid();
    String oldHash = AccessTokenService.hashRefreshToken(refreshToken);
    String replacement = AccessTokenService.newRefreshToken();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    IssuedAuthTokens issuedTokens =
        transaction.execute(
            status -> {
              Optional<RefreshTokenAccount> optional =
                  repository.findRefreshTokenForUpdate(oldHash, now);
              if (optional.isEmpty()) return null;
              RefreshTokenAccount refreshTokenAccount = optional.get();
              AuthAccount account = refreshTokenAccount.account();
              if (!"ACTIVE".equals(account.status())) return null;
              OffsetDateTime expiresAt = refreshTokenAccount.expiresAt();
              if (repository.rotateRefreshToken(
                          oldHash,
                          AccessTokenService.hashRefreshToken(replacement),
                          expiresAt)
                      != 1) return null;
              return createIssuedTokens(account, replacement, now, expiresAt);
            });
    if (issuedTokens == null) throw ApiException.refreshTokenInvalid();
    return issuedTokens;
  }

  @Override
  public PasswordRecoveryAccepted requestPasswordRecovery(PasswordRecoveryRequest request) {
    if (request == null) throw ApiException.passwordRecoveryRequestInvalid();
    String email = normalizeEmail(request.email());
    String code = otpCodeGenerator.nextCode();
    UUID salt = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String codeHash = otpHasher.hash(email, salt, code);

    transaction.execute(
        status -> {
          Optional<AuthAccount> account = repository.findAccountForRecovery(email);
          if (account.isEmpty() || account.get().passwordHash() == null) return null;
          Optional<PasswordRecoveryChallenge> existing =
              repository.findRecoveryChallengeForUpdate(account.get().id());
          if (existing.isPresent() && existing.get().resendAfter().isAfter(now)) return null;

          OffsetDateTime expiresAt = now.plus(RECOVERY_CODE_LIFETIME);
          OffsetDateTime resendAfter = now.plus(RECOVERY_RESEND_COOLDOWN);
          repository.saveRecoveryChallenge(
              new PasswordRecoveryChallenge(
                  account.get().id(), salt, codeHash, 0, expiresAt, resendAfter),
              now);
          try {
            emailOtpSender.sendPasswordRecoveryCode(
                email, code, (int) RECOVERY_CODE_LIFETIME.toMinutes());
          } catch (ApiException e) {
            if (!"EMAIL_DELIVERY_FAILED".equals(e.code())
                && !"EMAIL_VERIFICATION_UNAVAILABLE".equals(e.code())) throw e;
            log.warn("Password recovery email delivery failed; code={}", e.code());
          }
          return null;
        });
    return new PasswordRecoveryAccepted(GENERIC_RECOVERY_MESSAGE);
  }

  @Override
  public void resetPassword(PasswordResetRequest request) {
    if (request == null) throw ApiException.passwordRecoveryRequestInvalid();
    String email = normalizeEmail(request.email());
    String code = request.code() == null ? "" : request.code();
    if (code.length() > 32 || hasControlCharacter(code)) code = "";
    String submittedCode = code;
    String newPassword = validatePassword(request.newPassword());
    String passwordHash = passwordEncoder.encode(newPassword);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    RecoveryOutcome outcome =
        transaction.execute(
            status -> {
              Optional<AuthAccount> account = repository.findAccountForRecovery(email);
              if (account.isEmpty() || account.get().passwordHash() == null)
                return RecoveryOutcome.INVALID;
              Optional<PasswordRecoveryChallenge> optional =
                  repository.findRecoveryChallengeForUpdate(account.get().id());
              if (optional.isEmpty()) return RecoveryOutcome.INVALID;
              PasswordRecoveryChallenge challenge = optional.get();
              if (!challenge.expiresAt().isAfter(now)) {
                repository.deleteRecoveryChallenge(account.get().id());
                return RecoveryOutcome.INVALID;
              }
              if (challenge.attemptCount() >= RECOVERY_ATTEMPT_LIMIT)
                return RecoveryOutcome.EXHAUSTED;
              if (!otpHasher.matches(
                  email, challenge.otpSalt(), submittedCode, challenge.otpHash())) {
                int attempts = challenge.attemptCount() + 1;
                repository.updateRecoveryAttempts(account.get().id(), attempts);
                return attempts >= RECOVERY_ATTEMPT_LIMIT
                    ? RecoveryOutcome.EXHAUSTED
                    : RecoveryOutcome.INVALID;
              }
              repository.updatePassword(account.get().id(), passwordHash);
              repository.clearLoginFailures(account.get().id());
              repository.deleteRecoveryChallenge(account.get().id());
              repository.revokeRefreshTokensForUser(account.get().id(), now);
              return RecoveryOutcome.RESET;
            });
    if (outcome != RecoveryOutcome.RESET) throw ApiException.recoveryCodeInvalid();
  }

  @Override
  public void logout(String refreshToken) {
    if (!isRefreshTokenShapeValid(refreshToken)) throw ApiException.refreshTokenInvalid();
    repository.revokeRefreshToken(
        AccessTokenService.hashRefreshToken(refreshToken), OffsetDateTime.now(ZoneOffset.UTC));
  }

  private void recordLoginFailure(AuthAccount account, OffsetDateTime now) {
    OffsetDateTime windowStart = account.failedLoginWindowStartedAt();
    int failures = account.failedLoginCount();
    if (windowStart == null || !windowStart.plus(LOGIN_FAILURE_WINDOW).isAfter(now)) {
      windowStart = now;
      failures = 0;
    }
    failures++;
    OffsetDateTime lockedUntil =
        failures >= LOGIN_FAILURE_LIMIT ? now.plus(LOGIN_LOCK_DURATION) : null;
    repository.updateLoginFailures(account.id(), windowStart, failures, lockedUntil);
  }

  private IssuedAuthTokens issueTokens(AuthAccount account, OffsetDateTime now) {
    String refreshToken = AccessTokenService.newRefreshToken();
    OffsetDateTime expiresAt = now.plus(REFRESH_TOKEN_LIFETIME);
    repository.insertRefreshToken(
        account.id(),
        AccessTokenService.hashRefreshToken(refreshToken),
        now,
        expiresAt);
    return createIssuedTokens(account, refreshToken, now, expiresAt);
  }

  private IssuedAuthTokens createIssuedTokens(
      AuthAccount account,
      String refreshToken,
      OffsetDateTime now,
      OffsetDateTime refreshTokenExpiresAt) {
    Instant expiresAt = now.toInstant().plus(ACCESS_TOKEN_LIFETIME);
    if (refreshTokenExpiresAt.toInstant().isBefore(expiresAt))
      expiresAt = refreshTokenExpiresAt.toInstant();
    String accessToken = accessTokens.issue(account.id(), expiresAt);
    return new IssuedAuthTokens(
        new AccountLoginResult(
            account.id(),
            account.email(),
            account.displayName(),
            refreshTokenExpiresAt.toInstant()),
        accessToken,
        refreshToken);
  }

  private static String normalizeEmail(String value) {
    if (value == null) throw ApiException.emailInvalid();
    String email = value.trim().toLowerCase(Locale.ROOT);
    if (email.length() > 254
        || email.startsWith(".")
        || email.endsWith(".")
        || email.contains("..")
        || hasControlCharacter(email)
        || !EMAIL.matcher(email).matches()) {
      throw ApiException.emailInvalid();
    }
    return email;
  }

  private static String normalizeDisplayName(String value) {
    if (value == null) throw ApiException.registrationFieldInvalid();
    String displayName = value.trim();
    if (displayName.isEmpty() || displayName.length() > 100 || hasControlCharacter(displayName))
      throw ApiException.registrationFieldInvalid();
    return displayName;
  }

  private AcceptedConsent validateConsent(AccountConsentRequest consent) {
    if (consent == null
        || !Boolean.TRUE.equals(consent.termsAccepted())
        || !Boolean.TRUE.equals(consent.healthDataProcessingAccepted())
        || !Boolean.TRUE.equals(consent.nonDiagnosticAcknowledged())) {
      throw ApiException.consentRequired();
    }
    if (!Boolean.TRUE.equals(consent.adultConfirmed()))
      throw ApiException.adultConfirmationRequired();
    if (accountsProperties.termsVersion() == null
        || accountsProperties.termsVersion().isBlank()
        || !accountsProperties.termsVersion().equals(consent.termsVersion())) {
      throw ApiException.termsVersionInvalid();
    }
    return new AcceptedConsent(accountsProperties.termsVersion());
  }

  private static String validatePassword(String password) {
    if (password == null
        || password.getBytes(StandardCharsets.UTF_8).length < 8
        || password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES)
      throw ApiException.passwordInvalid();
    return password;
  }

  private static OAuthProvider parseProvider(String value) {
    if (value == null) throw ApiException.oauthProviderUnsupported();
    try {
      return OAuthProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.oauthProviderUnsupported();
    }
  }

  private static String validateOAuthToken(String token) {
    if (token == null || token.isBlank() || token.length() > 8192 || hasControlCharacter(token))
      throw ApiException.oauthVerificationFailed();
    return token;
  }

  private static boolean isRefreshTokenShapeValid(String token) {
    if (token == null || token.length() != 43) return false;
    try {
      return Base64.getUrlDecoder().decode(token).length == 32;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean hasControlCharacter(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  private record AcceptedConsent(String termsVersion) {}

  private enum RecoveryOutcome {
    RESET,
    INVALID,
    EXHAUSTED
  }

  private record LoginOutcome(IssuedAuthTokens tokens) {
    static LoginOutcome failed() {
      return new LoginOutcome(null);
    }

    static LoginOutcome succeeded(IssuedAuthTokens tokens) {
      return new LoginOutcome(tokens);
    }
  }
}
