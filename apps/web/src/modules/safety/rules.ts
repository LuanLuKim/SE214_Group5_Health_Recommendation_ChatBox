import {
  careLevelSchema,
  ruleDraftInputSchema,
  type CareLevel,
  type ReviewStatus,
  type RuleDraftInput,
  type SafetyRule,
} from '@health/contracts';

/** Pure UC-16 screen logic, kept out of components so it can be tested without a browser. */

export interface RuleActions {
  canEdit: boolean;
  canSubmit: boolean;
  /** Why the rule cannot be submitted, when it is editable but not ready. */
  submitBlockedReason: string | null;
}

/** Mirrors the backend: only DRAFT/RETURNED rules are editable, and only sourced ones submit. */
export function ruleActions(rule: Pick<SafetyRule, 'reviewStatus' | 'sourceName' | 'sourceUrl'>): RuleActions {
  const editable = rule.reviewStatus === 'DRAFT' || rule.reviewStatus === 'RETURNED';
  if (!editable) return { canEdit: false, canSubmit: false, submitBlockedReason: null };
  const sourced = rule.sourceName.trim() !== '' && isHttpUrlWithoutCredentials(rule.sourceUrl);
  return {
    canEdit: true,
    canSubmit: sourced,
    submitBlockedReason: sourced ? null : 'Cần có tên nguồn và đường dẫn nguồn hợp lệ trước khi gửi duyệt.',
  };
}

export function isHttpUrlWithoutCredentials(value: string): boolean {
  try {
    const url = new URL(value);
    return (url.protocol === 'https:' || url.protocol === 'http:') && !url.username && !url.password;
  } catch {
    return false;
  }
}

export type StatusFilter = ReviewStatus | 'ALL';

export function filterRules(rules: SafetyRule[], filter: StatusFilter): SafetyRule[] {
  return filter === 'ALL' ? rules : rules.filter((rule) => rule.reviewStatus === filter);
}

/** Form state: list fields are edited as one term per line. */
export interface RuleForm {
  name: string;
  all: string;
  any: string;
  requiredFields: string;
  specialtyIds: string;
  careLevel: CareLevel | '';
  sourceName: string;
  sourceUrl: string;
}

export const emptyRuleForm: RuleForm = {
  name: '',
  all: '',
  any: '',
  requiredFields: '',
  specialtyIds: '',
  careLevel: '',
  sourceName: '',
  sourceUrl: '',
};

export function formFromRule(rule: RuleDraftInput): RuleForm {
  return {
    name: rule.name,
    all: rule.conditions.all.join('\n'),
    any: rule.conditions.any.join('\n'),
    requiredFields: rule.requiredFields.join('\n'),
    specialtyIds: rule.specialtyIds.join('\n'),
    careLevel: rule.careLevel,
    sourceName: rule.sourceName,
    sourceUrl: rule.sourceUrl,
  };
}

export function lines(value: string): string[] {
  return value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line !== '');
}

export type FieldErrors = Partial<Record<keyof RuleForm, string>>;

const fieldMessages: Record<keyof RuleForm, string> = {
  name: 'Nhập tên quy tắc (tối đa 200 ký tự).',
  all: 'Cần ít nhất 1 và tối đa 20 điều kiện bắt buộc, mỗi điều kiện tối đa 120 ký tự.',
  any: 'Tối đa 20 điều kiện, mỗi điều kiện tối đa 120 ký tự.',
  requiredFields: 'Tối đa 20 trường, mỗi trường tối đa 120 ký tự.',
  specialtyIds: 'Tối đa 20 chuyên khoa, mỗi mục tối đa 120 ký tự.',
  careLevel: 'Chọn một trong 5 mức cảnh báo.',
  sourceName: 'Nhập tên nguồn (tối đa 200 ký tự).',
  sourceUrl: 'Nhập đường dẫn http(s) hợp lệ, không chứa tên đăng nhập hay mật khẩu.',
};

/** Validates with the shared contract schema, so the form and the API agree on every rule. */
export function validateRuleForm(
  form: RuleForm,
): { ok: true; input: RuleDraftInput } | { ok: false; errors: FieldErrors } {
  const careLevel = careLevelSchema.safeParse(form.careLevel);
  const candidate = {
    name: form.name,
    conditions: { all: lines(form.all), any: lines(form.any) },
    requiredFields: lines(form.requiredFields),
    careLevel: careLevel.success ? careLevel.data : form.careLevel,
    specialtyIds: lines(form.specialtyIds),
    sourceName: form.sourceName,
    sourceUrl: form.sourceUrl.trim(),
  };
  const parsed = ruleDraftInputSchema.safeParse(candidate);
  if (parsed.success) return { ok: true, input: parsed.data };
  const errors: FieldErrors = {};
  for (const issue of parsed.error.issues) {
    const [first, second] = issue.path;
    const field = (first === 'conditions' ? second : first) as keyof RuleForm | undefined;
    if (field && field in fieldMessages) errors[field] = fieldMessages[field];
  }
  if (Object.keys(errors).length === 0) errors.name = 'Bản nháp quy tắc không hợp lệ.';
  return { ok: false, errors };
}

/** What the screen should do with an API error code. */
export interface ErrorView {
  message: string;
  /** Show the message next to the form instead of replacing the screen. */
  inline: boolean;
  /** The rule changed state on the server; reload it before anything else. */
  reload: boolean;
}

export function errorView(code: string, fallback?: string): ErrorView {
  switch (code) {
    case 'AUTH_NOT_CONFIGURED':
      return {
        message: 'Chức năng soạn quy tắc cần đăng nhập với vai trò chuyên gia nội dung. Đăng nhập chưa được triển khai (M5).',
        inline: false,
        reload: false,
      };
    case 'RULES_UNAVAILABLE':
      return { message: 'Kho quy tắc cảnh báo chưa được cấu hình trên máy chủ.', inline: false, reload: false };
    case 'INVALID_RULE_DRAFT':
      return { message: 'Bản nháp quy tắc không hợp lệ. Kiểm tra lại các trường.', inline: true, reload: false };
    case 'RULE_SOURCE_REQUIRED':
      return { message: 'Quy tắc cần có nguồn hợp lệ trước khi gửi duyệt.', inline: true, reload: false };
    case 'RULE_NOT_EDITABLE':
      return {
        message: 'Quy tắc đang chờ duyệt hoặc đã duyệt nên không thể sửa hay gửi lại. Đã tải lại trạng thái mới nhất.',
        inline: true,
        reload: true,
      };
    case 'RULE_CHANGED':
      return { message: 'Quy tắc vừa được thay đổi ở nơi khác. Đã tải lại, vui lòng thử lại.', inline: true, reload: true };
    case 'RULE_NOT_FOUND':
      return { message: 'Không tìm thấy quy tắc. Có thể quy tắc đã bị xóa.', inline: false, reload: false };
    case 'NETWORK_ERROR':
      return { message: 'Không thể kết nối API. Kiểm tra backend và thử lại.', inline: false, reload: false };
    default:
      return { message: fallback ?? 'Không thể xử lý yêu cầu. Vui lòng thử lại.', inline: false, reload: false };
  }
}
