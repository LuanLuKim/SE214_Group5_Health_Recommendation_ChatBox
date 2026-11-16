import type { ArticleDetail, ArticleSummary, FacilitySummary } from '@health/contracts';

// Public UI fixtures only. Same fixed IDs as database/seeds/001_synthetic.sql.
const verifiedAt = '2026-10-29T00:00:00.000Z'; // Fixture timestamp, not a real medical review.
export const syntheticArticleDetails: ArticleDetail[] = [
  {
    id: '00000000-0000-4000-8000-000000000010',
    title: '[TEST] Kiểm thử bài viết về giấc ngủ',
    topic: 'Giấc ngủ',
    content: 'Nội dung giả lập để kiểm tra giao diện. Không có hướng dẫn y tế.',
    source: {
      id: '00000000-0000-4000-8000-000000000010',
      name: 'Synthetic fixture',
      url: 'https://example.invalid/articles/sleep',
      verifiedAt,
    },
    updatedAt: verifiedAt,
  },
];
export const syntheticArticles: ArticleSummary[] = syntheticArticleDetails.map(
  ({ content: _content, ...article }) => article,
);
export const syntheticFacilities: FacilitySummary[] = [
  {
    id: '00000000-0000-4000-8000-000000000020',
    name: '[TEST] Cơ sở giả lập A',
    specialties: ['Chuyên khoa thử nghiệm'],
    province: 'Tỉnh kiểm thử',
    address: 'Địa chỉ giả lập, không có thật',
    officialUrl: 'https://example.invalid/facility',
    source: {
      id: '00000000-0000-4000-8000-000000000020',
      name: 'Synthetic fixture',
      url: 'https://example.invalid/facility',
      verifiedAt,
    },
  },
];
