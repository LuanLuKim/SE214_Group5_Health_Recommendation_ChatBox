package accounts.auth;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import accounts.AccountApiException;
import accounts.config.AccountsProperties;
import accounts.persistence.AccountRegistrationRepository;
import shared.web.ApiException;

@Service
@Profile("postgres")
public class AccountConsentService implements AccountConsentUseCase {
  private final AccountRegistrationRepository repository;
  private final AccountsProperties properties;

  public AccountConsentService(
      AccountRegistrationRepository repository, AccountsProperties properties) {
    this.repository = repository;
    this.properties = properties;
  }

  @Override
  public void accept(UUID userId, AccountConsentRequest request) {
    AcceptedConsent consent = validate(request);
    repository.upsertConsent(
        userId,
        consent.termsVersion(),
        true,
        true,
        true,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private AcceptedConsent validate(AccountConsentRequest request) {
    if (request == null
        || !Boolean.TRUE.equals(request.termsAccepted())
        || !Boolean.TRUE.equals(request.healthDataProcessingAccepted())
        || !Boolean.TRUE.equals(request.nonDiagnosticAcknowledged())) {
      throw AccountApiException.consentRequired();
    }
    if (!Boolean.TRUE.equals(request.adultConfirmed())) {
      throw AccountApiException.adultConfirmationRequired();
    }
    if (properties.termsVersion() == null
        || properties.termsVersion().isBlank()
        || !properties.termsVersion().equals(request.termsVersion())) {
      throw AccountApiException.termsVersionInvalid();
    }
    return new AcceptedConsent(properties.termsVersion());
  }

  private record AcceptedConsent(String termsVersion) {}
}
