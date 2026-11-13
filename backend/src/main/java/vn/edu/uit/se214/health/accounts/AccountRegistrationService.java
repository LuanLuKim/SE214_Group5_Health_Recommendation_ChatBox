package vn.edu.uit.se214.health.accounts;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.uit.se214.health.shared.web.ApiException;

@Service
@Profile("postgres")
public class AccountRegistrationService implements AccountRegistrationUseCase {
  private static final Pattern EMAIL =
      Pattern.compile(
          "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$");
  private static final int MAX_PASSWORD_BYTES = 72;
  private static final int OTP_EXPIRY_MINUTES = 10;
  private static final int OTP_RESEND_COOLDOWN_SECONDS = 60;
  private static final int OTP_MAX_ATTEMPTS = 5;

  private final AccountRegistrationRepository repository;
  private final OAuthProviderVerifier oauthVerifier;
  private final EmailOtpSender emailOtpSender;
  private final OtpCodeGenerator otpCodeGenerator;
  private final OtpHasher otpHasher;
  private final PasswordEncoder passwordEncoder;
  private final AccountsProperties properties;
  private final TransactionTemplate transaction;

  public AccountRegistrationService(
      AccountRegistrationRepository repository,
      OAuthProviderVerifier oauthVerifier,
      EmailOtpSender emailOtpSender,
      OtpCodeGenerator otpCodeGenerator,
      OtpHasher otpHasher,
      PasswordEncoder passwordEncoder,
      AccountsProperties properties,
      TransactionTemplate transaction) {
    this.repository = repository;
    this.oauthVerifier = oauthVerifier;
    this.emailOtpSender = emailOtpSender;
    this.otpCodeGenerator = otpCodeGenerator;
    this.otpHasher = otpHasher;
    this.passwordEncoder = passwordEncoder;
    this.properties = properties;
    this.transaction = transaction;
  }

  @Override
  public RegistrationInitiation startEmailRegistration(EmailRegistrationRequest request) {
    if (request == null) throw ApiException.registrationFieldInvalid();
    AcceptedConsent consent = validateConsent(request.consent());
    String email = normalizeEmail(request.email());
    String displayName = normalizeDisplayName(request.displayName());
    String passwordHash = passwordEncoder.encode(validatePassword(request.password()));
    String code = otpCodeGenerator.nextCode();
    UUID salt = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime expiresAt = now.plus(Duration.ofMinutes(OTP_EXPIRY_MINUTES));
    OffsetDateTime resendAfter = now.plus(Duration.ofSeconds(OTP_RESEND_COOLDOWN_SECONDS));
    String codeHash = otpHasher.hash(email, salt, code);

    return transaction.execute(
        status -> {
          if (repository.emailRegistered(email)) throw ApiException.emailAlreadyRegistered();
          Optional<RegistrationChallenge> existing = repository.findChallengeForUpdate(email);
          if (existing.isPresent()) {
            if (existing.get().expiresAt().isAfter(now))
              throw ApiException.registrationAlreadyPending();
            repository.deleteChallenge(email);
          }
          var challenge =
              new RegistrationChallenge(
                  email,
                  displayName,
                  passwordHash,
                  consent.termsVersion(),
                  true,
                  true,
                  true,
                  salt,
                  codeHash,
                  0,
                  expiresAt,
                  resendAfter);
          if (!repository.insertChallenge(challenge, now))
            throw ApiException.registrationAlreadyPending();
          emailOtpSender.sendVerificationCode(email, code, OTP_EXPIRY_MINUTES);
          return new RegistrationInitiation(email, expiresAt.toInstant());
        });
  }

  @Override
  public RegistrationInitiation resendEmailVerification(EmailOtpResendRequest request) {
    if (request == null) throw ApiException.registrationFieldInvalid();
    String email = normalizeEmail(request.email());
    String code = otpCodeGenerator.nextCode();
    UUID salt = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime expiresAt = now.plus(Duration.ofMinutes(OTP_EXPIRY_MINUTES));
    OffsetDateTime resendAfter = now.plus(Duration.ofSeconds(OTP_RESEND_COOLDOWN_SECONDS));
    String codeHash = otpHasher.hash(email, salt, code);

    return transaction.execute(
        status -> {
          Optional<RegistrationChallenge> existing = repository.findChallengeForUpdate(email);
          if (existing.isEmpty()) throw ApiException.registrationChallengeNotFound();
          if (repository.emailRegistered(email)) throw ApiException.emailAlreadyRegistered();
          if (existing.get().resendAfter().isAfter(now))
            throw ApiException.otpResendTooSoon();
          repository.updateChallengeOtp(email, salt, codeHash, expiresAt, resendAfter);
          emailOtpSender.sendVerificationCode(email, code, OTP_EXPIRY_MINUTES);
          return new RegistrationInitiation(email, expiresAt.toInstant());
        });
  }

