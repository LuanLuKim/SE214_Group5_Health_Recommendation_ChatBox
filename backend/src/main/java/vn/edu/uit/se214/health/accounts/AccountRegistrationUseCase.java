package vn.edu.uit.se214.health.accounts;

public interface AccountRegistrationUseCase {
  RegistrationInitiation startEmailRegistration(EmailRegistrationRequest request);

  RegistrationResult verifyEmailRegistration(EmailOtpVerifyRequest request);

  RegistrationInitiation resendEmailVerification(EmailOtpResendRequest request);

  RegistrationResult registerOAuth(OAuthRegistrationRequest request);
}
