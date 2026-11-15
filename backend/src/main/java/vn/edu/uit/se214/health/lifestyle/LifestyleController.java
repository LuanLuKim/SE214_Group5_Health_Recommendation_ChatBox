package vn.edu.uit.se214.health.lifestyle;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
import vn.edu.uit.se214.health.shared.web.CurrentUser;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/**
 * M6 – UC-12, UC-13, UC-14. Every route except the module descriptor is private
 * (SpringSecurityConfiguration); the user is the verified access token's subject. Reminder sending
 * will run as a @Scheduled job inside this module.
 */
@RestController
@RequestMapping("/api/v1/lifestyle")
public class LifestyleController {
  private final Optional<LifestyleService> lifestyle;

  public LifestyleController(Optional<LifestyleService> lifestyle) {
    this.lifestyle = lifestyle;
  }

  @GetMapping
  public ApiResponse.Success<ModuleDescriptor> module() {
    return ApiResponse.of(ModuleRegistry.get("lifestyle"));
  }

  @GetMapping("/templates")
  public Object templates() {
    throw ApiException.notImplemented();
  }

  /** UC-13: today's activities of the active plan, progress and streaks. */
  @GetMapping("/plan")
  public ApiResponse.Success<TodayPlan> plan(@AuthenticationPrincipal Jwt principal) {
    return ApiResponse.of(service().today(CurrentUser.id(principal)));
  }

  @PutMapping("/plan")
  public Object choosePlan(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  /** UC-13: mark an activity done on a day (yyyy-mm-dd). Sending it again changes nothing. */
  @PutMapping("/completions/{activityId}/{date}")
  public ApiResponse.Success<CompletionResult> complete(
      @AuthenticationPrincipal Jwt principal, @PathVariable String activityId, @PathVariable String date) {
    LifestyleService service = service();
    return ApiResponse.of(service.complete(CurrentUser.id(principal), activityId, date));
  }

  /** UC-13: unmark an activity; only today's marks can be removed. */
  @DeleteMapping("/completions/{activityId}/{date}")
  public ApiResponse.Success<CompletionResult> uncomplete(
      @AuthenticationPrincipal Jwt principal, @PathVariable String activityId, @PathVariable String date) {
    LifestyleService service = service();
    return ApiResponse.of(service.uncomplete(CurrentUser.id(principal), activityId, date));
  }

  @GetMapping("/reminders")
  public Object reminders() {
    throw ApiException.notImplemented();
  }

  @PostMapping("/reminders")
  public Object createReminder(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @PatchMapping("/reminders/{id}")
  public Object updateReminder(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @DeleteMapping("/reminders/{id}")
  public Object deleteReminder(@PathVariable String id) {
    throw ApiException.notImplemented();
  }

  private LifestyleService service() {
    return lifestyle.orElseThrow(LifestyleErrors::unavailable);
  }
}
