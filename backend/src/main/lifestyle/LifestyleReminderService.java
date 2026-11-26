package lifestyle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import accounts.api.ConsentQuery;
import lifestyle.ReminderList.Delivery;
import lifestyle.ReminderList.Reminder;
import lifestyle.ReminderRepository.Fields;

/**
 * UC-14 Thiết lập nhắc nhở: create, change, turn on/off and delete one's own reminders. Sending is
 * {@link ReminderDispatcher}'s job; this class only saves what it will read.
 *
 * <p>A1: time, content, days, zone and channel are checked before anything is saved. A2: a
 * reminder can only be on with a channel available to the user. Someone else's reminder answers
 * 404, like one that does not exist.
 */
public class LifestyleReminderService {
  static final Set<String> FIELDS =
      Set.of("content", "activityId", "localTime", "daysOfWeek", "timezone", "channel", "enabled");
  static final int MAX_CONTENT = 200;
  private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
  private static final List<Integer> EVERY_DAY = List.of(1, 2, 3, 4, 5, 6, 7);

  private final ReminderRepository reminders;
  private final LifestyleRepository plans;
  private final ObjectMapper json;
  private final Clock clock;
  private final ConsentQuery consents;
  private final Map<ReminderChannel.Type, ReminderChannel> channels;
  private final int maxPerUser;

  /** {@code clock}'s zone is the default zone of a new reminder (app.lifestyle.zone). */
  public LifestyleReminderService(
      ReminderRepository reminders,
      LifestyleRepository plans,
      ObjectMapper json,
      Clock clock,
      ConsentQuery consents,
      List<ReminderChannel> channels,
      int maxPerUser) {
    this.reminders = reminders;
    this.plans = plans;
    this.json = json;
    this.clock = clock;
    this.consents = consents;
    this.channels = channels.stream().collect(Collectors.toMap(ReminderChannel::type, Function.identity()));
    this.maxPerUser = maxPerUser;
  }

  /**
   * No consent check: after withdrawing consent the owner must still find a reminder to turn it off
   * or delete it. The worker sends nothing to a user without consent.
   */
  public ReminderList list(UUID userId) {
    Instant now = clock.instant();
    List<Reminder> rows = reminders.list(userId).stream().map(r -> view(r, now)).toList();
    List<ReminderChannel.Type> available = new ArrayList<>();
    for (ReminderChannel.Type type : ReminderChannel.Type.values())
      if (available(type, userId)) available.add(type);
    return new ReminderList(rows, available);
  }

  /**
   * Body: {@code localTime} (HH:mm) and {@code content} (or {@code activityId}, whose title becomes
   * the content) are required. Defaults: every day, app.lifestyle.zone, EMAIL, on.
   */
  public Reminder create(UUID userId, JsonNode body) {
    requireConsent(userId);
    JsonNode input = object(body);
    String activityId = activityId(userId, input, null);
    String content =
        input.has("content") ? content(input.get("content")) : activityTitle(userId, activityId);
    if (!input.has("localTime")) throw LifestyleErrors.invalidReminder("Thiếu giờ nhắc (localTime, dạng HH:mm).");
    Fields fields =
        new Fields(
            content,
            activityId,
            localTime(input.get("localTime")),
            input.has("daysOfWeek") ? days(input.get("daysOfWeek")) : EVERY_DAY,
            input.has("timezone") ? zone(input.get("timezone")) : clock.getZone(),
            input.has("channel") ? channel(input.get("channel")) : ReminderChannel.Type.EMAIL,
            !input.has("enabled") || enabled(input.get("enabled")));
    requireChannel(userId, fields);
    if (reminders.count(userId) >= maxPerUser) throw LifestyleErrors.reminderLimit(maxPerUser);
    UUID id = UUID.randomUUID();
    reminders.insert(id, userId, fields, clock.instant());
    return read(userId, id);
  }

