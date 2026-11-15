package vn.edu.uit.se214.health.lifestyle;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uit.se214.health.shared.web.ApiResponse;
import vn.edu.uit.se214.health.shared.web.CurrentUser;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/**
 * M6 – UC-12, UC-13, UC-14. Every route except the module descriptor is private
 * (SpringSecurityConfiguration); the user is the verified access token's subject. Reminders are
 * sent by {@link ReminderScheduler}, a @Scheduled job inside this module.
 */
@RestController
@RequestMapping("/api/v1/lifestyle")
public class LifestyleController {
  private final Optional<LifestyleService> lifestyle;
  private final Optional<LifestylePlanService> plans;
  private final Optional<LifestyleReminderService> reminders;

  public LifestyleController(
      Optional<LifestyleService> lifestyle,
      Optional<LifestylePlanService> plans,
      Optional<LifestyleReminderService> reminders) {
    this.lifestyle = lifestyle;
    this.plans = plans;
    this.reminders = reminders;
  }

  @GetMapping
  public ApiResponse.Success<ModuleDescriptor> module() {
    return ApiResponse.of(ModuleRegistry.get("lifestyle"));
  }

  /** UC-12: approved templates the user may apply, optionally for one goal (SLEEP, MAINTAIN, GAIN, LOSE). */
  @GetMapping("/templates")
  public ApiResponse.Success<TemplateCatalog> templates(
      @AuthenticationPrincipal Jwt principal, @RequestParam(required = false) String goal) {
    UUID userId = CurrentUser.id(principal);
    return ApiResponse.of(planService().templates(userId, goal));
  }

  /** UC-12: preview before applying; reading it changes nothing. */
  @GetMapping("/templates/{id}")
  public ApiResponse.Success<TemplatePreview> template(
      @AuthenticationPrincipal Jwt principal, @PathVariable String id) {
    UUID userId = CurrentUser.id(principal);
    return ApiResponse.of(planService().preview(userId, id));
  }

  /** UC-13: today's activities of the active plan, progress and streaks. */
  @GetMapping("/plan")
  public ApiResponse.Success<TodayPlan> plan(@AuthenticationPrincipal Jwt principal) {
    return ApiResponse.of(service().today(CurrentUser.id(principal)));
  }

  /** UC-12: apply or switch to an approved template, body {@code {"templateId": "<uuid>"}}. */
  @PutMapping("/plan")
  public ApiResponse.Success<AppliedPlan> choosePlan(
      @AuthenticationPrincipal Jwt principal, @RequestBody(required = false) JsonNode body) {
    UUID userId = CurrentUser.id(principal);
    return ApiResponse.of(planService().apply(userId, body));
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

  /** UC-14: the user's reminders and the channels they can turn on now. */
  @GetMapping("/reminders")
  public ApiResponse.Success<ReminderList> reminders(@AuthenticationPrincipal Jwt principal) {
    UUID userId = CurrentUser.id(principal);
    return ApiResponse.of(reminderService().list(userId));
  }

  /** UC-14: create a reminder; it is on unless {@code enabled} is false. */
  @PostMapping("/reminders")
  public ResponseEntity<ApiResponse.Success<ReminderList.Reminder>> createReminder(
      @AuthenticationPrincipal Jwt principal, @RequestBody(required = false) JsonNode body) {
    UUID userId = CurrentUser.id(principal);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(reminderService().create(userId, body)));
  }

  /** UC-14: change some fields; {@code {"enabled": false}} turns the reminder off. */
  @PatchMapping("/reminders/{id}")
  public ApiResponse.Success<ReminderList.Reminder> updateReminder(
      @AuthenticationPrincipal Jwt principal, @PathVariable String id, @RequestBody(required = false) JsonNode body) {
    UUID userId = CurrentUser.id(principal);
    return ApiResponse.of(reminderService().update(userId, id, body));
  }

  @DeleteMapping("/reminders/{id}")
  public ApiResponse.Success<Void> deleteReminder(@AuthenticationPrincipal Jwt principal, @PathVariable String id) {
    UUID userId = CurrentUser.id(principal);
    reminderService().delete(userId, id);
    return ApiResponse.of(null);
  }

  private LifestyleReminderService reminderService() {
    return reminders.orElseThrow(LifestyleErrors::unavailable);
  }

  private LifestyleService service() {
    return lifestyle.orElseThrow(LifestyleErrors::unavailable);
  }

  private LifestylePlanService planService() {
    return plans.orElseThrow(LifestyleErrors::unavailable);
  }
}
