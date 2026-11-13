package vn.edu.uit.se214.health.accounts;

public record EmailRegistrationRequest(
    String email, String displayName, String password, AccountConsentRequest consent) {}
