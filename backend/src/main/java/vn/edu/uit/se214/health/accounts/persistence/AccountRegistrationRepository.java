package vn.edu.uit.se214.health.accounts.persistence;

import java.time.OffsetDateTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uit.se214.health.accounts.oauth.OAuthProvider;
import vn.edu.uit.se214.health.accounts.registration.RegistrationChallenge;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class AccountRegistrationRepository {
  private final JdbcClient jdbc;

  public AccountRegistrationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public boolean emailRegistered(String email) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM accounts.users WHERE email = :email)")
        .param("email", email)
        .query(Boolean.class)
        .single();
  }

  public Optional<AuthAccount> findAccountForLogin(String email) {
    return jdbc
        .sql(
            """
            SELECT id, email, display_name, password_hash, status,
                   failed_login_window_started_at, failed_login_count, login_locked_until
            FROM accounts.users WHERE email = :email FOR UPDATE
            """)
        .param("email", email)
        .query(AccountRegistrationRepository::mapAuthAccount)
        .optional();
  }

  public Optional<AuthAccount> findAccountForOAuthLogin(
      OAuthProvider provider, String subject) {
    return jdbc
        .sql(
            """
            SELECT u.id, u.email, u.display_name, u.password_hash, u.status,
                   u.failed_login_window_started_at, u.failed_login_count, u.login_locked_until
            FROM accounts.users u
            JOIN accounts.oauth_identities oi ON oi.user_id = u.id
            WHERE oi.provider = :provider AND oi.provider_subject = :subject
            FOR UPDATE OF u
            """)
        .param("provider", provider.name())
        .param("subject", subject)
        .query(AccountRegistrationRepository::mapAuthAccount)
        .optional();
  }

  public int updateLoginFailures(
      UUID userId,
      OffsetDateTime windowStartedAt,
      int failureCount,
      OffsetDateTime lockedUntil) {
    return jdbc
        .sql(
            """
            UPDATE accounts.users
            SET failed_login_window_started_at = :windowStartedAt,
                failed_login_count = :failureCount, login_locked_until = :lockedUntil
            WHERE id = :userId
            """)
        .param("windowStartedAt", windowStartedAt)
        .param("failureCount", failureCount)
        .param("lockedUntil", lockedUntil)
        .param("userId", userId)
        .update();
  }

  public int clearLoginFailures(UUID userId) {
    return jdbc
        .sql(
            """
            UPDATE accounts.users
            SET failed_login_window_started_at = NULL, failed_login_count = 0,
                login_locked_until = NULL
            WHERE id = :userId
            """)
        .param("userId", userId)
        .update();
  }

  public void insertRefreshToken(
      UUID userId,
      String refreshTokenHash,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt) {
    jdbc.sql(
            """
            INSERT INTO accounts.refresh_tokens
                (refresh_token_hash, user_id, created_at, expires_at)
            VALUES (:refreshTokenHash, :userId, :createdAt, :expiresAt)
            """)
        .param("userId", userId)
        .param("refreshTokenHash", refreshTokenHash)
        .param("createdAt", createdAt)
        .param("expiresAt", expiresAt)
        .update();
  }

  public Optional<RefreshTokenAccount> findRefreshTokenForUpdate(
      String refreshTokenHash, OffsetDateTime now) {
    return jdbc
        .sql(
            """
            SELECT s.expires_at AS refresh_token_expires_at,
                   u.id, u.email, u.display_name, u.password_hash, u.status,
                   u.failed_login_window_started_at, u.failed_login_count, u.login_locked_until
            FROM accounts.refresh_tokens s JOIN accounts.users u ON u.id = s.user_id
            WHERE s.refresh_token_hash = :refreshTokenHash
              AND s.expires_at > :now AND s.revoked_at IS NULL
            FOR UPDATE OF s, u
            """)
        .param("refreshTokenHash", refreshTokenHash)
        .param("now", now)
        .query(
            (rs, row) ->
                new RefreshTokenAccount(
                    mapAuthAccount(rs, row),
                    rs.getObject("refresh_token_expires_at", OffsetDateTime.class)))
        .optional();
  }

  public int rotateRefreshToken(
      String oldRefreshTokenHash,
      String newRefreshTokenHash,
      OffsetDateTime expiresAt) {
    return jdbc
        .sql(
            """
            UPDATE accounts.refresh_tokens SET refresh_token_hash = :newHash, expires_at = :expiresAt
            WHERE refresh_token_hash = :oldHash AND revoked_at IS NULL
            """)
        .param("newHash", newRefreshTokenHash)
        .param("expiresAt", expiresAt)
        .param("oldHash", oldRefreshTokenHash)
        .update();
  }

  public int revokeRefreshToken(String refreshTokenHash, OffsetDateTime now) {
    return jdbc
        .sql(
            """
            UPDATE accounts.refresh_tokens SET revoked_at = :now
            WHERE refresh_token_hash = :refreshTokenHash AND revoked_at IS NULL
            """)
        .param("now", now)
        .param("refreshTokenHash", refreshTokenHash)
        .update();
  }

  public int revokeRefreshTokensForUser(UUID userId, OffsetDateTime now) {
    return jdbc
        .sql(
            """
            UPDATE accounts.refresh_tokens SET revoked_at = :now
            WHERE user_id = :userId AND revoked_at IS NULL
            """)
        .param("now", now)
        .param("userId", userId)
        .update();
  }

  public Optional<AuthAccount> findAccountForRecovery(String email) {
    return jdbc
        .sql(
            """
            SELECT id, email, display_name, password_hash, status,
                   failed_login_window_started_at, failed_login_count, login_locked_until
            FROM accounts.users WHERE email = :email FOR UPDATE
            """)
        .param("email", email)
        .query(AccountRegistrationRepository::mapAuthAccount)
        .optional();
  }

  public Optional<PasswordRecoveryChallenge> findRecoveryChallengeForUpdate(UUID userId) {
    return jdbc
        .sql(
            """
            SELECT user_id, otp_salt, otp_hash, attempt_count, expires_at, resend_after
            FROM accounts.password_recovery_challenges WHERE user_id = :userId FOR UPDATE
            """)
        .param("userId", userId)
        .query(
            (rs, row) ->
                new PasswordRecoveryChallenge(
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("otp_salt", UUID.class),
                    rs.getString("otp_hash"),
                    rs.getInt("attempt_count"),
                    rs.getObject("expires_at", OffsetDateTime.class),
                    rs.getObject("resend_after", OffsetDateTime.class)))
        .optional();
  }

  public void saveRecoveryChallenge(PasswordRecoveryChallenge challenge, OffsetDateTime now) {
    jdbc.sql(
            """
            INSERT INTO accounts.password_recovery_challenges
                (user_id, otp_salt, otp_hash, attempt_count, expires_at, resend_after, created_at)
            VALUES (:userId, :salt, :hash, 0, :expiresAt, :resendAfter, :createdAt)
            ON CONFLICT (user_id) DO UPDATE SET
                otp_salt = EXCLUDED.otp_salt, otp_hash = EXCLUDED.otp_hash,
                attempt_count = 0, expires_at = EXCLUDED.expires_at,
                resend_after = EXCLUDED.resend_after, created_at = EXCLUDED.created_at
            """)
        .param("userId", challenge.userId())
        .param("salt", challenge.otpSalt())
        .param("hash", challenge.otpHash())
        .param("expiresAt", challenge.expiresAt())
        .param("resendAfter", challenge.resendAfter())
        .param("createdAt", now)
        .update();
  }

  public int updateRecoveryAttempts(UUID userId, int attempts) {
    return jdbc
        .sql(
            """
            UPDATE accounts.password_recovery_challenges
            SET attempt_count = :attempts WHERE user_id = :userId
            """)
        .param("attempts", attempts)
        .param("userId", userId)
        .update();
  }

  public int deleteRecoveryChallenge(UUID userId) {
    return jdbc
        .sql("DELETE FROM accounts.password_recovery_challenges WHERE user_id = :userId")
        .param("userId", userId)
        .update();
  }

  public int updatePassword(UUID userId, String passwordHash) {
    return jdbc
        .sql("UPDATE accounts.users SET password_hash = :passwordHash WHERE id = :userId")
        .param("passwordHash", passwordHash)
        .param("userId", userId)
        .update();
  }

  public int deleteExpiredRefreshTokens(OffsetDateTime now) {
    return jdbc
        .sql("DELETE FROM accounts.refresh_tokens WHERE expires_at <= :now OR revoked_at IS NOT NULL")
        .param("now", now)
        .update();
  }

  public int deleteExpiredRecoveryChallenges(OffsetDateTime now) {
    return jdbc
        .sql("DELETE FROM accounts.password_recovery_challenges WHERE expires_at <= :now")
        .param("now", now)
        .update();
  }

  public boolean insertChallenge(RegistrationChallenge challenge, OffsetDateTime createdAt) {
    return jdbc
            .sql(
                """
                INSERT INTO accounts.registration_challenges
                    (email, display_name, password_hash, terms_version, adult_confirmed,
                     health_data_processing_accepted, non_diagnostic_acknowledged, otp_salt,
                     otp_hash, attempt_count, expires_at, resend_after, created_at)
                VALUES (:email, :displayName, :passwordHash, :termsVersion, :adultConfirmed,
                        :healthDataProcessingAccepted, :nonDiagnosticAcknowledged, :otpSalt,
                        :otpHash, :attemptCount, :expiresAt, :resendAfter, :createdAt)
                ON CONFLICT DO NOTHING
                """)
            .param("email", challenge.email())
            .param("displayName", challenge.displayName())
            .param("passwordHash", challenge.passwordHash())
            .param("termsVersion", challenge.termsVersion())
            .param("adultConfirmed", challenge.adultConfirmed())
            .param("healthDataProcessingAccepted", challenge.healthDataProcessingAccepted())
            .param("nonDiagnosticAcknowledged", challenge.nonDiagnosticAcknowledged())
            .param("otpSalt", challenge.otpSalt())
            .param("otpHash", challenge.otpHash())
            .param("attemptCount", challenge.attemptCount())
            .param("expiresAt", challenge.expiresAt())
            .param("resendAfter", challenge.resendAfter())
            .param("createdAt", createdAt)
            .update()
        == 1;
  }

  public Optional<RegistrationChallenge> findChallengeForUpdate(String email) {
    return jdbc
        .sql(
            """
            SELECT email, display_name, password_hash, terms_version, adult_confirmed,
                   health_data_processing_accepted, non_diagnostic_acknowledged, otp_salt,
                   otp_hash, attempt_count, expires_at, resend_after
            FROM accounts.registration_challenges
            WHERE email = :email
            FOR UPDATE
            """)
        .param("email", email)
        .query(AccountRegistrationRepository::mapChallenge)
        .optional();
  }

  public int updateChallengeOtp(
      String email,
      UUID otpSalt,
      String otpHash,
      OffsetDateTime expiresAt,
      OffsetDateTime resendAfter) {
    return jdbc
        .sql(
            """
            UPDATE accounts.registration_challenges
            SET otp_salt = :otpSalt, otp_hash = :otpHash, attempt_count = 0,
                expires_at = :expiresAt, resend_after = :resendAfter
            WHERE email = :email
            """)
        .param("otpSalt", otpSalt)
        .param("otpHash", otpHash)
        .param("expiresAt", expiresAt)
        .param("resendAfter", resendAfter)
        .param("email", email)
        .update();
  }

  public int updateChallengeAttempts(String email, int attemptCount) {
    return jdbc
        .sql("UPDATE accounts.registration_challenges SET attempt_count = :attemptCount WHERE email = :email")
        .param("attemptCount", attemptCount)
        .param("email", email)
        .update();
  }

  public int deleteChallenge(String email) {
    return jdbc.sql("DELETE FROM accounts.registration_challenges WHERE email = :email")
        .param("email", email)
        .update();
  }

  public int deleteExpiredChallenges(OffsetDateTime now) {
    return jdbc.sql("DELETE FROM accounts.registration_challenges WHERE expires_at <= :now")
        .param("now", now)
        .update();
  }

  public boolean insertUser(
      UUID id,
      String email,
      String displayName,
      String passwordHash,
      OffsetDateTime createdAt) {
    return jdbc
            .sql(
                """
                INSERT INTO accounts.users (id, email, display_name, password_hash, created_at)
                VALUES (:id, :email, :displayName, :passwordHash, :createdAt)
                ON CONFLICT DO NOTHING
                """)
            .param("id", id)
            .param("email", email)
            .param("displayName", displayName)
            .param("passwordHash", passwordHash)
            .param("createdAt", createdAt)
            .update()
        == 1;
  }

  public void insertDefaultRole(UUID userId) {
    jdbc.sql("INSERT INTO accounts.user_roles (user_id, role) VALUES (:userId, 'USER')")
        .param("userId", userId)
        .update();
  }

  public void insertConsent(
      UUID userId,
      String termsVersion,
      boolean adultConfirmed,
      boolean healthDataProcessingAccepted,
      boolean nonDiagnosticAcknowledged,
      OffsetDateTime acceptedAt) {
    jdbc.sql(
            """
            INSERT INTO accounts.consents
                (user_id, terms_version, adult_confirmed, health_data_processing_accepted,
                 non_diagnostic_acknowledged, accepted_at)
            VALUES (:userId, :termsVersion, :adultConfirmed, :healthDataProcessingAccepted,
                    :nonDiagnosticAcknowledged, :acceptedAt)
            """)
        .param("userId", userId)
        .param("termsVersion", termsVersion)
        .param("adultConfirmed", adultConfirmed)
        .param("healthDataProcessingAccepted", healthDataProcessingAccepted)
        .param("nonDiagnosticAcknowledged", nonDiagnosticAcknowledged)
        .param("acceptedAt", acceptedAt)
        .update();
  }

  public boolean insertOAuthIdentity(
      OAuthProvider provider, String subject, UUID userId, OffsetDateTime createdAt) {
    return jdbc
            .sql(
                """
                INSERT INTO accounts.oauth_identities (provider, provider_subject, user_id, created_at)
                VALUES (:provider, :subject, :userId, :createdAt)
                ON CONFLICT DO NOTHING
                """)
            .param("provider", provider.name())
            .param("subject", subject)
            .param("userId", userId)
            .param("createdAt", createdAt)
            .update()
        == 1;
  }

  private static RegistrationChallenge mapChallenge(ResultSet result, int row)
      throws SQLException {
    return new RegistrationChallenge(
        result.getString("email"),
        result.getString("display_name"),
        result.getString("password_hash"),
        result.getString("terms_version"),
        result.getBoolean("adult_confirmed"),
        result.getBoolean("health_data_processing_accepted"),
        result.getBoolean("non_diagnostic_acknowledged"),
        result.getObject("otp_salt", UUID.class),
        result.getString("otp_hash"),
        result.getInt("attempt_count"),
        result.getObject("expires_at", OffsetDateTime.class),
        result.getObject("resend_after", OffsetDateTime.class));
  }

  private static AuthAccount mapAuthAccount(ResultSet result, int row) throws SQLException {
    return new AuthAccount(
        result.getObject("id", UUID.class),
        result.getString("email"),
        result.getString("display_name"),
        result.getString("password_hash"),
        result.getString("status"),
        result.getObject("failed_login_window_started_at", OffsetDateTime.class),
        result.getInt("failed_login_count"),
        result.getObject("login_locked_until", OffsetDateTime.class));
  }

  public record AuthAccount(
      UUID id,
      String email,
      String displayName,
      String passwordHash,
      String status,
      OffsetDateTime failedLoginWindowStartedAt,
      int failedLoginCount,
      OffsetDateTime loginLockedUntil) {}

  public record RefreshTokenAccount(AuthAccount account, OffsetDateTime expiresAt) {}

  public record PasswordRecoveryChallenge(
      UUID userId,
      UUID otpSalt,
      String otpHash,
      int attemptCount,
      OffsetDateTime expiresAt,
      OffsetDateTime resendAfter) {}
}
