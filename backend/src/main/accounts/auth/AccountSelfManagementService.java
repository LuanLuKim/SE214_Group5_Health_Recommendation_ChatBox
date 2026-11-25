package accounts.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import accounts.AccountApiException;
import accounts.auth.AccountSelfManagementModels.ChangePasswordRequest;
import accounts.auth.AccountSelfManagementModels.AccountProfile;
import accounts.auth.AccountSelfManagementModels.ProfileUpdateRequest;
import accounts.persistence.AccountRegistrationRepository;
import accounts.persistence.AccountRegistrationRepository.AccountDeletionChallenge;
import accounts.persistence.AccountRegistrationRepository.SelfManagementAccount;
import accounts.registration.EmailOtpSender;
import accounts.registration.OtpCodeGenerator;
import accounts.registration.OtpHasher;
import shared.web.ApiException;

@Service
@Profile("postgres")
public class AccountSelfManagementService implements AccountSelfManagementUseCase {
  private static final Pattern PHONE_NUMBER = Pattern.compile("^\\+?[0-9]{7,15}$");
  private static final int PASSWORD_MAX_BYTES = 72;
  private static final Duration DELETION_CODE_LIFETIME = Duration.ofMinutes(10);
  private static final Duration DELETION_RESEND_COOLDOWN = Duration.ofSeconds(60);
  private static final int DELETION_ATTEMPT_LIMIT = 5;

  private final AccountRegistrationRepository repository;
  private final EmailOtpSender emailOtpSender;
  private final OtpCodeGenerator otpCodeGenerator;
  private final OtpHasher otpHasher;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate transaction;

  public AccountSelfManagementService(
      AccountRegistrationRepository repository,
      EmailOtpSender emailOtpSender,
      OtpCodeGenerator otpCodeGenerator,
      OtpHasher otpHasher,
      PasswordEncoder passwordEncoder,
      TransactionTemplate transaction) {
    this.repository = repository;
    this.emailOtpSender = emailOtpSender;
    this.otpCodeGenerator = otpCodeGenerator;
    this.otpHasher = otpHasher;
    this.passwordEncoder = passwordEncoder;
    this.transaction = transaction;
  }

  @Override
  public AccountProfile profile(UUID userId) {
    SelfManagementAccount account =
        repository.findSelfManagementAccount(userId).orElseThrow(ApiException::sessionInvalid);
    requireActive(account);
    return toProfile(account);
  }

  @Override
  public AccountProfile updateProfile(UUID userId, ProfileUpdateRequest request) {
    if (request == null) throw AccountApiException.profileInvalid();
    String displayName = normalizeDisplayName(request.displayName());
    String phoneNumber = normalizePhoneNumber(request.phoneNumber());
    String avatarUrl = normalizeAvatarUrl(request.avatarUrl());

    return transaction.execute(
        status -> {
          SelfManagementAccount account = lockAccount(userId);
          repository.updateProfile(userId, displayName, phoneNumber, avatarUrl);
          return new AccountProfile(userId, account.email(), displayName, phoneNumber, avatarUrl);
        });
  }

  @Override
  public void changePassword(UUID userId, ChangePasswordRequest request) {
    if (request == null || request.currentPassword() == null)
      throw AccountApiException.currentPasswordInvalid();
    String currentPassword = request.currentPassword();
    if (!isPasswordLengthValid(currentPassword))
      throw AccountApiException.currentPasswordInvalid();
    String newPassword = validateNewPassword(request.newPassword());
    String newPasswordHash = passwordEncoder.encode(newPassword);

    ChangePasswordOutcome outcome =
        transaction.execute(
            status -> {
              SelfManagementAccount account = lockAccount(userId);
              if (account.passwordHash() == null) return ChangePasswordOutcome.OAUTH_ONLY;
              if (!passwordEncoder.matches(currentPassword, account.passwordHash()))
                return ChangePasswordOutcome.INVALID_CURRENT_PASSWORD;
              repository.updatePassword(userId, newPasswordHash);
              repository.clearLoginFailures(userId);
              repository.revokeRefreshTokensForUser(userId, OffsetDateTime.now(ZoneOffset.UTC));
              return ChangePasswordOutcome.CHANGED;
            });
    if (outcome == ChangePasswordOutcome.OAUTH_ONLY)
      throw AccountApiException.passwordChangeUnavailable();
    if (outcome != ChangePasswordOutcome.CHANGED)
      throw AccountApiException.currentPasswordInvalid();
  }

