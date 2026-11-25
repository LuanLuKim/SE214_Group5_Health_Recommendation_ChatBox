package accounts.oauth;

public record VerifiedOAuthIdentity(
    OAuthProvider provider, String subject, String email, String displayName) {}
