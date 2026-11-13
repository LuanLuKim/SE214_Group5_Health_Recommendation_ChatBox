package vn.edu.uit.se214.health.accounts;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("postgres")
public class RegistrationChallengeCleanup {
  private final AccountRegistrationRepository repository;

  public RegistrationChallengeCleanup(AccountRegistrationRepository repository) {
    this.repository = repository;
  }

  @Scheduled(fixedDelay = 60_000)
  public void deleteExpiredChallenges() {
    repository.deleteExpiredChallenges(OffsetDateTime.now(ZoneOffset.UTC));
  }
}
