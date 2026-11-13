package vn.edu.uit.se214.health.accounts;

public record OAuthRegistrationRequest(
    String provider, String token, AccountConsentRequest consent) {}
