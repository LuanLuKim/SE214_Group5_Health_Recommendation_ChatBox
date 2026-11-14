package vn.edu.uit.se214.health.accounts.registration;

import jakarta.mail.MessagingException;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import vn.edu.uit.se214.health.accounts.config.EmailOtpProperties;
import vn.edu.uit.se214.health.shared.web.ApiException;

@Component
@Profile("postgres")
public class SmtpEmailOtpSender implements EmailOtpSender {
  private final ObjectProvider<JavaMailSender> mailSenders;
  private final MailProperties mailProperties;
  private final EmailOtpProperties otpProperties;

  public SmtpEmailOtpSender(
      ObjectProvider<JavaMailSender> mailSenders,
      MailProperties mailProperties,
      EmailOtpProperties otpProperties) {
    this.mailSenders = mailSenders;
    this.mailProperties = mailProperties;
    this.otpProperties = otpProperties;
  }

  @Override
  public void sendVerificationCode(String email, String code, int expiresInMinutes) {
    send(
        email,
        "Mã xác nhận đăng ký tài khoản",
        "Mã OTP đăng ký của bạn là: ",
        code,
        expiresInMinutes,
        "Nếu bạn không yêu cầu đăng ký, hãy bỏ qua email này.");
  }

  @Override
  public void sendPasswordRecoveryCode(String email, String code, int expiresInMinutes) {
    send(
        email,
        "Mã khôi phục mật khẩu",
        "Mã khôi phục mật khẩu của bạn là: ",
        code,
        expiresInMinutes,
        "Nếu bạn không yêu cầu khôi phục mật khẩu, hãy bỏ qua email này.");
  }

  private void send(
      String email,
      String subject,
      String prefix,
      String code,
      int expiresInMinutes,
      String notice) {
    JavaMailSender mailSender = mailSenders.getIfAvailable();
    if (mailSender == null
        || mailProperties.getHost() == null
        || mailProperties.getHost().isBlank()
        || otpProperties.fromAddress() == null
        || otpProperties.fromAddress().isBlank()) {
      throw ApiException.emailVerificationUnavailable();
    }

    try {
      var message = mailSender.createMimeMessage();
      var helper = new MimeMessageHelper(message, "UTF-8");
      helper.setFrom(otpProperties.fromAddress());
      helper.setTo(email);
      helper.setSubject(subject);
      helper.setText(
          prefix + code + "\nMã có hiệu lực trong " + expiresInMinutes + " phút. " + notice,
          false);
      mailSender.send(message);
    } catch (MessagingException | MailException e) {
      throw ApiException.emailDeliveryFailed();
    }
  }
}