  /**
   * Changes only the fields sent; {@code {"enabled": false}} stops every later send. That body
   * alone needs no consent, so a user who withdrew it can still turn a reminder off.
   */
  public Reminder update(UUID userId, String reminderId, JsonNode body) {
    UUID id = id(reminderId);
    JsonNode input = object(body);
    if (!turnsOffOnly(input)) requireConsent(userId);
    ReminderRow current = reminders.find(userId, id).orElseThrow(LifestyleErrors::reminderNotFound);
    Fields fields =
        new Fields(
            input.has("content") ? content(input.get("content")) : current.content(),
            activityId(userId, input, current.activityId()),
            input.has("localTime") ? localTime(input.get("localTime")) : current.localTime(),
            input.has("daysOfWeek") ? days(input.get("daysOfWeek")) : current.daysOfWeek(),
            input.has("timezone") ? zone(input.get("timezone")) : current.zone(),
            input.has("channel") ? channel(input.get("channel")) : current.channel(),
            input.has("enabled") ? enabled(input.get("enabled")) : current.enabled());
    requireChannel(userId, fields);
    requireActivityInPlan(userId, input, fields);
    // A reminder paused by a plan switch is outside the limit; turning it on brings it back in.
    if (fields.enabled() && current.disabledReason() != null && reminders.count(userId) >= maxPerUser)
      throw LifestyleErrors.reminderLimit(maxPerUser);
    if (!reminders.update(userId, id, fields, clock.instant())) throw LifestyleErrors.reminderNotFound();
    return read(userId, id);
  }

  /**
   * A reminder that is on and keeps its activity must still follow an activity of the active plan.
   * A plan switch turns off reminders of activities the new plan lacks; turning one back on needs a
   * new {@code activityId} or {@code null}. A new {@code activityId} is checked by activityId().
   */
  private void requireActivityInPlan(UUID userId, JsonNode input, Fields fields) {
    if (fields.enabled() && fields.activityId() != null && !input.has("activityId"))
      activity(userId, fields.activityId());
  }

  /** Deleting one's own data never needs consent. */
  public void delete(UUID userId, String reminderId) {
    if (!reminders.delete(userId, id(reminderId))) throw LifestyleErrors.reminderNotFound();
  }

  private void requireConsent(UUID userId) {
    if (!consents.healthDataProcessingAccepted(userId)) throw LifestyleErrors.consentRequired();
  }

  private static boolean turnsOffOnly(JsonNode input) {
    JsonNode enabled = input.get("enabled");
    return input.size() == 1 && enabled != null && enabled.isBoolean() && !enabled.booleanValue();
  }

  private Reminder read(UUID userId, UUID id) {
    return view(reminders.find(userId, id).orElseThrow(LifestyleErrors::reminderNotFound), clock.instant());
  }

  /** A2: only a reminder that is on needs its channel; an off one may keep any valid channel. */
  private void requireChannel(UUID userId, Fields fields) {
    if (fields.enabled() && !available(fields.channel(), userId)) throw LifestyleErrors.channelUnavailable();
  }

  private boolean available(ReminderChannel.Type type, UUID userId) {
    ReminderChannel channel = channels.get(type);
    return channel != null && channel.available(userId);
  }

