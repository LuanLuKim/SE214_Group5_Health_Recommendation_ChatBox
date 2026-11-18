package vn.edu.uit.se214.health.accounts.auth;

import vn.edu.uit.se214.health.accounts.oauth.OAuthSignInRequest;

public interface AccountAuthenticationUseCase {
  IssuedAuthTokens login(AccountLoginRequest request);

  IssuedAuthTokens oauthSignIn(OAuthSignInRequest request);

  IssuedAuthTokens refresh(String refreshToken);

  PasswordRecoveryAccepted requestPasswordRecovery(PasswordRecoveryRequest request);

  void resetPassword(PasswordResetRequest request);

  void logout(String refreshToken);
}
