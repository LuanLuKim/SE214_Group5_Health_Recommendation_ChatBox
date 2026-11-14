package vn.edu.uit.se214.health.accounts.registration;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import vn.edu.uit.se214.health.accounts.persistence.AccountRegistrationRepository;
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
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    repository.deleteExpiredChallenges(now);
    repository.deleteExpiredRecoveryChallenges(now);
    repository.deleteExpiredRefreshTokens(now);
  }
}
