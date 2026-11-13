package vn.edu.uit.se214.health.accounts;

import java.time.OffsetDateTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.Optional;
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
}
