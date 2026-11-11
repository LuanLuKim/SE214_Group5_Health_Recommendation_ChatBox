package vn.edu.uit.se214.health.system;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uit.se214.health.shared.web.ApiResponse;

@RestController
public class SystemController {
  @GetMapping("/healthz")
  public ApiResponse.Success<Map<String, String>> health() {
    return ApiResponse.of(
        Map.of("status", "ok", "service", "health-assistant-api", "mode", "scaffold"));
  }

  @GetMapping("/api/v1/modules")
  public ApiResponse.Success<List<ModuleDescriptor>> modules() {
    return ApiResponse.of(ModuleRegistry.MODULES);
  }
}
