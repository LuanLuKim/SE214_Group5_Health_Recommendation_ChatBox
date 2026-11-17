package vn.edu.uit.se214.health.accounts.auth;

import java.util.UUID;
import vn.edu.uit.se214.health.accounts.auth.AccountSelfManagementModels.ChangePasswordRequest;
import vn.edu.uit.se214.health.accounts.auth.AccountSelfManagementModels.AccountProfile;
import vn.edu.uit.se214.health.accounts.auth.AccountSelfManagementModels.ProfileUpdateRequest;

public interface AccountSelfManagementUseCase {
  AccountProfile profile(UUID userId);

  AccountProfile updateProfile(UUID userId, ProfileUpdateRequest request);

  void changePassword(UUID userId, ChangePasswordRequest request);

  void requestDeletion(UUID userId);

  void confirmDeletion(UUID userId, String code);
}
