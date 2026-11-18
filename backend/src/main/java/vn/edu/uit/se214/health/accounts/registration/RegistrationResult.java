package vn.edu.uit.se214.health.accounts.registration;

import java.time.Instant;
import java.util.UUID;

public record RegistrationResult(
    UUID id, String email, String displayName, String status, Instant createdAt) {}
