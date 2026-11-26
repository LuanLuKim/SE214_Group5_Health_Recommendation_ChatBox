package accounts.registration;

import accounts.auth.AccountConsentRequest;
public record EmailRegistrationRequest(
    String email, String displayName, String password, AccountConsentRequest consent) {}
