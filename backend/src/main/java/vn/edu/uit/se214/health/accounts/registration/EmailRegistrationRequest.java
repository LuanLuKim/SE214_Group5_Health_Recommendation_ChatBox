package vn.edu.uit.se214.health.accounts.registration;

import vn.edu.uit.se214.health.accounts.auth.AccountConsentRequest;
public record EmailRegistrationRequest(
    String email, String displayName, String password, AccountConsentRequest consent) {}
