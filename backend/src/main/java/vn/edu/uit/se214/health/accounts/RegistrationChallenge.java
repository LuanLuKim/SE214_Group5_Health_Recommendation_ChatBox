package vn.edu.uit.se214.health.accounts;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RegistrationChallenge(
    String email,
    String displayName,
    String passwordHash,
    String termsVersion,
    boolean adultConfirmed,
    boolean healthDataProcessingAccepted,
    boolean nonDiagnosticAcknowledged,
    UUID otpSalt,
    String otpHash,
    int attemptCount,
    OffsetDateTime expiresAt,
    OffsetDateTime resendAfter) {}
