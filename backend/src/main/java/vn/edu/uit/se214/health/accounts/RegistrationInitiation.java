package vn.edu.uit.se214.health.accounts;

import java.time.Instant;

public record RegistrationInitiation(String email, Instant expiresAt) {}
