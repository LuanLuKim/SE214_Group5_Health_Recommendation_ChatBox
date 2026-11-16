package vn.edu.uit.se214.health.accounts.rbac;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.edu.uit.se214.health.accounts.rbac.RbacModels.AuditLogView;
import vn.edu.uit.se214.health.accounts.rbac.RbacModels.PermissionView;
import vn.edu.uit.se214.health.accounts.rbac.RbacModels.RoleView;
import vn.edu.uit.se214.health.accounts.rbac.RbacModels.UserSummary;

@Repository
@Profile("postgres")
public class AccountRbacRepository {
  private final JdbcClient jdbc;

  public AccountRbacRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public boolean isActiveAdmin(UUID userId) {
    return jdbc
        .sql(
            """
            SELECT EXISTS (
              SELECT 1 FROM accounts.users u
              JOIN accounts.user_roles ur ON ur.user_id = u.id
              WHERE u.id = :userId AND u.status = 'ACTIVE' AND ur.role = 'ADMIN'
            )
            """)
        .param("userId", userId)
        .query(Boolean.class)
        .single();
  }

  public List<String> findAuthorities(UUID userId) {
    return jdbc
        .sql(
            """
            SELECT authority FROM (
              SELECT 'ROLE_' || ur.role AS authority
              FROM accounts.users u
              JOIN accounts.user_roles ur ON ur.user_id = u.id
              WHERE u.id = :userId AND u.status = 'ACTIVE'
              UNION
              SELECT 'PERM_' || rp.permission_key AS authority
              FROM accounts.users u
              JOIN accounts.user_roles ur ON ur.user_id = u.id
              JOIN accounts.role_permissions rp ON rp.role_key = ur.role
              WHERE u.id = :userId AND u.status = 'ACTIVE'
            ) granted
            ORDER BY authority
            """)
        .param("userId", userId)
        .query(String.class)
        .list();
  }

  public void lockAdminRole() {
    jdbc.sql("SELECT role_key FROM accounts.roles WHERE role_key = 'ADMIN' FOR UPDATE")
        .query(String.class)
        .single();
  }

  public Optional<UUID> findUserIdByEmail(String email) {
    return jdbc.sql("SELECT id FROM accounts.users WHERE email = :email")
        .param("email", email)
        .query(UUID.class)
        .optional();
  }

  public void insertBootstrapAdmin(
      UUID id, String email, String displayName, String passwordHash, OffsetDateTime createdAt) {
    jdbc.sql(
            """
            INSERT INTO accounts.users (id, email, display_name, password_hash, created_at)
            VALUES (:id, :email, :displayName, :passwordHash, :createdAt)
            """)
        .param("id", id)
        .param("email", email)
        .param("displayName", displayName)
        .param("passwordHash", passwordHash)
        .param("createdAt", createdAt)
        .update();
    grantRole(id, "ADMIN");
  }

