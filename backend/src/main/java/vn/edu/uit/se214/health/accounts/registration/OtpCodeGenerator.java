package vn.edu.uit.se214.health.accounts.registration;

import java.security.SecureRandom;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("postgres")
public class OtpCodeGenerator {
  private static final int CODE_SPACE = 1_000_000;
  private final SecureRandom random = new SecureRandom();

  public String nextCode() {
    return "%06d".formatted(random.nextInt(CODE_SPACE));
  }
}
