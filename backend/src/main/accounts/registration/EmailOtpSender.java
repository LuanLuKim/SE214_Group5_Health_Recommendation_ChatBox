package accounts.registration;

public interface EmailOtpSender {
  void sendVerificationCode(String email, String code, int expiresInMinutes);

  void sendPasswordRecoveryCode(String email, String code, int expiresInMinutes);

  void sendAccountDeletionCode(String email, String code, int expiresInMinutes);
}
