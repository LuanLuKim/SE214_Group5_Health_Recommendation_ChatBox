package accounts.oauth;

public interface OAuthProviderVerifier {
  VerifiedOAuthIdentity verify(OAuthProvider provider, String token);
}
