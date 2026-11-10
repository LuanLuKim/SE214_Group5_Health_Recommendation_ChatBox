// Chạy qua `npm install` (script prepare): trỏ Git tới .githooks để chặn commit/push ngoài nhánh issue.
import { execFileSync } from 'node:child_process';

try {
  execFileSync('git', ['rev-parse', '--is-inside-work-tree'], { stdio: 'ignore' });
  execFileSync('git', ['config', 'core.hooksPath', '.githooks'], { stdio: 'ignore' });
} catch {
  // Không phải bản clone Git (ví dụ tải ZIP) hoặc chưa cài Git: bỏ qua, không làm hỏng npm install.
}
