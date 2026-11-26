package accounts.rbac;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import shared.web.ApiException;

@Service
@Profile("postgres")
public class AccountRbacService {
  private static final Pattern ROLE_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
  private static final Pattern PERMISSION_KEY =
      Pattern.compile("^[A-Z][A-Z0-9_.:-]{1,79}$");
  private static final Pattern EMAIL =
      Pattern.compile(
          "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$");
  private static final Set<String> BUILT_IN_ROLES =
      Set.of("GUEST", "USER", "CONTENT_EXPERT", "ADMIN");
  private static final int MAX_PAGE_SIZE = 100;

  private final AccountRbacRepository repository;
  private final ObjectMapper objectMapper;
  private final PasswordEncoder passwordEncoder;

  public AccountRbacService(
      AccountRbacRepository repository,
      ObjectMapper objectMapper,
      PasswordEncoder passwordEncoder) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.passwordEncoder = passwordEncoder;
  }

  public Page<UserSummary> searchUsers(
      String search, String status, String role, int page, int size) {
    requirePermission("PERM_USER_MANAGE");
    int safePage = checkedPage(page);
    int safeSize = checkedSize(size);
    String query = optionalText(search, 200);
    String selectedStatus = optionalStatus(status);
    String selectedRole = optionalRole(role);
    if (selectedRole != null && !repository.roleExists(selectedRole))
      throw notFound("ROLE_NOT_FOUND", "Không tìm thấy vai trò.");
    List<UserSummary> users =
        repository.searchUsers(
            query == null ? null : query.toLowerCase(Locale.ROOT),
            selectedStatus,
            selectedRole,
            safeSize,
            Math.multiplyExact(safePage, safeSize));
    return new Page<>(
        users,
        safePage,
        safeSize,
        repository.countUsers(
            query == null ? null : query.toLowerCase(Locale.ROOT),
            selectedStatus,
            selectedRole));
  }

  @Transactional
  public UserSummary createUser(AccountCreateRequest request) {
    requirePermission("PERM_USER_MANAGE");
    UUID actor = currentActor();
    if (request == null)
      throw invalid("INVALID_ACCOUNT", "Thông tin tài khoản không hợp lệ.");
    String email = normalizeEmail(request.email());
    String displayName = normalizeDisplayName(request.displayName());
    String password = validatePassword(request.password());
    UUID id = UUID.randomUUID();
    OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    if (!repository.createUser(id, email, displayName, passwordEncoder.encode(password), createdAt))
      throw new ApiException(
          HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email đã được đăng ký.");
    audit(
        actor,
        "USER_CREATED",
        "USER",
        id.toString(),
        Map.of("email", email, "roles", List.of("USER")));
    return repository.findUser(id)
        .orElseThrow(() -> new IllegalStateException("Created account could not be loaded."));
  }

  @Transactional
  public UserSummary setUserStatus(UUID targetId, StatusRequest request) {
    requirePermission("PERM_USER_MANAGE");
    UUID actor = currentActor();
    if (targetId == null || request == null || request.status() == null)
      throw invalid("INVALID_USER_STATUS", "Trạng thái tài khoản không hợp lệ.");
    String nextStatus = request.status().trim().toUpperCase(Locale.ROOT);
    if (!Set.of("ACTIVE", "LOCKED").contains(nextStatus))
      throw invalid("INVALID_USER_STATUS", "Trạng thái chỉ có thể là ACTIVE hoặc LOCKED.");

    repository.lockAdminRole();
    String previousStatus =
        repository
            .findUserStatus(targetId, true)
            .orElseThrow(() -> notFound("USER_NOT_FOUND", "Không tìm thấy tài khoản."));
    if (!previousStatus.equals(nextStatus) && hasRole(targetId, "ADMIN"))
      requirePermission("PERM_ADMIN_ROLE_MANAGE");
    if (actor.equals(targetId) && "LOCKED".equals(nextStatus))
      throw conflict("SELF_LOCK_FORBIDDEN", "Không thể khóa tài khoản quản trị đang sử dụng.");
    if ("LOCKED".equals(nextStatus)
        && "ACTIVE".equals(previousStatus)
        && hasRole(targetId, "ADMIN")
        && repository.countActiveAdmins() <= 1) {
      throw conflict("LAST_ACTIVE_ADMIN", "Không thể khóa quản trị viên đang hoạt động cuối cùng.");
    }

    if (!previousStatus.equals(nextStatus)) {
      repository.updateUserStatus(targetId, nextStatus);
      if ("LOCKED".equals(nextStatus))
        repository.revokeRefreshTokens(targetId, OffsetDateTime.now(ZoneOffset.UTC));
      audit(
          actor,
          "USER_STATUS_CHANGED",
          "USER",
          targetId.toString(),
          Map.of("from", previousStatus, "to", nextStatus));
    }
    return repository.findUser(targetId)
        .orElseThrow(() -> notFound("USER_NOT_FOUND", "Không tìm thấy tài khoản."));
  }

  @Transactional
  public UserSummary setUserRoles(UUID targetId, UserRolesRequest request) {
    requirePermission("PERM_USER_MANAGE");
    UUID actor = currentActor();
    if (targetId == null || request == null || request.roles() == null)
      throw invalid("INVALID_USER_ROLES", "Danh sách vai trò không hợp lệ.");
    List<String> nextRoles = normalizeKeys(request.roles(), ROLE_KEY, 20, "vai trò");

    repository.lockAdminRole();
    String previousStatus =
        repository
            .findUserStatus(targetId, true)
            .orElseThrow(() -> notFound("USER_NOT_FOUND", "Không tìm thấy tài khoản."));
    if (!repository.allRolesExist(nextRoles))
      throw notFound("ROLE_NOT_FOUND", "Danh sách có vai trò không tồn tại.");
    List<String> previousRoles = repository.findUserRoles(targetId);
    boolean rolesChanged = !previousRoles.equals(nextRoles);
    if (rolesChanged
        && (previousRoles.contains("ADMIN") || nextRoles.contains("ADMIN")))
      requirePermission("PERM_ADMIN_ROLE_MANAGE");
    if (previousRoles.contains("CONTENT_EXPERT") != nextRoles.contains("CONTENT_EXPERT"))
      requirePermission("PERM_CONTENT_EXPERT_ROLE_MANAGE");
    if (actor.equals(targetId) && previousRoles.contains("ADMIN") && !nextRoles.contains("ADMIN"))
      throw conflict("SELF_ADMIN_REVOKE_FORBIDDEN", "Không thể tự thu hồi vai trò quản trị.");
    if (previousRoles.contains("ADMIN") && !nextRoles.contains("ADMIN")) {
      if (repository.countAdmins() <= 1)
        throw conflict("LAST_ADMIN", "Không thể thu hồi vai trò của quản trị viên cuối cùng.");
      if ("ACTIVE".equals(previousStatus) && repository.countActiveAdmins() <= 1)
        throw conflict(
            "LAST_ACTIVE_ADMIN",
            "Không thể thu hồi vai trò của quản trị viên đang hoạt động cuối cùng.");
    }

    if (!previousRoles.equals(nextRoles)) {
      repository.replaceUserRoles(targetId, nextRoles);
      audit(
          actor,
          "USER_ROLES_CHANGED",
          "USER",
          targetId.toString(),
          Map.of("from", previousRoles, "to", nextRoles));
    }
    return repository.findUser(targetId)
        .orElseThrow(() -> notFound("USER_NOT_FOUND", "Không tìm thấy tài khoản."));
  }

  public List<RoleView> listRoles() {
    requirePermission("PERM_RBAC_MANAGE");
    return repository.listRoles();
  }

  @Transactional
  public RoleView createRole(RoleCreateRequest request) {
    requirePermission("PERM_RBAC_MANAGE");
    UUID actor = currentActor();
    if (request == null) throw invalid("INVALID_ROLE", "Thông tin vai trò không hợp lệ.");
    String key = requiredKey(request.key(), ROLE_KEY, "vai trò");
    if (BUILT_IN_ROLES.contains(key))
      throw conflict("BUILT_IN_ROLE", "Không thể tạo lại vai trò hệ thống.");
    if (repository.roleExists(key))
      throw conflict("ROLE_ALREADY_EXISTS", "Mã vai trò đã được sử dụng.");
    String name = requiredText(request.name(), 100, "Tên vai trò");
    String description = text(request.description(), 500, "Mô tả vai trò");
    List<String> permissions =
        normalizeKeys(request.permissions() == null ? List.of() : request.permissions(),
            PERMISSION_KEY, 50, "permission");
    if (!repository.allPermissionsExist(permissions))
      throw notFound("PERMISSION_NOT_FOUND", "Danh sách có permission không tồn tại.");
    repository.createRole(key, name, description, permissions);
    audit(
        actor,
        "ROLE_CREATED",
        "ROLE",
        key,
        Map.of("name", name, "permissions", permissions));
    return repository.findRole(key).orElseThrow();
  }

  @Transactional
  public RoleView updateRole(String keyValue, RoleUpdateRequest request) {
    requirePermission("PERM_RBAC_MANAGE");
    UUID actor = currentActor();
    String key = requiredKey(keyValue, ROLE_KEY, "vai trò");
    if (request == null || (request.name() == null && request.description() == null))
      throw invalid("INVALID_ROLE", "Thông tin vai trò không hợp lệ.");
    RoleView previous =
        repository
            .findRole(key)
            .orElseThrow(() -> notFound("ROLE_NOT_FOUND", "Không tìm thấy vai trò."));
    String name =
        request.name() == null
            ? previous.name()
            : requiredText(request.name(), 100, "Tên vai trò");
    String description =
        request.description() == null
            ? previous.description()
            : text(request.description(), 500, "Mô tả vai trò");
    repository.updateRole(key, name, description);
    if (!previous.name().equals(name) || !previous.description().equals(description))
      audit(
          actor,
          "ROLE_UPDATED",
          "ROLE",
          key,
          Map.of(
              "from", Map.of("name", previous.name(), "description", previous.description()),
              "to", Map.of("name", name, "description", description)));
    return repository.findRole(key).orElseThrow();
  }

  @Transactional
  public void deleteRole(String keyValue) {
    requirePermission("PERM_RBAC_MANAGE");
    UUID actor = currentActor();
    String key = requiredKey(keyValue, ROLE_KEY, "vai trò");
    if (BUILT_IN_ROLES.contains(key))
      throw conflict("BUILT_IN_ROLE", "Không thể xóa vai trò hệ thống.");
    if (!repository.roleExists(key)) throw notFound("ROLE_NOT_FOUND", "Không tìm thấy vai trò.");
    if (repository.countUsersWithRole(key) > 0)
      throw conflict("ROLE_IN_USE", "Không thể xóa vai trò đang được cấp cho tài khoản.");
    repository.deleteRole(key);
    audit(actor, "ROLE_DELETED", "ROLE", key, Map.of());
  }

  @Transactional
  public RoleView setRolePermissions(String keyValue, RolePermissionsRequest request) {
    requirePermission("PERM_RBAC_MANAGE");
    UUID actor = currentActor();
    String key = requiredKey(keyValue, ROLE_KEY, "vai trò");
    if (request == null || request.permissions() == null)
      throw invalid("INVALID_ROLE_PERMISSIONS", "Danh sách permission không hợp lệ.");
    if (!repository.roleExists(key)) throw notFound("ROLE_NOT_FOUND", "Không tìm thấy vai trò.");
    if ("ADMIN".equals(key))
      throw conflict("ADMIN_ROLE_PROTECTED", "Không thể thay đổi quyền quản trị hệ thống.");
    List<String> permissions =
        normalizeKeys(request.permissions(), PERMISSION_KEY, 50, "permission");
    if (!repository.allPermissionsExist(permissions))
      throw notFound("PERMISSION_NOT_FOUND", "Danh sách có permission không tồn tại.");
    List<String> previous =
        repository.findRole(key).map(RoleView::permissions).orElse(List.of());
    repository.replaceRolePermissions(key, permissions);
    if (!previous.equals(permissions))
      audit(
          actor,
          "ROLE_PERMISSIONS_CHANGED",
          "ROLE",
          key,
          Map.of("from", previous, "to", permissions));
    return repository.findRole(key).orElseThrow();
  }

  public List<PermissionView> listPermissions() {
    requirePermission("PERM_RBAC_MANAGE");
    return repository.listPermissions();
  }

  @Transactional
  public PermissionView createPermission(PermissionCreateRequest request) {
    requirePermission("PERM_RBAC_MANAGE");
    UUID actor = currentActor();
    if (request == null)
      throw invalid("INVALID_PERMISSION", "Thông tin permission không hợp lệ.");
    String key = requiredKey(request.key(), PERMISSION_KEY, "permission");
    if (repository.permissionExists(key))
      throw conflict("PERMISSION_ALREADY_EXISTS", "Mã permission đã được sử dụng.");
    String name = requiredText(request.name(), 100, "Tên permission");
    String description = text(request.description(), 500, "Mô tả permission");
    repository.createPermission(key, name, description);
    audit(
        actor,
        "PERMISSION_CREATED",
        "PERMISSION",
        key,
        Map.of("name", name, "description", description));
    return repository.listPermissions().stream()
        .filter(permission -> permission.key().equals(key))
        .findFirst()
        .orElseThrow();
  }

  @Transactional
  public PermissionView updatePermission(String keyValue, PermissionUpdateRequest request) {
    requirePermission("PERM_RBAC_MANAGE");
    UUID actor = currentActor();
    String key = requiredKey(keyValue, PERMISSION_KEY, "permission");
    if (request == null || (request.name() == null && request.description() == null))
      throw invalid("INVALID_PERMISSION", "Thông tin permission không hợp lệ.");
    PermissionView previous =
        repository.listPermissions().stream()
            .filter(permission -> permission.key().equals(key))
            .findFirst()
            .orElseThrow(() -> notFound("PERMISSION_NOT_FOUND", "Không tìm thấy permission."));
    if (isBuiltInPermission(key))
      throw conflict("BUILT_IN_PERMISSION", "Không thể sửa permission hệ thống.");
    String name =
        request.name() == null
            ? previous.name()
            : requiredText(request.name(), 100, "Tên permission");
    String description =
        request.description() == null
            ? previous.description()
            : text(request.description(), 500, "Mô tả permission");
    repository.updatePermission(key, name, description);
    if (!previous.name().equals(name) || !previous.description().equals(description))
      audit(
          actor,
          "PERMISSION_UPDATED",
          "PERMISSION",
          key,
          Map.of(
              "from", Map.of("name", previous.name(), "description", previous.description()),
              "to", Map.of("name", name, "description", description)));
    return repository.listPermissions().stream()
        .filter(permission -> permission.key().equals(key))
        .findFirst()
        .orElseThrow();
  }

  @Transactional
  public void deletePermission(String keyValue) {
    requirePermission("PERM_RBAC_MANAGE");
    UUID actor = currentActor();
    String key = requiredKey(keyValue, PERMISSION_KEY, "permission");
    if (isBuiltInPermission(key))
      throw conflict("BUILT_IN_PERMISSION", "Không thể xóa permission hệ thống.");
    if (!repository.permissionExists(key))
      throw notFound("PERMISSION_NOT_FOUND", "Không tìm thấy permission.");
    if (repository.countRolesWithPermission(key) > 0)
      throw conflict("PERMISSION_IN_USE", "Không thể xóa permission đang được gán cho vai trò.");
    repository.deletePermission(key);
    audit(actor, "PERMISSION_DELETED", "PERMISSION", key, Map.of());
  }

  public Page<AuditLogView> listAuditLogs(
      String action, String targetType, String targetId, int page, int size) {
    requirePermission("PERM_RBAC_MANAGE");
    int safePage = checkedPage(page);
    int safeSize = checkedSize(size);
    String selectedAction = optionalText(action, 80);
    String selectedTargetType = optionalText(targetType, 40);
    String selectedTargetId = optionalText(targetId, 200);
    return new Page<>(
        repository.listAuditLogs(
            selectedAction,
            selectedTargetType,
            selectedTargetId,
            safeSize,
            Math.multiplyExact(safePage, safeSize)),
        safePage,
        safeSize,
        repository.countAuditLogs(selectedAction, selectedTargetType, selectedTargetId));
  }

  private void requirePermission(String permission) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.getAuthorities().stream()
        .anyMatch(authority -> permission.equals(authority.getAuthority())))
      throw forbidden("ACCESS_DENIED", "Bạn không có quyền thực hiện yêu cầu này.");
  }

  private UUID currentActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken token))
      throw forbidden("ACCESS_DENIED", "Bạn không có quyền thực hiện yêu cầu này.");
    Jwt jwt = token.getToken();
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw forbidden("ACCESS_DENIED", "Bạn không có quyền thực hiện yêu cầu này.");
    }
  }

  private static String normalizeEmail(String value) {
    if (value == null) throw invalid("INVALID_EMAIL", "Địa chỉ email không hợp lệ.");
    String email = value.trim().toLowerCase(Locale.ROOT);
    if (email.length() > 254
        || email.startsWith(".")
        || email.endsWith(".")
        || email.contains("..")
        || email.codePoints().anyMatch(Character::isISOControl)
        || !EMAIL.matcher(email).matches())
      throw invalid("INVALID_EMAIL", "Địa chỉ email không hợp lệ.");
    return email;
  }

  private static String normalizeDisplayName(String value) {
    if (value == null) throw invalid("INVALID_ACCOUNT", "Tên hiển thị không hợp lệ.");
    String displayName = value.trim();
    if (displayName.isEmpty()
        || displayName.length() > 100
        || displayName.codePoints().anyMatch(Character::isISOControl))
      throw invalid("INVALID_ACCOUNT", "Tên hiển thị không hợp lệ.");
    return displayName;
  }

  private static String validatePassword(String value) {
    if (value == null) throw invalid("INVALID_PASSWORD", "Mật khẩu phải có từ 8 đến 72 byte UTF-8.");
    int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
    if (byteLength < 8 || byteLength > 72)
      throw invalid("INVALID_PASSWORD", "Mật khẩu phải có từ 8 đến 72 byte UTF-8.");
    return value;
  }

  private boolean hasRole(UUID userId, String role) {
    return repository.findUserRoles(userId).contains(role);
  }

  private void audit(
      UUID actor, String action, String targetType, String targetId, Map<String, Object> details) {
    try {
      repository.insertAuditLog(
          actor, action, targetType, targetId, objectMapper.writeValueAsString(details));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize account audit details.", e);
    }
  }

  private static List<String> normalizeKeys(
      List<String> values, Pattern pattern, int limit, String field) {
    if (values.size() > limit)
      throw invalid("INVALID_RBAC_REQUEST", "Số lượng " + field + " vượt giới hạn.");
    ArrayList<String> result = new ArrayList<>();
    for (String value : values) {
      String key = requiredKey(value, pattern, field);
      if (result.contains(key))
        throw invalid("INVALID_RBAC_REQUEST", "Danh sách " + field + " bị trùng.");
      result.add(key);
    }
    result.sort(String::compareTo);
    return List.copyOf(result);
  }

  private static String requiredKey(String value, Pattern pattern, String field) {
    if (value == null) throw invalid("INVALID_RBAC_REQUEST", field + " không hợp lệ.");
    String key = value.trim().toUpperCase(Locale.ROOT);
    if (!pattern.matcher(key).matches())
      throw invalid("INVALID_RBAC_REQUEST", field + " không hợp lệ.");
    return key;
  }

  private static String requiredText(String value, int maxLength, String label) {
    String result = text(value, maxLength, label);
    if (result.isBlank()) throw invalid("INVALID_RBAC_REQUEST", label + " không được để trống.");
    return result;
  }

  private static String text(String value, int maxLength, String label) {
    if (value == null) return "";
    String result = value.trim();
    if (result.length() > maxLength || result.codePoints().anyMatch(Character::isISOControl))
      throw invalid("INVALID_RBAC_REQUEST", label + " không hợp lệ.");
    return result;
  }

  private static String optionalText(String value, int maxLength) {
    if (value == null || value.isBlank()) return null;
    return text(value, maxLength, "Bộ lọc");
  }

  private static String optionalStatus(String value) {
    if (value == null || value.isBlank()) return null;
    String status = value.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("ACTIVE", "LOCKED").contains(status))
      throw invalid("INVALID_USER_STATUS", "Trạng thái chỉ có thể là ACTIVE hoặc LOCKED.");
    return status;
  }

  private static String optionalRole(String value) {
    return value == null || value.isBlank() ? null : requiredKey(value, ROLE_KEY, "vai trò");
  }

  private static int checkedPage(int page) {
    if (page < 0 || page > 1_000_000)
      throw invalid("INVALID_PAGE", "Số trang không hợp lệ.");
    return page;
  }

  private static int checkedSize(int size) {
    if (size < 1 || size > MAX_PAGE_SIZE)
      throw invalid("INVALID_PAGE_SIZE", "Kích thước trang phải từ 1 đến 100.");
    return size;
  }

  private static boolean isBuiltInPermission(String key) {
    return Set.of(
            "KNOWLEDGE_SEARCH",
            "ACCOUNT_REGISTER",
            "ACCOUNT_LOGIN",
            "ACCOUNT_SELF_MANAGE",
            "CONTENT_MANAGE",
            "CONTENT_REVIEW",
            "CONTENT_EXPERT_ROLE_MANAGE",
            "SAFETY_RULE_MANAGE",
            "USER_MANAGE",
            "ADMIN_ROLE_MANAGE",
            "SYSTEM_STATS_VIEW",
            "RBAC_MANAGE")
        .contains(key);
  }

  private static ApiException invalid(String code, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, message);
  }

  private static ApiException notFound(String code, String message) {
    return new ApiException(HttpStatus.NOT_FOUND, code, message);
  }

  private static ApiException forbidden(String code, String message) {
    return new ApiException(HttpStatus.FORBIDDEN, code, message);
  }

  private static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }
}
