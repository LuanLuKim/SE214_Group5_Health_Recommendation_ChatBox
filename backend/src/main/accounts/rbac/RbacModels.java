package accounts.rbac;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RbacModels {
  private RbacModels() {}

  public record UserSummary(
      UUID id,
      String email,
      String displayName,
      String status,
      List<String> roles,
      OffsetDateTime createdAt) {}

  public record RoleView(
      String key,
      String name,
      String description,
      boolean builtIn,
      List<String> permissions,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record PermissionView(
      String key,
      String name,
      String description,
      boolean builtIn,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record AuditLogView(
      UUID id,
      UUID actorUserId,
      String action,
      String targetType,
      String targetId,
      Map<String, Object> details,
      OffsetDateTime createdAt) {}

  public record Page<T>(List<T> items, int page, int size, long total) {}

  public record StatusRequest(String status) {}

  public record AccountCreateRequest(String email, String displayName, String password) {}

  public record UserRolesRequest(List<String> roles) {}

  public record RoleCreateRequest(
      String key, String name, String description, List<String> permissions) {}

  public record RoleUpdateRequest(String name, String description) {}

  public record RolePermissionsRequest(List<String> permissions) {}

  public record PermissionCreateRequest(String key, String name, String description) {}

  public record PermissionUpdateRequest(String name, String description) {}
}
