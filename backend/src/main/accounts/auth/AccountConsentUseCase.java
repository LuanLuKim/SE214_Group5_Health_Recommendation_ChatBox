package accounts.auth;

import java.util.UUID;

public interface AccountConsentUseCase {
  void accept(UUID userId, AccountConsentRequest request);
}
