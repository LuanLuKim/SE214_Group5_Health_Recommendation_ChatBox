import type { ApiFailure, ApiSuccess, RuleDraftInput, SafetyRule } from '@health/contracts';
import { isHttpUrlWithoutCredentials } from './rules';

/** UC-16 data access. The page only sees this interface, never fetch or the mock directly. */
export interface RulesClient {
  readonly mode: 'api' | 'mock';
  list(signal?: AbortSignal): Promise<SafetyRule[]>;
  get(id: string, signal?: AbortSignal): Promise<SafetyRule>;
  create(input: RuleDraftInput): Promise<SafetyRule>;
  update(id: string, input: RuleDraftInput): Promise<SafetyRule>;
  submit(id: string): Promise<SafetyRule>;
}

/** An error whose code and Vietnamese message came from the API envelope (or NETWORK_ERROR). */
export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

type Fetch = (input: string, init?: RequestInit) => Promise<Response>;

export function createHttpRulesClient(origin = '', fetcher: Fetch = (input, init) => fetch(input, init)): RulesClient {
  const base = `${origin.replace(/\/$/, '')}/api/v1/safety/rules`;
  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    let response: Response;
    try {
      response = await fetcher(`${base}${path}`, {
        ...init,
        signal: init.signal ? AbortSignal.any([init.signal, AbortSignal.timeout(8000)]) : AbortSignal.timeout(8000),
        headers: {
          Accept: 'application/json',
          ...(init.body === undefined ? {} : { 'Content-Type': 'application/json' }),
        },
      });
    } catch (error) {
      if (init.signal?.aborted) throw error;
      throw new ApiError('NETWORK_ERROR', 'Không thể kết nối API. Kiểm tra backend và thử lại.', 0);
    }
    const payload: unknown = await response.json().catch(() => null);
    if (!response.ok) {
      const failure = payload as Partial<ApiFailure> | null;
      const code = typeof failure?.error?.code === 'string' ? failure.error.code : 'HTTP_' + response.status;
      const message =
        typeof failure?.error?.message === 'string' ? failure.error.message : 'Không thể xử lý yêu cầu. Vui lòng thử lại.';
      throw new ApiError(code, message, response.status);
    }
    return (payload as ApiSuccess<T>).data;
  }
  const withSignal = (signal?: AbortSignal): RequestInit => (signal ? { signal } : {});
  return {
    mode: 'api',
    list: (signal) => request<SafetyRule[]>('', withSignal(signal)),
    get: (id, signal) => request<SafetyRule>(`/${encodeURIComponent(id)}`, withSignal(signal)),
    create: (input) => request<SafetyRule>('', { method: 'POST', body: JSON.stringify(input) }),
    update: (id, input) =>
      request<SafetyRule>(`/${encodeURIComponent(id)}`, { method: 'PATCH', body: JSON.stringify(input) }),
    submit: (id) => request<SafetyRule>(`/${encodeURIComponent(id)}/submit`, { method: 'POST' }),
  };
}

/**
 * Development-only, in-memory stand-in for the backend while /safety/rules stays behind the M5
 * session guard. Same rules and error codes as SafetyController; nothing is persisted, and every
 * rule is a [TEST] synthetic fixture with __SYNTHETIC_ONLY__ conditions.
 */
