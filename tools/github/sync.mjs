#!/usr/bin/env node
// Đồng bộ GitHub (milestone, nhãn, issue, ruleset) theo kế hoạch Jira trong plan.json.
// Mặc định chạy thử (dry-run), không đổi gì. Thêm --apply để thực hiện.
//   node tools/github/sync.mjs                # xem sẽ làm gì
//   node tools/github/sync.mjs --apply        # thực hiện (cần: gh auth login, quyền admin repo)
// Tùy chọn: --repo owner/name  --skip-issues  --skip-rules
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const apply = args.includes('--apply');
const argValue = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const repo = argValue('--repo') ?? 'LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox';
const plan = JSON.parse(readFileSync(join(here, 'plan.json'), 'utf8'));
const ruleset = JSON.parse(readFileSync(join(here, 'ruleset-main.json'), 'utf8'));

const log = (m) => console.log((apply ? '' : '[dry-run] ') + m);
function gh(ghArgs, input) {
  return execFileSync('gh', ghArgs, { encoding: 'utf8', input, stdio: ['pipe', 'pipe', 'pipe'] });
}
const ghJson = (ghArgs) => JSON.parse(gh(ghArgs) || 'null');

function loginOf(key) {
  return key ? (plan.people[key]?.login ?? null) : null;
}
function nameOf(key) {
  return key ? (plan.people[key]?.name ?? key) : null;
}
function formatBody(issue, extraNote) {
  const lines = [];
  lines.push(
    `**Mã Jira:** ${issue.jira}${issue.old?.length ? ` · thay thế issue cũ ${issue.old.map((n) => `#${n}`).join(', ')}` : ''}`,
    '',
  );
  lines.push('## Phạm vi', ...issue.scope.map((s) => `- ${s}`), '');
  if (issue.tests?.length) {
    lines.push('## Điều kiện kiểm thử', ...issue.tests.map((t) => `- [ ] ${t}`));
    lines.push(
      '- [ ] Kiểm thử tự động nằm trong `apps/*/tests` (hoặc cạnh mã công cụ) và `npm run check` đạt',
      '- [ ] PR chỉ merge khi CI (`check`, `data-tools`) xanh và có 1 reviewer duyệt',
      '',
    );
  }
  if (issue.refs?.length) lines.push('## Tham chiếu', ...issue.refs.map((r) => `- ${r}`), '');
  if (extraNote) lines.push(extraNote, '');
  return lines.join('\n').trim() + '\n';
}

// 1. Sao lưu và dọn issue đang mở (issue đã đóng được giữ nguyên)
let oldIssues = [];
if (!args.includes('--skip-issues')) {
  if (apply) {
    oldIssues = ghJson([
      'issue',
      'list',
      '--repo',
      repo,
      '--state',
      'open',
      '--limit',
      '500',
      '--json',
      'number,title,body,assignees,labels,milestone,url,createdAt',
    ]);
    mkdirSync(join(here, 'backup'), { recursive: true });
    const file = join(here, 'backup', `open-issues-${new Date().toISOString().slice(0, 10)}.json`);
    writeFileSync(file, JSON.stringify(oldIssues, null, 2));
    log(`Đã sao lưu ${oldIssues.length} issue đang mở vào ${file}`);
  } else {
    log('Sẽ sao lưu mọi issue đang mở vào tools/github/backup/ trước khi xóa');
  }
}

// 2. Nhãn
for (const l of plan.labels) {
  log(`Nhãn ${l.name}`);
  if (apply)
    gh([
      'label',
      'create',
      l.name,
      '--repo',
      repo,
      '--color',
      l.color,
      '--description',
      l.description,
      '--force',
    ]);
}

// 3. Milestone
const milestoneNumber = new Map();
const existing = apply ? ghJson(['api', `repos/${repo}/milestones?state=all&per_page=100`]) : [];
for (const m of plan.milestones) {
  log(`Milestone "${m.title}" (hạn ${m.due})`);
  if (!apply) continue;
  const found = existing.find((e) => e.title === m.title);
  if (found) {
    milestoneNumber.set(m.id, found.number);
  } else {
    const made = ghJson([
      'api',
      `repos/${repo}/milestones`,
      '-f',
      `title=${m.title}`,
      '-f',
      `due_on=${m.due}T16:59:00Z`,
      '-f',
      'state=open',
    ]);
    milestoneNumber.set(m.id, made.number);
  }
}

