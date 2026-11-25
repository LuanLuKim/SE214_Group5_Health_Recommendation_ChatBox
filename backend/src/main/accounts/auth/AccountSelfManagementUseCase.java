package accounts.auth;

import java.util.UUID;
import accounts.auth.AccountSelfManagementModels.ChangePasswordRequest;
import accounts.auth.AccountSelfManagementModels.AccountProfile;
import accounts.auth.AccountSelfManagementModels.ProfileUpdateRequest;

public interface AccountSelfManagementUseCase {
  AccountProfile profile(UUID userId);

  AccountProfile updateProfile(UUID userId, ProfileUpdateRequest request);

  void changePassword(UUID userId, ChangePasswordRequest request);

  void requestDeletion(UUID userId);

  void confirmDeletion(UUID userId, String code);
}