export function createMockRulesClient(seed: SafetyRule[] = mockSeed(), now = () => new Date()): RulesClient {
  const rules = new Map(seed.map((rule) => [rule.id, structuredClone(rule)]));
  let counter = 0;
  const find = (id: string) => {
    const rule = rules.get(id);
    if (!rule) throw new ApiError('RULE_NOT_FOUND', 'Không tìm thấy quy tắc.', 404);
    return rule;
  };
  const editable = (rule: SafetyRule) => rule.reviewStatus === 'DRAFT' || rule.reviewStatus === 'RETURNED';
  const locked = () =>
    new ApiError('RULE_NOT_EDITABLE', 'Quy tắc đang chờ duyệt hoặc đã duyệt, không thể sửa hay gửi lại.', 409);
  const copy = (rule: SafetyRule) => structuredClone(rule);
  return {
    mode: 'mock',
    async list() {
      return [...rules.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(copy);
    },
    async get(id) {
      return copy(find(id));
    },
    async create(input) {
      counter += 1;
      const at = now().toISOString();
      const rule: SafetyRule = {
        ...structuredClone(input),
        id: `mock-${counter}-${Math.random().toString(16).slice(2, 10)}`,
        reviewStatus: 'DRAFT',
        isSynthetic: true,
        createdAt: at,
        updatedAt: at,
      };
      rules.set(rule.id, rule);
      return copy(rule);
    },
    async update(id, input) {
      const rule = find(id);
      if (!editable(rule)) throw locked();
      const updated: SafetyRule = { ...rule, ...structuredClone(input), reviewStatus: 'DRAFT', updatedAt: now().toISOString() };
      rules.set(id, updated);
      return copy(updated);
    },
    async submit(id) {
      const rule = find(id);
      if (!editable(rule)) throw locked();
      if (rule.sourceName.trim() === '' || !isHttpUrlWithoutCredentials(rule.sourceUrl)) {
        throw new ApiError('RULE_SOURCE_REQUIRED', 'Quy tắc cần có nguồn hợp lệ trước khi gửi duyệt.', 400);
      }
      const submitted: SafetyRule = { ...rule, reviewStatus: 'PENDING_REVIEW', updatedAt: now().toISOString() };
      rules.set(id, submitted);
      return copy(submitted);
    },
  };
}

/** One synthetic rule per review state, so every screen state can be demonstrated. */
export function mockSeed(): SafetyRule[] {
  const base = {
    requiredFields: [] as string[],
    specialtyIds: [] as string[],
    sourceName: 'Synthetic fixture',
    isSynthetic: true,
  };
  const rule = (
    n: number,
    name: string,
    reviewStatus: SafetyRule['reviewStatus'],
    careLevel: SafetyRule['careLevel'],
    extra: Partial<SafetyRule> = {},
  ): SafetyRule => ({
    ...base,
    id: `00000000-0000-4000-8000-0000000000${n}`,
    name,
    conditions: { all: [`__SYNTHETIC_ONLY__${reviewStatus.toLowerCase()}`], any: [] },
    careLevel,
    sourceUrl: `https://example.invalid/rules/${reviewStatus.toLowerCase()}`,
    reviewStatus,
    createdAt: `2026-11-0${n - 30}T00:00:00Z`,
    updatedAt: `2026-11-0${n - 30}T00:00:00Z`,
    ...extra,
  });
  return [
    rule(31, '[TEST] Quy tắc nháp có nguồn', 'DRAFT', 'INSUFFICIENT_INFO', {
      requiredFields: ['__SYNTHETIC_ONLY__onset'],
    }),
    rule(32, '[TEST] Quy tắc đang chờ duyệt', 'PENDING_REVIEW', 'SEEK_CARE_SOON', {
      conditions: { all: ['__SYNTHETIC_ONLY__pending'], any: ['__SYNTHETIC_ONLY__pending_a'] },
    }),
    rule(33, '[TEST] Quy tắc bị trả lại', 'RETURNED', 'ROUTINE_CONSULT'),
    rule(34, '[TEST] Quy tắc nháp thiếu nguồn', 'DRAFT', 'GENERAL_SELF_CARE_INFO', { sourceName: '', sourceUrl: '' }),
    rule(35, '[TEST] Quy tắc đã duyệt', 'APPROVED', 'EMERGENCY_NOW'),
    rule(36, '[TEST] Quy tắc ngừng dùng', 'RETIRED', 'EMERGENCY_NOW'),
  ];
}

/**
 * The mock is used only in `vite dev` and only while VITE_SAFETY_RULES_MOCK is not "false".
 * A production build always talks to the real API.
 */
export function defaultRulesClient(): RulesClient {
  const env = import.meta.env as Partial<ImportMetaEnv> | undefined;
  if (env?.DEV === true && env.VITE_SAFETY_RULES_MOCK !== 'false') return createMockRulesClient();
  return createHttpRulesClient(env?.VITE_API_ORIGIN ?? '');
}
