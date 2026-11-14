package vn.edu.uit.se214.health.safety;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.shared.web.ApiResponse;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/** M1 – UC-07, UC-16. Routes require authentication; role and consent checks remain unimplemented. */
@RestController
@RequestMapping("/api/v1/safety")
public class SafetyController {
  @GetMapping
  public ApiResponse.Success<ModuleDescriptor> module() {
    return ApiResponse.of(ModuleRegistry.get("safety"));
  }

  @PostMapping("/evaluate")
  public Object evaluate(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @GetMapping("/rules")
  public Object listRules() {
    throw ApiException.notImplemented();
  }

  @PostMapping("/rules")
  public Object createRule(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @PatchMapping("/rules/{id}")
  public Object updateRule(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }
}
