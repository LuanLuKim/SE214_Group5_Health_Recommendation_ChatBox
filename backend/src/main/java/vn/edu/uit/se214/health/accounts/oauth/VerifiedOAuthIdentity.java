package vn.edu.uit.se214.health.accounts.oauth;

public record VerifiedOAuthIdentity(
    OAuthProvider provider, String subject, String email, String displayName) {}