  public boolean createUser(
      UUID id,
      String email,
      String displayName,
      String passwordHash,
      OffsetDateTime createdAt) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO accounts.users (id, email, display_name, password_hash, created_at)
                VALUES (:id, :email, :displayName, :passwordHash, :createdAt)
                ON CONFLICT DO NOTHING
                """)
            .param("id", id)
            .param("email", email)
            .param("displayName", displayName)
            .param("passwordHash", passwordHash)
            .param("createdAt", createdAt)
            .update();
    if (inserted != 1) return false;
    grantRole(id, "USER");
    return true;
  }

  public long countAdmins() {
    return jdbc
        .sql("SELECT count(DISTINCT user_id) FROM accounts.user_roles WHERE role = 'ADMIN'")
        .query(Long.class)
        .single();
  }

  public long countActiveAdmins() {
    return jdbc
        .sql(
            """
            SELECT count(DISTINCT u.id) FROM accounts.users u
            JOIN accounts.user_roles ur ON ur.user_id = u.id
            WHERE u.status = 'ACTIVE' AND ur.role = 'ADMIN'
            """)
        .query(Long.class)
        .single();
  }

  public Optional<String> findUserStatus(UUID userId, boolean lock) {
    String sql =
        "SELECT status FROM accounts.users WHERE id = :userId" + (lock ? " FOR UPDATE" : "");
    return jdbc.sql(sql).param("userId", userId).query(String.class).optional();
  }

  public int updateUserStatus(UUID userId, String status) {
    return jdbc
        .sql("UPDATE accounts.users SET status = :status WHERE id = :userId")
        .param("status", status)
        .param("userId", userId)
        .update();
  }

  public int revokeRefreshTokens(UUID userId, OffsetDateTime now) {
    return jdbc
        .sql(
            """
            UPDATE accounts.refresh_tokens SET revoked_at = :now
            WHERE user_id = :userId AND revoked_at IS NULL
            """)
        .param("now", now)
        .param("userId", userId)
        .update();
  }

  public List<String> findUserRoles(UUID userId) {
    return jdbc
        .sql("SELECT role FROM accounts.user_roles WHERE user_id = :userId ORDER BY role")
        .param("userId", userId)
        .query(String.class)
        .list();
  }

  public Optional<UserSummary> findUser(UUID userId) {
    return jdbc
        .sql(
            """
            SELECT u.id, u.email, u.display_name, u.status, u.created_at,
                   COALESCE(string_agg(ur.role, ',' ORDER BY ur.role), '') AS roles
            FROM accounts.users u
            LEFT JOIN accounts.user_roles ur ON ur.user_id = u.id
            WHERE u.id = :userId
            GROUP BY u.id
            """)
        .param("userId", userId)
        .query(
            (rs, row) ->
                new UserSummary(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("display_name"),
                    rs.getString("status"),
                    csv(rs.getString("roles")),
                    rs.getObject("created_at", OffsetDateTime.class)))
        .optional();
  }

  public void replaceUserRoles(UUID userId, List<String> roles) {
    jdbc.sql("DELETE FROM accounts.user_roles WHERE user_id = :userId")
        .param("userId", userId)
        .update();
    for (String role : roles) grantRole(userId, role);
  }

  public boolean allRolesExist(List<String> roles) {
    if (roles.isEmpty()) return true;
    Integer count =
        jdbc.sql("SELECT count(*) FROM accounts.roles WHERE role_key IN (:roles)")
            .param("roles", roles)
            .query(Integer.class)
            .single();
    return count == roles.size();
  }

  public List<UserSummary> searchUsers(
      String query, String status, String role, int limit, int offset) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT u.id, u.email, u.display_name, u.status, u.created_at,
                   COALESCE(string_agg(ur.role, ',' ORDER BY ur.role), '') AS roles
            FROM accounts.users u
            LEFT JOIN accounts.user_roles ur ON ur.user_id = u.id
            WHERE true
            """);
    if (query != null) sql.append(" AND (position(:query IN lower(u.email)) > 0 OR position(:query IN lower(u.display_name)) > 0)");
    if (status != null) sql.append(" AND u.status = :status");
    if (role != null) sql.append(" AND EXISTS (SELECT 1 FROM accounts.user_roles filter_role WHERE filter_role.user_id = u.id AND filter_role.role = :role)");
    sql.append(" GROUP BY u.id ORDER BY u.created_at DESC, u.id LIMIT :limit OFFSET :offset");

    var statement = jdbc.sql(sql.toString()).param("limit", limit).param("offset", offset);
    if (query != null) statement = statement.param("query", query);
    if (status != null) statement = statement.param("status", status);
    if (role != null) statement = statement.param("role", role);
    return statement
        .query(
            (rs, row) ->
                new UserSummary(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("display_name"),
                    rs.getString("status"),
                    rs.getString("roles").isEmpty()
                        ? List.of()
                        : List.of(rs.getString("roles").split(",")),
                    rs.getObject("created_at", OffsetDateTime.class)))
        .list();
  }

  public long countUsers(String query, String status, String role) {
    StringBuilder sql = new StringBuilder("SELECT count(*) FROM accounts.users u WHERE true");
    if (query != null) sql.append(" AND (position(:query IN lower(u.email)) > 0 OR position(:query IN lower(u.display_name)) > 0)");
    if (status != null) sql.append(" AND u.status = :status");
    if (role != null) sql.append(" AND EXISTS (SELECT 1 FROM accounts.user_roles filter_role WHERE filter_role.user_id = u.id AND filter_role.role = :role)");
    var statement = jdbc.sql(sql.toString());
    if (query != null) statement = statement.param("query", query);
    if (status != null) statement = statement.param("status", status);
    if (role != null) statement = statement.param("role", role);
    return statement.query(Long.class).single();
  }

  public List<RoleView> listRoles() {
    return jdbc
        .sql(
            """
            SELECT r.role_key, r.name, r.description, r.built_in, r.created_at, r.updated_at,
                   COALESCE(string_agg(rp.permission_key, ',' ORDER BY rp.permission_key), '') AS permissions
            FROM accounts.roles r
            LEFT JOIN accounts.role_permissions rp ON rp.role_key = r.role_key
            GROUP BY r.role_key ORDER BY r.role_key
            """)
        .query(
            (rs, row) ->
                new RoleView(
                    rs.getString("role_key"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getBoolean("built_in"),
                    csv(rs.getString("permissions")),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class)))
        .list();
  }

  public Optional<RoleView> findRole(String key) {
    return listRoles().stream().filter(role -> role.key().equals(key)).findFirst();
  }

  public boolean roleExists(String key) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM accounts.roles WHERE role_key = :key)")
        .param("key", key)
        .query(Boolean.class)
        .single();
  }

  public void createRole(
      String key, String name, String description, List<String> permissions) {
    jdbc.sql("INSERT INTO accounts.roles (role_key, name, description) VALUES (:key, :name, :description)")
        .param("key", key)
        .param("name", name)
        .param("description", description)
        .update();
    replaceRolePermissions(key, permissions);
  }

  public int updateRole(String key, String name, String description) {
    return jdbc
        .sql(
            """
            UPDATE accounts.roles SET name = :name, description = :description, updated_at = now()
            WHERE role_key = :key
            """)
        .param("key", key)
        .param("name", name)
        .param("description", description)
        .update();
  }

  public int deleteRole(String key) {
    return jdbc.sql("DELETE FROM accounts.roles WHERE role_key = :key")
        .param("key", key)
        .update();
  }

  public int countUsersWithRole(String key) {
    return jdbc.sql("SELECT count(*) FROM accounts.user_roles WHERE role = :key")
        .param("key", key)
        .query(Integer.class)
        .single();
  }

  public List<PermissionView> listPermissions() {
    return jdbc
        .sql(
            """
            SELECT permission_key, name, description, built_in, created_at, updated_at
            FROM accounts.permissions ORDER BY permission_key
            """)
        .query(
            (rs, row) ->
                new PermissionView(
                    rs.getString("permission_key"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getBoolean("built_in"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class)))
        .list();
  }

  public boolean permissionExists(String key) {
    return jdbc
        .sql("SELECT EXISTS (SELECT 1 FROM accounts.permissions WHERE permission_key = :key)")
        .param("key", key)
        .query(Boolean.class)
        .single();
  }

  public boolean allPermissionsExist(List<String> permissions) {
    if (permissions.isEmpty()) return true;
    Integer count =
        jdbc.sql("SELECT count(*) FROM accounts.permissions WHERE permission_key IN (:permissions)")
            .param("permissions", permissions)
            .query(Integer.class)
            .single();
    return count == permissions.size();
  }

  public void replaceRolePermissions(String role, List<String> permissions) {
    jdbc.sql("DELETE FROM accounts.role_permissions WHERE role_key = :role")
        .param("role", role)
        .update();
    for (String permission : permissions) {
      jdbc.sql(
              "INSERT INTO accounts.role_permissions (role_key, permission_key) VALUES (:role, :permission)")
          .param("role", role)
          .param("permission", permission)
          .update();
    }
    jdbc.sql("UPDATE accounts.roles SET updated_at = now() WHERE role_key = :role")
        .param("role", role)
        .update();
  }

  public void createPermission(String key, String name, String description) {
    jdbc.sql(
            "INSERT INTO accounts.permissions (permission_key, name, description) VALUES (:key, :name, :description)")
        .param("key", key)
        .param("name", name)
        .param("description", description)
        .update();
  }

  public int updatePermission(String key, String name, String description) {
    return jdbc
        .sql(
            """
            UPDATE accounts.permissions SET name = :name, description = :description, updated_at = now()
            WHERE permission_key = :key
            """)
        .param("key", key)
        .param("name", name)
        .param("description", description)
        .update();
  }

  public int deletePermission(String key) {
    return jdbc.sql("DELETE FROM accounts.permissions WHERE permission_key = :key")
        .param("key", key)
        .update();
  }

  public int countRolesWithPermission(String key) {
    return jdbc
        .sql("SELECT count(*) FROM accounts.role_permissions WHERE permission_key = :key")
        .param("key", key)
        .query(Integer.class)
        .single();
  }

  public List<AuditLogView> listAuditLogs(
      String action, String targetType, String targetId, int limit, int offset) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT id, actor_user_id, action, target_type, target_id, details::text AS details,
                   created_at
            FROM accounts.admin_audit_logs WHERE true
            """);
    if (action != null) sql.append(" AND action = :action");
    if (targetType != null) sql.append(" AND target_type = :targetType");
    if (targetId != null) sql.append(" AND target_id = :targetId");
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
    var statement = jdbc.sql(sql.toString()).param("limit", limit).param("offset", offset);
    if (action != null) statement = statement.param("action", action);
    if (targetType != null) statement = statement.param("targetType", targetType);
    if (targetId != null) statement = statement.param("targetId", targetId);
    return statement
        .query(
            (rs, row) ->
                new AuditLogView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("actor_user_id", UUID.class),
                    rs.getString("action"),
                    rs.getString("target_type"),
                    rs.getString("target_id"),
                    parseDetails(rs.getString("details")),
                    rs.getObject("created_at", OffsetDateTime.class)))
        .list();
  }

  public long countAuditLogs(String action, String targetType, String targetId) {
    StringBuilder sql = new StringBuilder("SELECT count(*) FROM accounts.admin_audit_logs WHERE true");
    if (action != null) sql.append(" AND action = :action");
    if (targetType != null) sql.append(" AND target_type = :targetType");
    if (targetId != null) sql.append(" AND target_id = :targetId");
    var statement = jdbc.sql(sql.toString());
    if (action != null) statement = statement.param("action", action);
    if (targetType != null) statement = statement.param("targetType", targetType);
    if (targetId != null) statement = statement.param("targetId", targetId);
    return statement.query(Long.class).single();
  }

  public void insertAuditLog(
      UUID actor, String action, String targetType, String targetId, String detailsJson) {
    jdbc.sql(
            """
            INSERT INTO accounts.admin_audit_logs
                (id, actor_user_id, action, target_type, target_id, details)
            VALUES (:id, :actor, :action, :targetType, :targetId, CAST(:details AS jsonb))
            """)
        .param("id", UUID.randomUUID())
        .param("actor", actor)
        .param("action", action)
        .param("targetType", targetType)
        .param("targetId", targetId)
        .param("details", detailsJson)
        .update();
  }

  public void grantRole(UUID userId, String role) {
    jdbc.sql("INSERT INTO accounts.user_roles (user_id, role) VALUES (:userId, :role)")
        .param("userId", userId)
        .param("role", role)
        .update();
  }

  private static List<String> csv(String value) {
    return value == null || value.isEmpty() ? List.of() : List.of(value.split(","));
  }

  private static java.util.Map<String, Object> parseDetails(String value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot read audit log details.", e);
    }
  }
}
