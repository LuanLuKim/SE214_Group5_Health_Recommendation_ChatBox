package vn.edu.uit.se214.health.accounts.auth;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import vn.edu.uit.se214.health.shared.web.ApiException;

@Service
@Profile("mock")
public class MockAccountConsentService implements AccountConsentUseCase {
  @Override
  public void accept(UUID userId, AccountConsentRequest request) {
    throw ApiException.authNotConfigured();
  }
}
