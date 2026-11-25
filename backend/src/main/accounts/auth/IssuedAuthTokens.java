package accounts.auth;

public record IssuedAuthTokens(
    AccountLoginResult account, String accessToken, String refreshToken) {}
