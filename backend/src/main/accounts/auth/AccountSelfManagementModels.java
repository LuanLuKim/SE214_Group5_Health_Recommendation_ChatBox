package accounts.auth;

import java.util.UUID;

public final class AccountSelfManagementModels {
  private AccountSelfManagementModels() {}

  public record AccountProfile(
      UUID id, String email, String displayName, String phoneNumber, String avatarUrl) {}

  public record ProfileUpdateRequest(String displayName, String phoneNumber, String avatarUrl) {}

  public record ChangePasswordRequest(String currentPassword, String newPassword) {}

  public record DeletionConfirmRequest(String code) {}
}
