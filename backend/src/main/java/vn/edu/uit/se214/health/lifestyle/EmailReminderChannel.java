package vn.edu.uit.se214.health.lifestyle;

import jakarta.mail.MessagingException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import vn.edu.uit.se214.health.accounts.api.AccountContactQuery;

/**
 * UC-14 email channel over the SMTP settings M5 already uses (SMTP_*, MAIL_FROM). The address is
 * read through M5's {@link AccountContactQuery}; SMTP timeouts come from spring.mail.properties.
 */
class EmailReminderChannel implements ReminderChannel {
  static final String SUBJECT = "Nhắc nhở sinh hoạt";
  static final String FOOTER =
      "\n\nBạn nhận email này vì đã bật nhắc nhở sinh hoạt. Tắt nhắc nhở trong mục Nhắc nhở của"
          + " ứng dụng để ngừng nhận.";

  private final ObjectProvider<JavaMailSender> mailSenders;
  private final MailProperties mail;
  private final ReminderProperties properties;
  private final AccountContactQuery contacts;

  EmailReminderChannel(
      ObjectProvider<JavaMailSender> mailSenders,
      MailProperties mail,
      ReminderProperties properties,
      AccountContactQuery contacts) {
    this.mailSenders = mailSenders;
    this.mail = mail;
    this.properties = properties;
    this.contacts = contacts;
  }

  @Override
  public Type type() {
    return Type.EMAIL;
  }

  @Override
  public boolean available(UUID userId) {
    return sender().isPresent() && contacts.email(userId).isPresent();
  }

  @Override
  public void send(UUID userId, String content) throws SendFailure {
    JavaMailSender sender = sender().orElseThrow(() -> new SendFailure("EMAIL_NOT_CONFIGURED"));
    String to = contacts.email(userId).orElseThrow(() -> new SendFailure("RECIPIENT_UNAVAILABLE"));
    try {
      var message = sender.createMimeMessage();
      var helper = new MimeMessageHelper(message, "UTF-8");
      helper.setFrom(properties.fromAddress());
      helper.setTo(to);
      helper.setSubject(SUBJECT);
      helper.setText(content + FOOTER, false);
      sender.send(message);
    } catch (MessagingException | MailException e) {
      throw new SendFailure("EMAIL_SEND_FAILED");
    }
  }

  /** The mail sender, only when an SMTP host and a sender address are configured. */
  private Optional<JavaMailSender> sender() {
    if (isBlank(mail.getHost()) || isBlank(properties.fromAddress())) return Optional.empty();
    return Optional.ofNullable(mailSenders.getIfAvailable());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
