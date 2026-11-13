package vn.edu.uit.se214.health.accounts;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import vn.edu.uit.se214.health.shared.web.ApiException;

@Service
@Profile("mock")
public class MockAccountRegistrationService implements AccountRegistrationUseCase {
  @Override
  public RegistrationInitiation startEmailRegistration(EmailRegistrationRequest request) {
    throw ApiException.registrationUnavailable();
  }

  @Override
  public RegistrationResult verifyEmailRegistration(EmailOtpVerifyRequest request) {
    throw ApiException.registrationUnavailable();
  }

  @Override
  public RegistrationInitiation resendEmailVerification(EmailOtpResendRequest request) {
    throw ApiException.registrationUnavailable();
  }

  @Override
  public RegistrationResult registerOAuth(OAuthRegistrationRequest request) {
    throw ApiException.registrationUnavailable();
  }
}
