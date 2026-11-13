package vn.edu.uit.se214.health.accounts;

public interface EmailOtpSender {
  void sendVerificationCode(String email, String code, int expiresInMinutes);
}
