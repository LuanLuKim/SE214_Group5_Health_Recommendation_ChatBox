package vn.edu.uit.se214.health.accounts.auth;

import vn.edu.uit.se214.health.accounts.oauth.OAuthSignInRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import vn.edu.uit.se214.health.shared.web.ApiException;

@Service
@Profile("mock")
public class MockAccountAuthenticationService implements AccountAuthenticationUseCase {
  @Override
  public IssuedAuthTokens login(AccountLoginRequest request) {
    throw ApiException.authenticationUnavailable();
  }

  @Override
  public IssuedAuthTokens oauthSignIn(OAuthSignInRequest request) {
    throw ApiException.authenticationUnavailable();
  }

  @Override
  public IssuedAuthTokens refresh(String refreshToken) {
    throw ApiException.authenticationUnavailable();
  }

  @Override
  public PasswordRecoveryAccepted requestPasswordRecovery(PasswordRecoveryRequest request) {
    throw ApiException.authenticationUnavailable();
  }

  @Override
  public void resetPassword(PasswordResetRequest request) {
    throw ApiException.authenticationUnavailable();
  }

  @Override
  public void logout(String refreshToken) {
    throw ApiException.authenticationUnavailable();
  }

}
