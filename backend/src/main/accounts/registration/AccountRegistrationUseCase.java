package accounts.registration;


public interface AccountRegistrationUseCase {
  RegistrationInitiation startEmailRegistration(EmailRegistrationRequest request);

  RegistrationResult verifyEmailRegistration(EmailOtpVerifyRequest request);

  RegistrationInitiation resendEmailVerification(EmailOtpResendRequest request);
}
