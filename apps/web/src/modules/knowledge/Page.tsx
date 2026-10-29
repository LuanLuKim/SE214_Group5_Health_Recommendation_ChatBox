import { useEffect, useState } from 'react';
import { ModulePanel } from '../../components/ModulePanel';
import { getArticles, getFacilities } from '../../lib/api';

export default function Page() {
  const [kind, setKind] = useState<'articles' | 'facilities'>('articles');
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [state, setState] = useState<'loading' | 'empty' | 'error'>('loading');
  const [refresh, setRefresh] = useState(0);
  const [items, setItems] = useState<Array<{ id: string; label: string }>>([]);
  useEffect(() => {
    const controller = new AbortController();
    setState('loading');
    const request = kind === 'articles' ? getArticles : getFacilities;
    void request(submitted, controller.signal)
      .then((result) => {
        setItems(
          result.data.map((item) => ({
            id: item.id,
            label: 'title' in item ? item.title : item.name,
          })),
        );
        setState('empty');
      })
      .catch(() => {
        if (!controller.signal.aborted) setState('error');
      });
    return () => controller.abort();
  }, [kind, submitted, refresh]);
  return (
    <ModulePanel id="knowledge">
      <div className="search-card">
        <div className="segmented" aria-label="Loại tra cứu">
          <button
            type="button"
            aria-pressed={kind === 'articles'}
            onClick={() => setKind('articles')}
          >
            Kiến thức sức khỏe
          </button>
          <button
            type="button"
            aria-pressed={kind === 'facilities'}
            onClick={() => setKind('facilities')}
          >
            Cơ sở y tế
          </button>
        </div>
        <form
          className="search-form"
          onSubmit={(event) => {
            event.preventDefault();
            setSubmitted(query);
            setRefresh((value) => value + 1);
          }}
        >
          <label htmlFor="search">
            {kind === 'articles' ? 'Tìm theo chủ đề hoặc từ khóa' : 'Tìm cơ sở y tế theo tên'}
          </label>
          <div className="search-input-row">
            <input
              id="search"
              value={query}
              maxLength={200}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={kind === 'articles' ? 'Ví dụ: giấc ngủ' : 'Nhập tên cơ sở y tế'}
            />
            <button className="primary-button" type="submit" disabled={state === 'loading'}>
              Tìm kiếm
            </button>
          </div>
        </form>
        <p className="module-note">
          Dữ liệu thử nghiệm tổng hợp. Các bài viết và cơ sở có nhãn [TEST] không phải thông tin y
          tế thật.
        </p>
        {state === 'empty' && items.length > 0 ? (
          <ul className="fixture-list" aria-live="polite">
            {items.map((item) => (
              <li key={item.id}>
                <span className="status">Synthetic</span> {item.label}
              </li>
            ))}
          </ul>
        ) : (
          <div className="empty-state" aria-live="polite">
            <div className="empty-symbol" aria-hidden="true">
              {state === 'error' ? '!' : '○'}
            </div>
            <h3>
              {state === 'loading'
                ? 'Đang kết nối kho dữ liệu…'
                : state === 'error'
                  ? 'Chưa kết nối được backend'
                  : submitted
                    ? 'Không tìm thấy kết quả phù hợp'
                    : kind === 'articles'
                      ? 'Chưa có bài viết đã duyệt'
                      : 'Chưa có danh mục đã xác minh'}
            </h3>
            <p>
              {state === 'error'
                ? 'Khởi động API và thử tìm kiếm lại.'
                : state === 'loading'
                  ? 'Vui lòng đợi trong giây lát.'
                  : 'Kho dữ liệu sẽ được bổ sung sau bước thu thập và rà soát nội dung.'}
            </p>
          </div>
        )}
      </div>
      <p className="module-note">
        Soạn bài viết, phê duyệt nội dung và rà soát phản hồi sẽ được bổ sung trong module này với
        quyền chuyên gia nội dung.
      </p>
    </ModulePanel>
  );
}
