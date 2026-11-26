package accounts.auth;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import accounts.auth.AccountSelfManagementModels.ChangePasswordRequest;
import accounts.auth.AccountSelfManagementModels.AccountProfile;
import accounts.auth.AccountSelfManagementModels.ProfileUpdateRequest;
import shared.web.ApiException;

@Service
@Profile("mock")
public class MockAccountSelfManagementService implements AccountSelfManagementUseCase {
  @Override
  public AccountProfile profile(UUID userId) {
    throw ApiException.authenticationUnavailable();
  }

  @Override
  public AccountProfile updateProfile(UUID userId, ProfileUpdateRequest request) {
    throw ApiException.authenticationUnavailable();
  }

  @Override
  public void changePassword(UUID userId, ChangePasswordRequest request) {
    throw ApiException.authenticationUnavailable();
  }

  @Override
  public void requestDeletion(UUID userId) {
    throw ApiException.authenticationUnavailable();
  }

  @Override
  public void confirmDeletion(UUID userId, String code) {
    throw ApiException.authenticationUnavailable();
  }
}
