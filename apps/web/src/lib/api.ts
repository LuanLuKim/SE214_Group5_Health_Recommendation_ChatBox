import {
  moduleListSchema,
  type ArticleSummary,
  type ApiSuccess,
  type FacilitySummary,
} from '@health/contracts';

const origin = (import.meta.env?.VITE_API_ORIGIN ?? '').replace(/\/$/, '');
export async function getJson<T>(path: string, signal?: AbortSignal): Promise<ApiSuccess<T>> {
  const timeout = AbortSignal.timeout(8000);
  const response = await fetch(`${origin}${path}`, {
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) throw new Error('Không thể kết nối API. Kiểm tra backend và thử lại.');
  return (await response.json()) as ApiSuccess<T>;
}
export async function getModules(signal?: AbortSignal) {
  const payload = await getJson<unknown>('/api/v1/modules', signal);
  return moduleListSchema.parse(payload.data);
}
export const getArticles = (query: string, signal?: AbortSignal) =>
  getJson<ArticleSummary[]>(`/api/v1/knowledge/articles?q=${encodeURIComponent(query)}`, signal);
export const getFacilities = (query: string, signal?: AbortSignal) =>
  getJson<FacilitySummary[]>(`/api/v1/knowledge/facilities?q=${encodeURIComponent(query)}`, signal);
