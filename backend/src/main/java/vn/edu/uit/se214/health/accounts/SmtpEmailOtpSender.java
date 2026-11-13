package vn.edu.uit.se214.health.accounts;

import jakarta.mail.MessagingException;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
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
      helper.setSubject("Mã xác nhận đăng ký tài khoản");
      helper.setText(
          "Mã OTP đăng ký của bạn là: " + code + "\nMã có hiệu lực trong "
              + expiresInMinutes + " phút. Nếu bạn không yêu cầu đăng ký, hãy bỏ qua email này.",
          false);
      mailSender.send(message);
    } catch (MessagingException | MailException e) {
      throw ApiException.emailDeliveryFailed();
    }
  }
}
