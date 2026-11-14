package vn.edu.uit.se214.health.accounts.auth;

public record PasswordResetRequest(String email, String code, String newPassword) {}
