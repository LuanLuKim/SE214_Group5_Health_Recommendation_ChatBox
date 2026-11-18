package vn.edu.uit.se214.health.accounts.auth;

import java.util.UUID;

public interface AccountConsentUseCase {
  void accept(UUID userId, AccountConsentRequest request);
}
