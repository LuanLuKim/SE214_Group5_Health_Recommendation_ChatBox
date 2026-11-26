package accounts.auth;

import accounts.oauth.OAuthSignInRequest;

public interface AccountAuthenticationUseCase {
  IssuedAuthTokens login(AccountLoginRequest request);

  IssuedAuthTokens oauthSignIn(OAuthSignInRequest request);

  IssuedAuthTokens refresh(String refreshToken);

  PasswordRecoveryAccepted requestPasswordRecovery(PasswordRecoveryRequest request);

  void resetPassword(PasswordResetRequest request);

  void logout(String refreshToken);
}
