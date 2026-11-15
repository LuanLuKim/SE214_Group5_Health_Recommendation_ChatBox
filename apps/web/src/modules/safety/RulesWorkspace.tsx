import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { careLevelSchema, reviewStatusSchema, type SafetyRule } from '@health/contracts';
import { ApiError, defaultRulesClient, type RulesClient } from './client';
import { careLevelLabels, reviewStatusLabels } from './labels';
import {
  emptyRuleForm,
  errorView,
  filterRules,
  formFromRule,
  ruleActions,
  validateRuleForm,
  type ErrorView,
  type FieldErrors,
  type RuleForm,
  type StatusFilter,
} from './rules';

type Screen = { kind: 'list' } | { kind: 'detail'; id: string } | { kind: 'edit'; id: string | null };

function toErrorView(error: unknown): ErrorView {
  return error instanceof ApiError ? errorView(error.code, error.message) : errorView('UNKNOWN');
}

const dateFormat = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short', timeStyle: 'short' });
const formatDate = (iso: string) => {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : dateFormat.format(date);
};

/** UC-16: list, write, view and submit warning rules. Approval belongs to UC-17 (M2 queue). */
export function RulesWorkspace({ client: injected }: { client?: RulesClient }) {
  const client = useMemo(() => injected ?? defaultRulesClient(), [injected]);
  const [screen, setScreen] = useState<Screen>({ kind: 'list' });
  return (
    <div className="rules-workspace">
      {client.mode === 'mock' && (
        <p className="rules-banner" role="note">
          <strong>Chế độ giả lập (dev).</strong> Dữ liệu là quy tắc [TEST] trong trình duyệt, không lưu vào hệ
          thống. API thật đang chờ đăng nhập (M5). Tắt bằng <code>VITE_SAFETY_RULES_MOCK=false</code>.
        </p>
      )}
      {screen.kind === 'list' && (
        <RuleList
          client={client}
          onOpen={(id) => setScreen({ kind: 'detail', id })}
          onCreate={() => setScreen({ kind: 'edit', id: null })}
        />
      )}
      {screen.kind === 'detail' && (
        <RuleDetail
          client={client}
          id={screen.id}
          onBack={() => setScreen({ kind: 'list' })}
          onEdit={() => setScreen({ kind: 'edit', id: screen.id })}
        />
      )}
      {screen.kind === 'edit' && (
        <RuleEditor
          client={client}
          id={screen.id}
          onCancel={() => setScreen(screen.id ? { kind: 'detail', id: screen.id } : { kind: 'list' })}
          onSaved={(rule) => setScreen({ kind: 'detail', id: rule.id })}
        />
      )}
    </div>
  );
}

function CareLevelBadge({ level }: { level: SafetyRule['careLevel'] }) {
  return <span className={`care-badge care-${level.toLowerCase().replaceAll('_', '-')}`}>{careLevelLabels[level].label}</span>;
}

function StatusBadge({ status }: { status: SafetyRule['reviewStatus'] }) {
  return <span className={`review-badge review-${status.toLowerCase().replaceAll('_', '-')}`}>{reviewStatusLabels[status]}</span>;
}

function Notice({ view, onRetry }: { view: ErrorView; onRetry?: () => void }) {
  return (
    <div className="empty-state" role="alert">
      <div className="empty-symbol" aria-hidden="true">
        !
      </div>
      <h3>Chưa thể tải quy tắc</h3>
      <p>{view.message}</p>
      {onRetry && (
        <button className="secondary-button rules-retry" type="button" onClick={onRetry}>
          Thử lại
        </button>
      )}
    </div>
  );
}

