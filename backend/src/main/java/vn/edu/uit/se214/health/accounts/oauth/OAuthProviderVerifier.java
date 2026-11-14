package vn.edu.uit.se214.health.accounts.oauth;

public interface OAuthProviderVerifier {
  VerifiedOAuthIdentity verify(OAuthProvider provider, String token);
}