// 4. Xóa issue đang mở (không xóa được thì đóng "not planned" kèm ghi chú)
if (!args.includes('--skip-issues')) {
  const targets = apply ? oldIssues : [];
  if (!apply)
    log('Sẽ xóa các issue đang mở (nếu gh không đủ quyền xóa: đóng với lý do not planned)');
  for (const o of targets) {
    log(`Xóa issue #${o.number}: ${o.title}`);
    try {
      gh(['issue', 'delete', String(o.number), '--repo', repo, '--yes']);
    } catch {
      gh([
        'issue',
        'close',
        String(o.number),
        '--repo',
        repo,
        '--reason',
        'not planned',
        '--comment',
        'Thay thế bằng bộ issue chuẩn theo kế hoạch Jira.',
      ]);
    }
  }

  // 5. Tạo lại issue
  const created = [];
  for (const issue of plan.issues) {
    const logins = new Set();
    const planned = loginOf(issue.assignee);
    if (planned) logins.add(planned);
    for (const n of issue.old ?? []) {
      for (const a of oldIssues.find((o) => o.number === n)?.assignees ?? []) logins.add(a.login);
    }
    const missing =
      issue.assignee && !planned
        ? `> Người làm: ${nameOf(issue.assignee)} (chưa điền GitHub login trong plan.json nên chưa gán được).`
        : null;
    const body = formatBody(issue, missing);
    log(
      `Tạo "${issue.title}" [${issue.state}] milestone ${issue.milestone} gán ${[...logins].join(', ') || '-'}`,
    );
    if (!apply) continue;
    const createArgs = [
      'issue',
      'create',
      '--repo',
      repo,
      '--title',
      issue.title,
      '--body-file',
      '-',
      '--milestone',
      plan.milestones.find((m) => m.id === issue.milestone).title,
    ];
    if (issue.labels.length) createArgs.push('--label', issue.labels.join(','));
    for (const l of logins) createArgs.push('--assignee', l);
    const url = gh(createArgs, body).trim().split('\n').pop();
    const number = Number(url.split('/').pop());
    created.push({ issue, number, url });
    if (issue.state === 'closed') {
      const who = nameOf(issue.doneBy);
      const bits = [];
      if (who) bits.push(`Thực hiện bởi ${who}`);
      if (issue.branch) bits.push(`nhánh \`${issue.branch}\``);
      if (issue.pr) bits.push(`PR #${issue.pr}`);
      if (issue.commit) bits.push(`commit ${issue.commit}`);
      const comment = bits.length ? `Hoàn thành: ${bits.join(' · ')}.` : 'Hoàn thành (xem Jira).';
      gh([
        'issue',
        'close',
        String(number),
        '--repo',
        repo,
        '--reason',
        'completed',
        '--comment',
        comment,
      ]);
    }
  }
  if (apply)
    writeFileSync(
      join(here, 'backup', 'created-issues.json'),
      JSON.stringify(
        created.map((c) => ({ jira: c.issue.jira, number: c.number, url: c.url })),
        null,
        2,
      ),
    );

  // Đóng milestone của sprint đã xong
  for (const m of plan.milestones.filter((x) => x.closed)) {
    log(`Đóng milestone "${m.title}"`);
    if (apply)
      gh([
        'api',
        '-X',
        'PATCH',
        `repos/${repo}/milestones/${milestoneNumber.get(m.id)}`,
        '-f',
        'state=closed',
      ]);
  }
}

// 6. Ruleset: chỉ cần 1 reviewer + CI xanh
if (!args.includes('--skip-rules')) {
  log('Ruleset nhánh main: 1 reviewer, check + data-tools bắt buộc');
  if (apply) {
    const sets = ghJson(['api', `repos/${repo}/rulesets`]) ?? [];
    for (const s of sets.filter((x) => x.target === 'branch')) {
      const full = ghJson(['api', `repos/${repo}/rulesets/${s.id}`]);
      const touchesMain = JSON.stringify(full.conditions ?? {}).match(
        /DEFAULT_BRANCH|refs\/heads\/main/,
      );
      if (!touchesMain) continue;
      for (const r of full.rules) {
        if (r.type === 'pull_request') r.parameters.required_approving_review_count = 1;
      }
      gh(
        ['api', '-X', 'PUT', `repos/${repo}/rulesets/${s.id}`, '--input', '-'],
        JSON.stringify(full),
      );
      log(`Đã đặt required_approving_review_count = 1 cho ruleset "${full.name}"`);
    }
    if (!sets.some((x) => x.name === ruleset.name)) {
      const hasMainRule = sets.some((x) => x.target === 'branch');
      if (!hasMainRule)
        gh(
          ['api', '-X', 'POST', `repos/${repo}/rulesets`, '--input', '-'],
          JSON.stringify(ruleset),
        );
      else
        log(
          "Đã có ruleset cho main; bổ sung status check 'check' và 'data-tools' trong Settings nếu chưa có (xem README).",
        );
    }
    try {
      gh([
        'api',
        '-X',
        'PATCH',
        `repos/${repo}/branches/main/protection/required_pull_request_reviews`,
        '-F',
        'required_approving_review_count=1',
      ]);
      log('Đã đặt 1 reviewer cho branch protection cổ điển của main');
    } catch {
      log('Không có branch protection cổ điển trên main (bỏ qua)');
    }
  }
}
log(apply ? 'Xong.' : 'Chạy lại với --apply để thực hiện.');
