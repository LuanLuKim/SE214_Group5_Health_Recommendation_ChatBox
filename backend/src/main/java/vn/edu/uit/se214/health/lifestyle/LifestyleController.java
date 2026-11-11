package vn.edu.uit.se214.health.lifestyle;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.shared.web.ApiResponse;
import vn.edu.uit.se214.health.shared.web.RequiresSession;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/** M6 – UC-12, UC-13, UC-14. Reminder sending will run as a @Scheduled job inside this module. */
@RestController
@RequestMapping("/api/v1/lifestyle")
public class LifestyleController {
  @GetMapping
  public ApiResponse.Success<ModuleDescriptor> module() {
    return ApiResponse.of(ModuleRegistry.get("lifestyle"));
  }

  @RequiresSession
  @GetMapping("/templates")
  public Object templates() {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @GetMapping("/plan")
  public Object plan() {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PutMapping("/plan")
  public Object choosePlan(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PutMapping("/completions/{activityId}/{date}")
  public Object complete(@PathVariable String activityId, @PathVariable String date) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @GetMapping("/reminders")
  public Object reminders() {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PostMapping("/reminders")
  public Object createReminder(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PatchMapping("/reminders/{id}")
  public Object updateReminder(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @DeleteMapping("/reminders/{id}")
  public Object deleteReminder(@PathVariable String id) {
    throw ApiException.notImplemented();
  }
}
