package accounts.auth;

import java.time.Instant;
import java.util.UUID;

public record AccountLoginResult(
    UUID userId, String email, String displayName, Instant refreshExpiresAt) {}
