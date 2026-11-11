package vn.edu.uit.se214.health.system;

import java.util.List;
import java.util.Optional;

/**
 * The six functional modules and the use cases each one owns (19 UC, each exactly once).
 * Keep in sync with {@code modules} in packages/contracts/src/index.ts. UC-17 belongs to M2;
 * M1 builds its input-review part through M2's review interface, it is not a separate UC.
 */
public final class ModuleRegistry {
  private ModuleRegistry() {}

  public static final List<ModuleDescriptor> MODULES =
      List.of(
          new ModuleDescriptor(
              "safety", 1, "Safety Gate & quy tắc cảnh báo",
              "Đánh giá mức độ cần chú ý bằng quy tắc đã duyệt.",
              List.of("UC-07", "UC-16"), "/api/v1/safety", "scaffold"),
          new ModuleDescriptor(
              "knowledge", 2, "Tra cứu & quản lý nội dung",
              "Kiến thức, cơ sở y tế và quy trình rà soát nội dung.",
              List.of("UC-10", "UC-11", "UC-15", "UC-17"), "/api/v1/knowledge", "scaffold"),
          new ModuleDescriptor(
              "health", 3, "Hồ sơ, chỉ số & thống kê",
              "Hồ sơ cá nhân, chỉ số theo ngày và thống kê tổng hợp.",
              List.of("UC-04", "UC-05", "UC-19"), "/api/v1/health", "scaffold"),
          new ModuleDescriptor(
              "chat", 4, "Hội thoại với trợ lý AI",
              "Hội thoại, phản hồi câu trả lời và lịch sử riêng tư.",
              List.of("UC-06", "UC-08", "UC-09"), "/api/v1/chat", "scaffold"),
          new ModuleDescriptor(
              "accounts", 5, "Tài khoản & phân quyền",
              "Đăng ký, đồng ý điều khoản, tài khoản và quyền truy cập.",
              List.of("UC-01", "UC-02", "UC-03", "UC-18"), "/api/v1/accounts", "scaffold"),
          new ModuleDescriptor(
              "lifestyle", 6, "Lộ trình sinh hoạt & nhắc nhở",
              "Lộ trình từ mẫu đã duyệt, thói quen và nhắc nhở.",
              List.of("UC-12", "UC-13", "UC-14"), "/api/v1/lifestyle", "scaffold"));

  public static ModuleDescriptor get(String id) {
    return find(id).orElseThrow();
  }

  public static Optional<ModuleDescriptor> find(String id) {
    return MODULES.stream().filter(m -> m.id().equals(id)).findFirst();
  }
}
