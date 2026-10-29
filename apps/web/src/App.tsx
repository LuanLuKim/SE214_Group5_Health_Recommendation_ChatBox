import { useEffect, useState } from 'react';
import { moduleIdSchema, modules, type ModuleId } from '@health/contracts';
import { getModules } from './lib/api';
import { modulePages } from './modules';

type View = ModuleId | 'overview';
function currentView(): View {
  const parsed = moduleIdSchema.safeParse(window.location.hash.slice(1));
  return parsed.success ? parsed.data : 'overview';
}

export function App() {
  const [view, setView] = useState<View>(currentView);
  const [connection, setConnection] = useState<'loading' | 'connected' | 'offline'>('loading');
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    const onHashChange = () => setView(currentView());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    setConnection('loading');
    void getModules(controller.signal)
      .then((items) => {
        if (items.length !== modules.length) throw new Error('Unexpected module manifest');
        setConnection('connected');
      })
      .catch(() => {
        if (!controller.signal.aborted) setConnection('offline');
      });
    return () => controller.abort();
  }, [refresh]);
  useEffect(() => {
    document.title = `${view === 'overview' ? 'Tổng quan' : modules.find((item) => item.id === view)?.title} · Trợ lý Sức khỏe AI`;
  }, [view]);
  const Page = view === 'overview' ? null : modulePages[view];
  return (
    <div className="app-shell">
      <a
        className="skip-link"
        href="#main-content"
        onClick={(event) => {
          event.preventDefault();
          document.getElementById('main-content')?.focus();
        }}
      >
        Đến nội dung chính
      </a>
      <aside className="sidebar">
        <a className="brand" href="#overview">
          <span className="brand-mark" aria-hidden="true">
            +
          </span>
          <span>
            Sức khỏe AI<small>SE214 · NHÓM 5</small>
          </span>
        </a>
        <div className="nav-label">KHÔNG GIAN LÀM VIỆC</div>
        <nav aria-label="Điều hướng chính">
          <a
            className={view === 'overview' ? 'active' : ''}
            aria-current={view === 'overview' ? 'page' : undefined}
            href="#overview"
          >
            <span className="nav-number">⌂</span>Tổng quan
          </a>
          {modules.map((module) => (
            <a
              key={module.id}
              className={view === module.id ? 'active' : ''}
              aria-current={view === module.id ? 'page' : undefined}
              href={`#${module.id}`}
            >
              <span className="nav-number">{String(module.number).padStart(2, '0')}</span>
              <span>{module.title}</span>
            </a>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <span className="small-dot" />
          Phiên bản nền tảng <strong>0.1.0</strong>
          <p>Sáu module · Một nền tảng chung</p>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <span>
            Trợ lý Sức khỏe AI{' '}
            <span className="breadcrumb">
              /{' '}
              {view === 'overview'
                ? 'Tổng quan'
                : `Module ${modules.find((item) => item.id === view)?.number}`}
            </span>
          </span>
          <span className={`connection ${connection}`} role="status">
            <span className="small-dot" />
            {connection === 'connected'
              ? 'API đã kết nối'
              : connection === 'loading'
                ? 'Đang kết nối API'
                : 'API chưa kết nối'}
          </span>
        </header>
        <main id="main-content" tabIndex={-1} key={view}>
          {Page ? (
            <Page />
          ) : (
            <>
              <div className="eyebrow">SE214 GROUP 5 · PROJECT BASELINE</div>
              <h1>
                Cùng xây dựng một
                <br />
                thói quen khỏe mạnh hơn.
              </h1>
              <p className="lead">
                Nền tảng phát triển cho trợ lý sức khỏe bằng tiếng Việt.
                <br className="desktop-break" /> Mỗi module có giao diện, API và phạm vi công việc
                riêng.
              </p>
              <div className="overview-line">
                <span>06 module</span>
                <span>19 use case</span>
                <span>React + TypeScript</span>
              </div>
              <div className="section-heading">
                <h2>Các module của dự án</h2>
                <span>Chọn module để bắt đầu</span>
              </div>
              <div className="module-grid">
                {modules.map((module) => (
                  <a className="module-card" key={module.id} href={`#${module.id}`}>
                    <div className="card-top">
                      <span className="module-number">
                        {String(module.number).padStart(2, '0')}
                      </span>
                      <span className="status">Bản khung</span>
                    </div>
                    <h3>{module.title}</h3>
                    <p>{module.description}</p>
                    <div className="card-footer">
                      <span>{module.useCases.join(' · ')}</span>
                      <span className="card-arrow" aria-hidden="true">
                        ↗
                      </span>
                    </div>
                  </a>
                ))}
              </div>
              <div className="integration-strip">
                <div>
                  <h3>Kết nối frontend & backend</h3>
                  <p>
                    {connection === 'connected'
                      ? 'Sáu module đã được khai báo trong API dùng chung.'
                      : 'Khởi động cả hai ứng dụng bằng npm run dev để kiểm tra kết nối.'}
                  </p>
                </div>
                <button
                  className="secondary-button"
                  type="button"
                  disabled={connection === 'loading'}
                  onClick={() => setRefresh((value) => value + 1)}
                >
                  Kiểm tra lại <span aria-hidden="true">↻</span>
                </button>
              </div>
            </>
          )}
          <footer className="page-footer">
            <span>Bản khung phát triển · Chưa cung cấp tư vấn sức khỏe</span>
            <span>SE214 / Nhóm 5</span>
          </footer>
        </main>
      </div>
    </div>
  );
}
