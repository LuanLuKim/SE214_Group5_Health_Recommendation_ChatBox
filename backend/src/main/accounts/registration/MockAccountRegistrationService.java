package accounts.registration;


import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import shared.web.ApiException;

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

}
