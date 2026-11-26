package accounts.registration;

import java.time.Instant;

public record RegistrationInitiation(String email, Instant expiresAt) {}
