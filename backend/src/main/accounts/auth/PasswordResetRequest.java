package accounts.auth;

public record PasswordResetRequest(String email, String code, String newPassword) {}
