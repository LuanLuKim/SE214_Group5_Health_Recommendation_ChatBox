package vn.edu.uit.se214.health.accounts;

public record VerifiedOAuthIdentity(
    OAuthProvider provider, String subject, String email, String displayName) {}
