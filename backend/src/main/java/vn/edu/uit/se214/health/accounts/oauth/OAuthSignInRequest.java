package vn.edu.uit.se214.health.accounts.oauth;

import vn.edu.uit.se214.health.accounts.auth.AccountConsentRequest;

public record OAuthSignInRequest(String provider, String token, AccountConsentRequest consent) {}