  private static JsonNode object(JsonNode body) {
    if (body == null || !body.isObject()) throw LifestyleErrors.invalidReminder("Nội dung yêu cầu phải là một đối tượng JSON.");
    for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
      String name = names.next();
      if (!FIELDS.contains(name)) throw LifestyleErrors.invalidReminder("Trường không hỗ trợ: " + name + ".");
    }
    return body;
  }

  /** Trimmed, 1–200 characters, no control characters (the text goes into an email as is). */
  private static String content(JsonNode value) {
    String text = value != null && value.isTextual() ? value.asText().strip() : "";
    int length = text.codePointCount(0, text.length());
    if (length < 1 || length > MAX_CONTENT || text.codePoints().anyMatch(Character::isISOControl))
      throw LifestyleErrors.invalidReminder("Nội dung nhắc phải dài 1–" + MAX_CONTENT + " ký tự, không có ký tự điều khiển.");
    return text;
  }

  /** 24-hour HH:mm only, so "7:00", "07:00:00" and "7 PM" are rejected rather than guessed. */
  private static LocalTime localTime(JsonNode value) {
    if (value == null || !value.isTextual() || !TIME.matcher(value.asText()).matches())
      throw LifestyleErrors.invalidReminder("Giờ nhắc phải theo dạng 24 giờ HH:mm, ví dụ 07:30 hoặc 21:00.");
    return LocalTime.parse(value.asText());
  }

  /** A non-empty set of ISO days (1 = Monday … 7 = Sunday), stored sorted without duplicates. */
  private static List<Integer> days(JsonNode value) {
    Set<Integer> days = new TreeSet<>();
    if (value != null && value.isArray())
      for (JsonNode day : value) {
        if (!day.isInt() || day.asInt() < 1 || day.asInt() > 7) {
          days.clear();
          break;
        }
        days.add(day.asInt());
      }
    if (days.isEmpty())
      throw LifestyleErrors.invalidReminder("Ngày áp dụng phải là danh sách khác rỗng các số 1 (thứ Hai) đến 7 (Chủ nhật).");
    return List.copyOf(days);
  }

  private static ZoneId zone(JsonNode value) {
    try {
      if (value != null && value.isTextual()) return ZoneId.of(value.asText().strip());
    } catch (DateTimeException e) {
      // falls through to the error below
    }
    throw LifestyleErrors.invalidReminder("Múi giờ không hợp lệ, ví dụ Asia/Ho_Chi_Minh.");
  }

  private static ReminderChannel.Type channel(JsonNode value) {
    if (value != null && value.isTextual())
      for (ReminderChannel.Type type : ReminderChannel.Type.values())
        if (type.name().equals(value.asText())) return type;
    throw LifestyleErrors.invalidReminder("Kênh nhắc phải là EMAIL hoặc WEB_PUSH.");
  }

  private static boolean enabled(JsonNode value) {
    if (value == null || !value.isBoolean()) throw LifestyleErrors.invalidReminder("enabled phải là true hoặc false.");
    return value.asBoolean();
  }

  /**
   * {@code activityId} links the reminder to an activity of the active plan; null unlinks it.
   * Absent: keep {@code current}. The table has no FK to the JSON activities, so it is checked here.
   */
  private String activityId(UUID userId, JsonNode input, String current) {
    if (!input.has("activityId")) return current;
    JsonNode value = input.get("activityId");
    if (value.isNull()) return null;
    if (!value.isTextual() || value.asText().isBlank())
      throw LifestyleErrors.invalidReminder("activityId phải là mã hoạt động hoặc null.");
    String id = value.asText();
    activity(userId, id);
    return id;
  }

  private String activityTitle(UUID userId, String activityId) {
    if (activityId == null) throw LifestyleErrors.invalidReminder("Thiếu nội dung nhắc (content) hoặc hoạt động (activityId).");
    String title = activity(userId, activityId).title();
    return content(json.getNodeFactory().textNode(title == null ? "" : title));
  }

  private PlanActivity activity(UUID userId, String activityId) {
    ActivePlan plan = plans.findActivePlan(userId).orElseThrow(LifestyleErrors::activityNotInPlan);
    return PlanActivity.parse(json, plan.activitiesJson(), plan.templateId()).stream()
        .filter(a -> a.id().equals(activityId))
        .findFirst()
        .orElseThrow(LifestyleErrors::activityNotInPlan);
  }

  private static UUID id(String value) {
    try {
      return UUID.fromString(value == null ? "" : value.strip());
    } catch (IllegalArgumentException e) {
      throw LifestyleErrors.reminderNotFound();
    }
  }

  private static Reminder view(ReminderRow row, Instant now) {
    ReminderRow.LastDelivery last = row.lastDelivery();
    return new Reminder(
        row.id().toString(),
        row.content(),
        row.activityId(),
        row.localTime().toString(),
        row.daysOfWeek(),
        row.zone().getId(),
        row.channel(),
        row.enabled(),
        row.disabledReason(),
        row.enabled() ? ReminderSchedule.next(row, now).toString() : null,
        last == null
            ? null
            : new Delivery(
                last.scheduledFor().toString(),
                last.status(),
                last.sentAt() == null ? null : last.sentAt().toString()));
  }
}
