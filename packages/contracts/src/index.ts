import { z } from 'zod';

export const careLevelSchema = z.enum([
  'EMERGENCY_NOW',
  'SEEK_CARE_SOON',
  'ROUTINE_CONSULT',
  'GENERAL_SELF_CARE_INFO',
  'INSUFFICIENT_INFO',
]);
export type CareLevel = z.infer<typeof careLevelSchema>;
export const roleSchema = z.enum(['USER', 'CONTENT_EXPERT', 'ADMIN']);
export type Role = z.infer<typeof roleSchema>;
export const reviewStatusSchema = z.enum([
  'DRAFT',
  'PENDING_REVIEW',
  'APPROVED',
  'RETURNED',
  'RETIRED',
]);
export type ReviewStatus = z.infer<typeof reviewStatusSchema>;
export const moduleIdSchema = z.enum([
  'safety',
  'knowledge',
  'health',
  'chat',
  'accounts',
  'lifestyle',
]);
export type ModuleId = z.infer<typeof moduleIdSchema>;
export const moduleDescriptorSchema = z.object({
  id: moduleIdSchema,
  number: z.number().int(),
  title: z.string(),
  description: z.string(),
  useCases: z.array(z.string()),
  apiPrefix: z.string(),
  status: z.literal('scaffold'),
});
export type ModuleDescriptor = z.infer<typeof moduleDescriptorSchema>;
export const moduleListSchema = z.array(moduleDescriptorSchema);

export const modules: ModuleDescriptor[] = [
  {
    id: 'safety',
    number: 1,
    title: 'Safety Gate & quy tắc cảnh báo',
    description: 'Đánh giá mức độ cần chú ý bằng quy tắc đã duyệt.',
    useCases: ['UC-07', 'UC-16'],
    apiPrefix: '/api/v1/safety',
    status: 'scaffold',
  },
  {
    id: 'knowledge',
    number: 2,
    title: 'Tra cứu & quản lý nội dung',
    description: 'Kiến thức, cơ sở y tế và quy trình rà soát nội dung.',
    useCases: ['UC-10', 'UC-11', 'UC-15', 'UC-17'],
    apiPrefix: '/api/v1/knowledge',
    status: 'scaffold',
  },
  {
    id: 'health',
    number: 3,
    title: 'Hồ sơ, chỉ số & thống kê',
    description: 'Hồ sơ cá nhân, chỉ số theo ngày và thống kê tổng hợp.',
    useCases: ['UC-04', 'UC-05', 'UC-19'],
    apiPrefix: '/api/v1/health',
    status: 'scaffold',
  },
  {
    id: 'chat',
    number: 4,
    title: 'Hội thoại với trợ lý AI',
    description: 'Hội thoại, phản hồi câu trả lời và lịch sử riêng tư.',
    useCases: ['UC-06', 'UC-08', 'UC-09'],
    apiPrefix: '/api/v1/chat',
    status: 'scaffold',
  },
  {
    id: 'accounts',
    number: 5,
    title: 'Tài khoản & phân quyền',
    description: 'Đăng ký, đồng ý điều khoản, tài khoản và quyền truy cập.',
    useCases: ['UC-01', 'UC-02', 'UC-03', 'UC-18'],
    apiPrefix: '/api/v1/accounts',
    status: 'scaffold',
  },
  {
    id: 'lifestyle',
    number: 6,
    title: 'Lộ trình sinh hoạt & nhắc nhở',
    description: 'Lộ trình từ mẫu đã duyệt, thói quen và nhắc nhở.',
    useCases: ['UC-12', 'UC-13', 'UC-14'],
    apiPrefix: '/api/v1/lifestyle',
    status: 'scaffold',
  },
];

export interface ApiSuccess<T> {
  data: T;
  meta?: { status: string; total?: number };
}
export interface ApiFailure {
  error: { code: string; message: string; requestId: string };
}
export interface SourceReference {
  id: string;
  name: string;
  url: string;
  verifiedAt: string;
}
export interface ArticleSummary {
  id: string;
  title: string;
  topic: string;
  source: SourceReference;
  updatedAt: string;
}
export interface FacilitySummary {
  id: string;
  name: string;
  specialties: string[];
  province: string;
  address: string;
  officialUrl: string;
  source: SourceReference;
}

// M1 -> M4: care direction is controlled by reviewed rules, never an LLM.
export interface SafetyDecision {
  status: 'EVALUATED' | 'UNAVAILABLE';
  careLevel: CareLevel | null;
  matchedRuleIds: string[];
  missingFields: string[];
  specialtyIds: string[];
  sources: SourceReference[];
}
export interface SafetyEvaluator {
  evaluate(input: { text: string }): Promise<SafetyDecision>;
}
// M2 -> M4: implementations must return only approved, current content.
export interface KnowledgeRetriever {
  retrieve(query: string): Promise<ArticleSummary[]>;
}
// M4 -> M2: expert review is limited to the explicitly shared pair, without identity.
export interface AnonymousFeedback {
  id: string;
  reason: 'INCORRECT' | 'IRRELEVANT_SOURCE' | 'UNCLEAR' | 'OTHER';
  note: string;
  attachment: { question: string; answer: string } | null;
  createdAt: string;
}
