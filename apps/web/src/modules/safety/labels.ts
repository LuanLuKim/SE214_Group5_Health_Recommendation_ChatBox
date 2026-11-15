import type { CareLevel, ReviewStatus } from '@health/contracts';

/**
 * The one place for Vietnamese care-level wording (UC-16). Meanings follow S1 Table 2.2
 * (docs/requirements/PROJECT_CONTEXT.md §5); the first three mirror the NHS headings used in the
 * M7 corpus (Immediate action required / Urgent advice / Non-urgent advice).
 */
export const careLevelLabels: Record<CareLevel, { label: string; hint: string }> = {
  EMERGENCY_NOW: {
    label: 'Cấp cứu ngay',
    hint: 'Hiển thị hướng dẫn gọi 115 hoặc đến cơ sở y tế gần nhất, dừng tư vấn thông thường.',
  },
  SEEK_CARE_SOON: {
    label: 'Cần đi khám sớm',
    hint: 'Khuyên liên hệ cơ sở y tế trong khoảng thời gian quy tắc quy định, gợi ý chuyên khoa.',
  },
  ROUTINE_CONSULT: {
    label: 'Nên khám định kỳ',
    hint: 'Khuyên sắp xếp khám khi triệu chứng kéo dài hoặc tái phát, gợi ý chuyên khoa.',
  },
  GENERAL_SELF_CARE_INFO: {
    label: 'Thông tin tự chăm sóc',
    hint: 'Cung cấp thông tin chăm sóc chung có nguồn và các dấu hiệu cần đi khám.',
  },
  INSUFFICIENT_INFO: {
    label: 'Cần thêm thông tin',
    hint: 'Hỏi thêm thông tin còn thiếu, chưa đưa ra kết luận.',
  },
};

export const reviewStatusLabels: Record<ReviewStatus, string> = {
  DRAFT: 'Nháp',
  PENDING_REVIEW: 'Chờ duyệt',
  APPROVED: 'Đã duyệt',
  RETURNED: 'Bị trả lại',
  RETIRED: 'Ngừng dùng',
};