  @Override
  public void requestDeletion(UUID userId) {
    transaction.executeWithoutResult(
        status -> {
          SelfManagementAccount account = lockAccount(userId);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          var existing = repository.findDeletionChallengeForUpdate(userId);
          if (existing.isPresent() && existing.get().resendAfter().isAfter(now))
            throw ApiException.otpResendTooSoon();

          String code = otpCodeGenerator.nextCode();
          UUID salt = UUID.randomUUID();
          repository.saveDeletionChallenge(
              new AccountDeletionChallenge(
                  userId,
                  salt,
                  otpHasher.hash(account.email(), salt, code),
                  0,
                  now.plus(DELETION_CODE_LIFETIME),
                  now.plus(DELETION_RESEND_COOLDOWN)),
              now);
          emailOtpSender.sendAccountDeletionCode(
              account.email(), code, (int) DELETION_CODE_LIFETIME.toMinutes());
        });
  }

  @Override
  public void confirmDeletion(UUID userId, String submittedCode) {
    String code = normalizeDeletionCode(submittedCode);
    DeletionOutcome outcome =
        transaction.execute(status -> confirmDeletionInTransaction(userId, code));
    if (outcome != DeletionOutcome.DELETED) {
      if (outcome == DeletionOutcome.ATTEMPTS_EXHAUSTED)
        throw ApiException.otpAttemptsExhausted();
      throw ApiException.otpInvalid();
    }
  }

  private DeletionOutcome confirmDeletionInTransaction(UUID userId, String code) {
    var accountOptional = repository.findSelfManagementAccountForUpdate(userId);
    if (accountOptional.isEmpty()) return DeletionOutcome.INVALID;
    SelfManagementAccount account = accountOptional.get();
    if (!"ACTIVE".equals(account.status())) return DeletionOutcome.INVALID;

    var challengeOptional = repository.findDeletionChallengeForUpdate(userId);
    if (challengeOptional.isEmpty()) return DeletionOutcome.INVALID;
    AccountDeletionChallenge challenge = challengeOptional.get();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    if (!challenge.expiresAt().isAfter(now)) {
      repository.deleteDeletionChallenge(userId);
      return DeletionOutcome.INVALID;
    }
    if (challenge.attemptCount() >= DELETION_ATTEMPT_LIMIT)
      return DeletionOutcome.ATTEMPTS_EXHAUSTED;
    if (!otpHasher.matches(account.email(), challenge.otpSalt(), code, challenge.otpHash())) {
      int attempts = challenge.attemptCount() + 1;
      repository.updateDeletionAttempts(userId, attempts);
      return attempts >= DELETION_ATTEMPT_LIMIT
          ? DeletionOutcome.ATTEMPTS_EXHAUSTED
          : DeletionOutcome.INVALID;
    }

    repository.anonymizeAuditLogs(userId);
    if (repository.deleteAccount(userId) != 1) return DeletionOutcome.INVALID;
    return DeletionOutcome.DELETED;
  }

  private SelfManagementAccount lockAccount(UUID userId) {
    SelfManagementAccount account =
        repository
            .findSelfManagementAccountForUpdate(userId)
            .orElseThrow(ApiException::sessionInvalid);
    requireActive(account);
    return account;
  }

  private static void requireActive(SelfManagementAccount account) {
    if (!"ACTIVE".equals(account.status())) throw ApiException.sessionInvalid();
  }

  private static AccountProfile toProfile(SelfManagementAccount account) {
    return new AccountProfile(
        account.id(),
        account.email(),
        account.displayName(),
        account.phoneNumber(),
        account.avatarUrl());
  }

  private static String normalizeDisplayName(String value) {
    if (value == null) throw AccountApiException.profileInvalid();
    String normalized = value.trim();
    if (normalized.isEmpty()
        || normalized.length() > 100
        || normalized.codePoints().anyMatch(Character::isISOControl))
      throw AccountApiException.profileInvalid();
    return normalized;
  }

  private static String normalizePhoneNumber(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (!PHONE_NUMBER.matcher(normalized).matches()) throw AccountApiException.profileInvalid();
    return normalized;
  }

  private static String normalizeAvatarUrl(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > 2048
        || normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw AccountApiException.profileInvalid();
    }
    try {
      URI uri = URI.create(normalized);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null) {
        throw AccountApiException.profileInvalid();
      }
    } catch (IllegalArgumentException exception) {
      throw AccountApiException.profileInvalid();
    }
    return normalized;
  }

  private static String validateNewPassword(String password) {
    if (!isPasswordLengthValid(password)) throw AccountApiException.passwordInvalid();
    return password;
  }

  private static boolean isPasswordLengthValid(String password) {
    if (password == null) return false;
    int byteLength = password.getBytes(StandardCharsets.UTF_8).length;
    return byteLength >= 8 && byteLength <= PASSWORD_MAX_BYTES;
  }

  private static String normalizeDeletionCode(String value) {
    if (value == null || value.length() > 32 || value.codePoints().anyMatch(Character::isISOControl))
      return "";
    return value;
  }

  private enum ChangePasswordOutcome {
    CHANGED,
    OAUTH_ONLY,
    INVALID_CURRENT_PASSWORD
  }

  private enum DeletionOutcome {
    DELETED,
    INVALID,
    ATTEMPTS_EXHAUSTED
  }
}