function RuleList({
  client,
  onOpen,
  onCreate,
}: {
  client: RulesClient;
  onOpen: (id: string) => void;
  onCreate: () => void;
}) {
  const [rules, setRules] = useState<SafetyRule[] | null>(null);
  const [error, setError] = useState<ErrorView | null>(null);
  const [filter, setFilter] = useState<StatusFilter>('ALL');
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setRules(null);
    setError(null);
    client
      .list(controller.signal)
      .then(setRules)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(toErrorView(e));
      });
    return () => controller.abort();
  }, [client, refresh]);
  const visible = rules ? filterRules(rules, filter) : [];
  const count = (status: StatusFilter) => (rules ? filterRules(rules, status).length : 0);
  return (
    <div className="search-card">
      <div className="rules-header">
        <div>
          <h2>Quy tắc dấu hiệu cảnh báo</h2>
          <p>Quy tắc chỉ được Safety Gate dùng sau khi được duyệt và bật.</p>
        </div>
        <button className="primary-button" type="button" onClick={onCreate} disabled={error !== null}>
          + Soạn quy tắc
        </button>
      </div>
      <div className="segmented" aria-label="Lọc theo trạng thái">
        {(['ALL', ...reviewStatusSchema.options] as StatusFilter[]).map((status) => (
          <button key={status} type="button" aria-pressed={filter === status} onClick={() => setFilter(status)}>
            {status === 'ALL' ? 'Tất cả' : reviewStatusLabels[status]} <span className="rules-count">{count(status)}</span>
          </button>
        ))}
      </div>
      {error ? (
        <Notice view={error} onRetry={() => setRefresh((n) => n + 1)} />
      ) : rules === null ? (
        <div className="empty-state" aria-live="polite">
          <h3>Đang tải quy tắc…</h3>
        </div>
      ) : visible.length === 0 ? (
        <div className="empty-state" aria-live="polite">
          <div className="empty-symbol" aria-hidden="true">
            ○
          </div>
          <h3>{filter === 'ALL' ? 'Chưa có quy tắc nào' : `Không có quy tắc ở trạng thái "${reviewStatusLabels[filter]}"`}</h3>
          <p>Bấm "Soạn quy tắc" để tạo bản nháp đầu tiên kèm nguồn tham khảo.</p>
        </div>
      ) : (
        <div className="rules-table-wrap">
          <table className="rules-table">
            <thead>
              <tr>
                <th scope="col">Tên quy tắc</th>
                <th scope="col">Mức cảnh báo</th>
                <th scope="col">Trạng thái</th>
                <th scope="col">Cập nhật</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((rule) => (
                <tr key={rule.id}>
                  <td>
                    <button className="rules-link" type="button" onClick={() => onOpen(rule.id)}>
                      {rule.name}
                    </button>
                    {rule.isSynthetic && <span className="status rules-synthetic">Giả lập</span>}
                  </td>
                  <td>
                    <CareLevelBadge level={rule.careLevel} />
                  </td>
                  <td>
                    <StatusBadge status={rule.reviewStatus} />
                  </td>
                  <td className="rules-date">{formatDate(rule.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function useRule(client: RulesClient, id: string | null) {
  const [rule, setRule] = useState<SafetyRule | null>(null);
  const [error, setError] = useState<ErrorView | null>(null);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    if (id === null) return;
    const controller = new AbortController();
    setError(null);
    client
      .get(id, controller.signal)
      .then(setRule)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(toErrorView(e));
      });
    return () => controller.abort();
  }, [client, id, refresh]);
  return { rule, setRule, error, reload: () => setRefresh((n) => n + 1) };
}

function Terms({ items, empty }: { items: string[]; empty: string }) {
  if (items.length === 0) return <span className="rules-muted">{empty}</span>;
  return (
    <ul className="rules-terms">
      {items.map((item) => (
        <li key={item}>
          <code>{item}</code>
        </li>
      ))}
    </ul>
  );
}

function RuleDetail({
  client,
  id,
  onBack,
  onEdit,
}: {
  client: RulesClient;
  id: string;
  onBack: () => void;
  onEdit: () => void;
}) {
  const { rule, setRule, error, reload } = useRule(client, id);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
  const back = (
    <button className="secondary-button" type="button" onClick={onBack}>
      ← Danh sách
    </button>
  );
  if (error) return (
    <div className="search-card">
      {back}
      <Notice view={error} onRetry={reload} />
    </div>
  );
  if (!rule) return (
    <div className="search-card">
      {back}
      <div className="empty-state" aria-live="polite">
        <h3>Đang tải quy tắc…</h3>
      </div>
    </div>
  );
  const actions = ruleActions(rule);
  const submit = async () => {
    setBusy(true);
    setMessage(null);
    try {
      setRule(await client.submit(rule.id));
      setMessage({ tone: 'ok', text: 'Đã gửi duyệt. Quy tắc chờ chuyên gia duyệt và chưa được Safety Gate sử dụng.' });
    } catch (e) {
      const view = toErrorView(e);
      setMessage({ tone: 'error', text: view.message });
      if (view.reload) reload();
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="search-card">
      <div className="rules-header">
        {back}
        <div className="rules-actions">
          {actions.canEdit && (
            <button className="secondary-button" type="button" onClick={onEdit} disabled={busy}>
              Sửa
            </button>
          )}
          {actions.canEdit && (
            <button
              className="primary-button"
              type="button"
              onClick={() => void submit()}
              disabled={busy || !actions.canSubmit}
              aria-describedby={actions.submitBlockedReason ? 'submit-blocked' : undefined}
            >
              {busy ? 'Đang gửi…' : 'Gửi duyệt'}
            </button>
          )}
        </div>
      </div>
      <h2 className="rules-title">
        {rule.name} {rule.isSynthetic && <span className="status rules-synthetic">Giả lập</span>}
      </h2>
      <div className="rules-badges">
        <StatusBadge status={rule.reviewStatus} />
        <CareLevelBadge level={rule.careLevel} />
      </div>
      {message && (
        <p className={`rules-message rules-message-${message.tone}`} role={message.tone === 'error' ? 'alert' : 'status'}>
          {message.text}
        </p>
      )}
      {actions.submitBlockedReason && (
        <p id="submit-blocked" className="rules-message rules-message-error">
          {actions.submitBlockedReason}
        </p>
      )}
      {!actions.canEdit && (
        <p className="rules-message rules-message-locked">
          {rule.reviewStatus === 'PENDING_REVIEW'
            ? 'Quy tắc đang chờ duyệt nên đã khóa. Chuyên gia duyệt có thể duyệt hoặc trả lại để sửa.'
            : rule.reviewStatus === 'APPROVED'
              ? 'Quy tắc đã duyệt nên đã khóa, không sửa trực tiếp được.'
              : 'Quy tắc đã ngừng dùng và chỉ còn để tra cứu.'}
        </p>
      )}
      <dl className="rules-fields">
        <Field label="Mức cảnh báo">
          <strong>{careLevelLabels[rule.careLevel].label}</strong>
          <span className="rules-muted"> · {careLevelLabels[rule.careLevel].hint}</span>
        </Field>
        <Field label="Điều kiện bắt buộc (tất cả)">
          <Terms items={rule.conditions.all} empty="Chưa có" />
        </Field>
        <Field label="Điều kiện bổ sung (ít nhất một)">
          <Terms items={rule.conditions.any} empty="Không có" />
        </Field>
        <Field label="Thông tin cần hỏi thêm">
          <Terms items={rule.requiredFields} empty="Không có" />
        </Field>
        <Field label="Chuyên khoa gợi ý">
          <Terms items={rule.specialtyIds} empty="Không có" />
        </Field>
        <Field label="Nguồn">
          {rule.sourceName ? <span>{rule.sourceName}</span> : <span className="rules-muted">Chưa có tên nguồn</span>}
          {rule.sourceUrl && (
            <>
              {' · '}
              <a className="rules-source" href={rule.sourceUrl} target="_blank" rel="noopener noreferrer">
                {rule.sourceUrl}
              </a>
            </>
          )}
        </Field>
        <Field label="Cập nhật">
          {formatDate(rule.updatedAt)} <span className="rules-muted">(tạo {formatDate(rule.createdAt)})</span>
        </Field>
      </dl>
    </div>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="rules-field">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function RuleEditor({
  client,
  id,
  onCancel,
  onSaved,
}: {
  client: RulesClient;
  id: string | null;
  onCancel: () => void;
  onSaved: (rule: SafetyRule) => void;
}) {
  const { rule, error, reload } = useRule(client, id);
  const [form, setForm] = useState<RuleForm>(emptyRuleForm);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (rule) setForm(formFromRule(rule));
  }, [rule]);
  if (id !== null && error) return (
    <div className="search-card">
      <Notice view={error} onRetry={reload} />
    </div>
  );
  if (id !== null && !rule) return (
    <div className="search-card">
      <div className="empty-state" aria-live="polite">
        <h3>Đang tải quy tắc…</h3>
      </div>
    </div>
  );
  if (rule && !ruleActions(rule).canEdit) return (
    <div className="search-card">
      <p className="rules-message rules-message-locked">Quy tắc này đã khóa, không thể sửa.</p>
      <button className="secondary-button" type="button" onClick={onCancel}>
        ← Quay lại
      </button>
    </div>
  );
  const set = (key: keyof RuleForm) => (value: string) => setForm((current) => ({ ...current, [key]: value }));
  const save = async () => {
    setFormError(null);
    const result = validateRuleForm(form);
    if (!result.ok) {
      setErrors(result.errors);
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      onSaved(id === null ? await client.create(result.input) : await client.update(id, result.input));
    } catch (e) {
      const view = toErrorView(e);
      setFormError(view.message);
      if (view.reload) reload();
    } finally {
      setBusy(false);
    }
  };
  return (
    <form
      className="search-card rules-form"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void save();
      }}
    >
      <h2>{id === null ? 'Soạn quy tắc mới' : 'Sửa quy tắc'}</h2>
      <p className="rules-muted">
        Lưu nháp không kích hoạt quy tắc. Mỗi dòng trong ô danh sách là một mục. Quy tắc bị trả lại sẽ quay về
        trạng thái nháp sau khi sửa.
      </p>
      {formError && (
        <p className="rules-message rules-message-error" role="alert">
          {formError}
        </p>
      )}
      <TextField id="rule-name" label="Tên quy tắc" value={form.name} onChange={set('name')} error={errors.name} maxLength={200} />
      <div className="rules-field-input">
        <label htmlFor="rule-care-level">Mức cảnh báo</label>
        <select
          id="rule-care-level"
          value={form.careLevel}
          onChange={(event) => set('careLevel')(event.target.value)}
          aria-invalid={errors.careLevel ? true : undefined}
          aria-describedby={errors.careLevel ? 'rule-care-level-error' : 'rule-care-level-hint'}
        >
          <option value="">Chọn mức…</option>
          {careLevelSchema.options.map((level) => (
            <option key={level} value={level}>
              {careLevelLabels[level].label}
            </option>
          ))}
        </select>
        {errors.careLevel ? (
          <span id="rule-care-level-error" className="rules-error">
            {errors.careLevel}
          </span>
        ) : (
          <span id="rule-care-level-hint" className="rules-hint">
            {form.careLevel ? careLevelLabels[form.careLevel].hint : 'Chỉ có 5 mức hợp lệ.'}
          </span>
        )}
      </div>
      <div className="rules-grid">
        <ListField id="rule-all" label="Điều kiện bắt buộc (tất cả phải có)" value={form.all} onChange={set('all')} error={errors.all} />
        <ListField id="rule-any" label="Điều kiện bổ sung (ít nhất một, có thể bỏ trống)" value={form.any} onChange={set('any')} error={errors.any} />
        <ListField id="rule-required" label="Thông tin cần hỏi thêm" value={form.requiredFields} onChange={set('requiredFields')} error={errors.requiredFields} />
        <ListField id="rule-specialty" label="Chuyên khoa gợi ý" value={form.specialtyIds} onChange={set('specialtyIds')} error={errors.specialtyIds} />
      </div>
      <div className="rules-grid">
        <TextField id="rule-source-name" label="Tên nguồn" value={form.sourceName} onChange={set('sourceName')} error={errors.sourceName} maxLength={200} />
        <TextField
          id="rule-source-url"
          label="Đường dẫn nguồn"
          type="url"
          value={form.sourceUrl}
          onChange={set('sourceUrl')}
          error={errors.sourceUrl}
          maxLength={2000}
          placeholder="https://"
        />
      </div>
      <div className="rules-actions">
        <button className="secondary-button" type="button" onClick={onCancel} disabled={busy}>
          Hủy
        </button>
        <button className="primary-button" type="submit" disabled={busy}>
          {busy ? 'Đang lưu…' : 'Lưu nháp'}
        </button>
      </div>
    </form>
  );
}

function TextField(props: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  error: string | undefined;
  maxLength: number;
  type?: string;
  placeholder?: string;
}) {
  return (
    <div className="rules-field-input">
      <label htmlFor={props.id}>{props.label}</label>
      <input
        id={props.id}
        type={props.type ?? 'text'}
        value={props.value}
        maxLength={props.maxLength}
        placeholder={props.placeholder}
        onChange={(event) => props.onChange(event.target.value)}
        aria-invalid={props.error ? true : undefined}
        aria-describedby={props.error ? `${props.id}-error` : undefined}
      />
      {props.error && (
        <span id={`${props.id}-error`} className="rules-error">
          {props.error}
        </span>
      )}
    </div>
  );
}

function ListField(props: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  error: string | undefined;
}) {
  return (
    <div className="rules-field-input">
      <label htmlFor={props.id}>{props.label}</label>
      <textarea
        id={props.id}
        rows={4}
        value={props.value}
        onChange={(event) => props.onChange(event.target.value)}
        aria-invalid={props.error ? true : undefined}
        aria-describedby={props.error ? `${props.id}-error` : undefined}
      />
      {props.error && (
        <span id={`${props.id}-error`} className="rules-error">
          {props.error}
        </span>
      )}
    </div>
  );
}