  @Override
  public RegistrationResult verifyEmailRegistration(EmailOtpVerifyRequest request) {
    if (request == null) throw ApiException.registrationFieldInvalid();
    String email = normalizeEmail(request.email());
    String code = request.code() == null ? "" : request.code();
    if (code.length() > 32 || hasControlCharacter(code)) code = "";
    String submittedCode = code;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    VerificationOutcome outcome =
        transaction.execute(
            status -> {
              Optional<RegistrationChallenge> optional =
                  repository.findChallengeForUpdate(email);
              if (optional.isEmpty()) return new VerificationOutcome(VerifyStatus.NOT_FOUND, null);
              RegistrationChallenge challenge = optional.get();
              if (!challenge.expiresAt().isAfter(now)) {
                repository.deleteChallenge(email);
                return new VerificationOutcome(VerifyStatus.EXPIRED, null);
              }
              if (challenge.attemptCount() >= OTP_MAX_ATTEMPTS)
                return new VerificationOutcome(VerifyStatus.EXHAUSTED, null);
              if (!otpHasher.matches(
                  email, challenge.otpSalt(), submittedCode, challenge.otpHash())) {
                int attempts = challenge.attemptCount() + 1;
                repository.updateChallengeAttempts(email, attempts);
                return new VerificationOutcome(
                    attempts >= OTP_MAX_ATTEMPTS ? VerifyStatus.EXHAUSTED : VerifyStatus.INVALID,
                    null);
              }

              UUID id = UUID.randomUUID();
              if (!repository.insertUser(
                  id, email, challenge.displayName(), challenge.passwordHash(), now)) {
                repository.deleteChallenge(email);
                return new VerificationOutcome(VerifyStatus.EMAIL_REGISTERED, null);
              }
              repository.insertDefaultRole(id);
              repository.insertConsent(
                  id,
                  challenge.termsVersion(),
                  challenge.adultConfirmed(),
                  challenge.healthDataProcessingAccepted(),
                  challenge.nonDiagnosticAcknowledged(),
                  now);
              repository.deleteChallenge(email);
              return new VerificationOutcome(
                  VerifyStatus.VERIFIED,
                  new RegistrationResult(
                      id, email, challenge.displayName(), "ACTIVE", now.toInstant()));
            });

    return switch (outcome.status()) {
      case VERIFIED -> outcome.registration();
      case INVALID -> throw ApiException.otpInvalid();
      case EXPIRED -> throw ApiException.otpExpired();
      case EXHAUSTED -> throw ApiException.otpAttemptsExhausted();
      case NOT_FOUND -> throw ApiException.registrationChallengeNotFound();
      case EMAIL_REGISTERED -> throw ApiException.emailAlreadyRegistered();
    };
  }

  @Override
  public RegistrationResult registerOAuth(OAuthRegistrationRequest request) {
    if (request == null) throw ApiException.registrationFieldInvalid();
    AcceptedConsent consent = validateConsent(request.consent());
    OAuthProvider provider = parseProvider(request.provider());
    String token = validateOAuthToken(request.token());
    VerifiedOAuthIdentity identity = oauthVerifier.verify(provider, token);
    if (identity == null
        || identity.provider() != provider
        || identity.subject() == null
        || identity.subject().isBlank()) {
      throw ApiException.oauthVerificationFailed();
    }

    return createAccount(
        normalizeEmail(identity.email()),
        normalizeDisplayName(identity.displayName()),
        null,
        identity,
        consent);
  }

  private RegistrationResult createAccount(
      String email,
      String displayName,
      String passwordHash,
      VerifiedOAuthIdentity identity,
      AcceptedConsent consent) {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return transaction.execute(
        status -> {
          if (!repository.insertUser(id, email, displayName, passwordHash, now))
            throw ApiException.emailAlreadyRegistered();
          repository.insertDefaultRole(id);
          repository.insertConsent(
              id,
              consent.termsVersion(),
              true,
              true,
              true,
              now);
          if (identity != null
              && !repository.insertOAuthIdentity(identity.provider(), identity.subject(), id, now))
            throw ApiException.oauthIdentityAlreadyRegistered();
          return new RegistrationResult(id, email, displayName, "ACTIVE", now.toInstant());
        });
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
    if (properties.termsVersion() == null
        || properties.termsVersion().isBlank()
        || !properties.termsVersion().equals(consent.termsVersion())) {
      throw ApiException.termsVersionInvalid();
    }
    return new AcceptedConsent(properties.termsVersion());
  }

  private static String normalizeEmail(String value) {
    if (value == null) throw ApiException.emailInvalid();
    String email = value.trim().toLowerCase(Locale.ROOT);
    if (email.length() > 254
        || email.startsWith(".")
        || email.endsWith(".")
        || email.contains("..")
        || !EMAIL.matcher(email).matches()
        || hasControlCharacter(email))
      throw ApiException.emailInvalid();
    return email;
  }

  private static String normalizeDisplayName(String value) {
    if (value == null) throw ApiException.registrationFieldInvalid();
    String displayName = value.trim();
    if (displayName.isEmpty() || displayName.length() > 100 || hasControlCharacter(displayName))
      throw ApiException.registrationFieldInvalid();
    return displayName;
  }

  private static String validatePassword(String value) {
    if (value == null) throw ApiException.passwordInvalid();
    int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
    if (byteLength < 8 || byteLength > MAX_PASSWORD_BYTES)
      throw ApiException.passwordInvalid();
    return value;
  }

  private static String validateOAuthToken(String token) {
    if (token == null || token.isBlank() || token.length() > 8192 || hasControlCharacter(token))
      throw ApiException.oauthVerificationFailed();
    return token;
  }

  private static OAuthProvider parseProvider(String value) {
    if (value == null) throw ApiException.oauthProviderUnsupported();
    try {
      return OAuthProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.oauthProviderUnsupported();
    }
  }

  private static boolean hasControlCharacter(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  private record AcceptedConsent(String termsVersion) {}

  private enum VerifyStatus {
    VERIFIED,
    INVALID,
    EXPIRED,
    EXHAUSTED,
    NOT_FOUND,
    EMAIL_REGISTERED
  }

  private record VerificationOutcome(VerifyStatus status, RegistrationResult registration) {}
}
