package vn.edu.uit.se214.health.accounts;

public interface OAuthProviderVerifier {
  VerifiedOAuthIdentity verify(OAuthProvider provider, String token);
}
