package accounts.rbac;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("postgres")
@ConditionalOnProperty(
    prefix = "app.accounts.admin",
    name = "bootstrap-enabled",
    havingValue = "true")
public class AdminAccountBootstrap implements ApplicationRunner {
  private static final Pattern EMAIL =
      Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$");
  private final AccountRbacRepository repository;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate transaction;
  private final String adminEmail;
  private final String adminPassword;

  public AdminAccountBootstrap(
      AccountRbacRepository repository,
      PasswordEncoder passwordEncoder,
      TransactionTemplate transaction,
      org.springframework.core.env.Environment environment) {
    this.repository = repository;
    this.passwordEncoder = passwordEncoder;
    this.transaction = transaction;
    this.adminEmail = environment.getProperty("app.accounts.admin.email", "").trim();
    this.adminPassword = environment.getProperty("app.accounts.admin.password", "");
  }

  @Override
  public void run(ApplicationArguments args) {
    String email = adminEmail.toLowerCase(Locale.ROOT);
    if (!EMAIL.matcher(email).matches()
        || email.length() > 254
        || adminPassword.getBytes(StandardCharsets.UTF_8).length < 12
        || adminPassword.getBytes(StandardCharsets.UTF_8).length > 72
        || adminPassword.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalStateException(
          "Set ADMIN_EMAIL to a valid email and ADMIN_PASSWORD to a 12-72 byte secret before starting DATA_MODE=postgres.");
    }
    transaction.executeWithoutResult(
        status -> {
          var existing = repository.findUserIdByEmail(email);
          if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            repository.insertBootstrapAdmin(
                id,
                email,
                "Quản trị viên",
                passwordEncoder.encode(adminPassword),
                OffsetDateTime.now(ZoneOffset.UTC));
            repository.insertAuditLog(
                id,
                "ADMIN_ACCOUNT_BOOTSTRAPPED",
                "USER",
                id.toString(),
                "{\"email\":\"" + email.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
            return;
          }
          UUID id = existing.get();
          if (!repository.isActiveAdmin(id))
            throw new IllegalStateException(
                "Configured ADMIN_EMAIL already belongs to a non-admin or locked account.");
        });
  }
}
