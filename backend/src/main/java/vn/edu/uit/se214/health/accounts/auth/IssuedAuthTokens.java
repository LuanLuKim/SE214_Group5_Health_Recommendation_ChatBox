package vn.edu.uit.se214.health.accounts.auth;

public record IssuedAuthTokens(
    AccountLoginResult account, String accessToken, String refreshToken) {}
