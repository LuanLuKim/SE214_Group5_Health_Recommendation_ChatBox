package accounts.rbac;

import java.util.List;
import java.util.UUID;
import accounts.rbac.RbacModels.AuditLogView;
import accounts.rbac.RbacModels.AccountCreateRequest;
import accounts.rbac.RbacModels.Page;
import accounts.rbac.RbacModels.PermissionCreateRequest;
import accounts.rbac.RbacModels.PermissionUpdateRequest;
import accounts.rbac.RbacModels.PermissionView;
import accounts.rbac.RbacModels.RoleCreateRequest;
import accounts.rbac.RbacModels.RolePermissionsRequest;
import accounts.rbac.RbacModels.RoleUpdateRequest;
import accounts.rbac.RbacModels.RoleView;
import accounts.rbac.RbacModels.StatusRequest;
import accounts.rbac.RbacModels.UserRolesRequest;
import accounts.rbac.RbacModels.UserSummary;
import shared.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Profile("postgres")
public class AccountRbacController {
  private final AccountRbacService service;

  public AccountRbacController(AccountRbacService service) {
    this.service = service;
  }

  @GetMapping("/users")
  public ApiResponse.Success<Page<UserSummary>> users(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String role,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.of(service.searchUsers(q, status, role, page, size));
  }

  @PostMapping("/users")
  public ResponseEntity<ApiResponse.Success<UserSummary>> createUser(
      @RequestBody AccountCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.of(service.createUser(request)));
  }

  @PatchMapping("/users/{id}/status")
  public ApiResponse.Success<UserSummary> setUserStatus(
      @PathVariable UUID id, @RequestBody StatusRequest request) {
    return ApiResponse.of(service.setUserStatus(id, request));
  }

  @PutMapping("/users/{id}/roles")
  public ApiResponse.Success<UserSummary> setUserRoles(
      @PathVariable UUID id, @RequestBody UserRolesRequest request) {
    return ApiResponse.of(service.setUserRoles(id, request));
  }

  @GetMapping("/roles")
  public ApiResponse.Success<List<RoleView>> roles() {
    return ApiResponse.of(service.listRoles());
  }

  @PostMapping("/roles")
  public ResponseEntity<ApiResponse.Success<RoleView>> createRole(
      @RequestBody RoleCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.createRole(request)));
  }

  @PatchMapping("/roles/{key}")
  public ApiResponse.Success<RoleView> updateRole(
      @PathVariable String key, @RequestBody RoleUpdateRequest request) {
    return ApiResponse.of(service.updateRole(key, request));
  }

  @DeleteMapping("/roles/{key}")
  public ApiResponse.Success<Void> deleteRole(@PathVariable String key) {
    service.deleteRole(key);
    return ApiResponse.of(null);
  }

  @PutMapping("/roles/{key}/permissions")
  public ApiResponse.Success<RoleView> setRolePermissions(
      @PathVariable String key, @RequestBody RolePermissionsRequest request) {
    return ApiResponse.of(service.setRolePermissions(key, request));
  }

  @GetMapping("/permissions")
  public ApiResponse.Success<List<PermissionView>> permissions() {
    return ApiResponse.of(service.listPermissions());
  }

  @PostMapping("/permissions")
  public ResponseEntity<ApiResponse.Success<PermissionView>> createPermission(
      @RequestBody PermissionCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.of(service.createPermission(request)));
  }

  @PatchMapping("/permissions/{key}")
  public ApiResponse.Success<PermissionView> updatePermission(
      @PathVariable String key, @RequestBody PermissionUpdateRequest request) {
    return ApiResponse.of(service.updatePermission(key, request));
  }

  @DeleteMapping("/permissions/{key}")
  public ApiResponse.Success<Void> deletePermission(@PathVariable String key) {
    service.deletePermission(key);
    return ApiResponse.of(null);
  }

  @GetMapping("/audit-logs")
  public ApiResponse.Success<Page<AuditLogView>> auditLogs(
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String targetId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return ApiResponse.of(
        service.listAuditLogs(action, targetType, targetId, page, size));
  }
}
