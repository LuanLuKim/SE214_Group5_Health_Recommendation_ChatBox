package accounts.oauth;

import accounts.auth.AccountConsentRequest;

public record OAuthSignInRequest(String provider, String token, AccountConsentRequest consent) {}
