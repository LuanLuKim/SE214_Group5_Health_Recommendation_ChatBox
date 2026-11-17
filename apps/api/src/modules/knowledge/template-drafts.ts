import {
  planTemplateDraftInputSchema,
  planTemplateDraftPatchSchema,
  type ApprovedTemplateProvider,
  type ApprovedPlanTemplate,
  type PlanTemplateDraft,
} from '@health/contracts';
import { z } from 'zod';
import { assertContentAuthor, AuthoringError, type ContentActor } from './authoring-access.js';
import type { KnowledgeDatabase } from './repository.js';
import { createTemplateRepository } from './template-repository.js';

function requireId(id: string): string {
  const result = z.uuid().safeParse(id);
  if (!result.success) throw missing();
  return result.data.toLowerCase();
}
const missing = () => new AuthoringError(404, 'NOT_FOUND', 'Không tìm thấy mẫu kế hoạch.');
const conflict = () =>
  new AuthoringError(
    409,
    'CONTENT_NOT_EDITABLE',
    'Chỉ được sửa hoặc xóa mẫu nháp và mẫu được trả về.',
  );
const inUse = () =>
  new AuthoringError(
    409,
    'TEMPLATE_IN_USE',
    'Mẫu đang được lộ trình tham chiếu nên không thể xóa.',
  );

export function createTemplateDraftService(db: KnowledgeDatabase) {
  const repository = createTemplateRepository(db);
  return {
    async create(actor: ContentActor, input: unknown) {
      assertContentAuthor(actor);
      return repository.create(planTemplateDraftInputSchema.parse(input), actor.userId);
    },
    async list(actor: ContentActor) {
      assertContentAuthor(actor);
      return repository.list();
    },
    async get(actor: ContentActor, id: string) {
      assertContentAuthor(actor);
      const item = await repository.get(requireId(id));
      if (!item) throw missing();
      return item;
    },
    async update(actor: ContentActor, id: string, input: unknown) {
      assertContentAuthor(actor);
      const normalizedId = requireId(id);
      const patch = planTemplateDraftPatchSchema.parse(input);
      const current = await repository.get(normalizedId);
      if (!current) throw missing();
      if (!['DRAFT', 'RETURNED'].includes(current.reviewStatus)) throw conflict();
      // Legacy templates must supply missing metadata before a write can satisfy the new constraints.
      planTemplateDraftInputSchema.strip().parse({ ...current, ...patch });
      const item = await repository.update(normalizedId, patch, actor.userId);
      if (item) return item;
      if (!(await repository.get(normalizedId))) throw missing();
      throw conflict();
    },
    async delete(actor: ContentActor, id: string) {
      assertContentAuthor(actor);
      const normalizedId = requireId(id);
      try {
        if (await repository.delete(normalizedId)) return;
      } catch (error) {
        if (error && typeof error === 'object' && 'code' in error && error.code === '23503')
          throw inUse();
        throw error;
      }
      const current = await repository.get(normalizedId);
      if (!current) throw missing();
      if (!['DRAFT', 'RETURNED'].includes(current.reviewStatus)) throw conflict();
      throw inUse();
    },
    async submit(actor: ContentActor, id: string) {
      assertContentAuthor(actor);
      const normalizedId = requireId(id);
      const current = await repository.get(normalizedId);
      if (!current) throw missing();
      if (!['DRAFT', 'RETURNED'].includes(current.reviewStatus)) {
        throw new AuthoringError(
          409,
          'CONTENT_NOT_SUBMITTABLE',
          'Mẫu không ở trạng thái được gửi duyệt.',
        );
      }
      planTemplateDraftInputSchema.strip().parse(current);
      const submitted = await repository.submit(normalizedId, actor.userId, current.revision);
      if (!submitted)
        throw new AuthoringError(409, 'CONTENT_CHANGED', 'Nội dung đã thay đổi. Vui lòng tải lại.');
      return submitted;
    },
  };
}

// Internal M6 adapter: no draft or editorial identity is exposed.
export function createApprovedTemplateProvider(db: KnowledgeDatabase): ApprovedTemplateProvider {
  const repository = createTemplateRepository(db);
  const toApproved = (item: PlanTemplateDraft): ApprovedPlanTemplate | null => {
    const parsed = planTemplateDraftInputSchema.strip().safeParse(item);
    if (!parsed.success || item.reviewStatus !== 'APPROVED' || !item.verifiedAt) return null;
    return {
      ...parsed.data,
      id: item.id,
      reviewStatus: 'APPROVED',
      verifiedAt: item.verifiedAt,
      updatedAt: item.updatedAt,
    };
  };
  return {
    async listApprovedTemplates() {
      return (await repository.listApproved())
        .map(toApproved)
        .filter((item): item is ApprovedPlanTemplate => item !== null);
    },
    async getApprovedTemplate(id: string) {
      const parsed = z.uuid().safeParse(id);
      if (!parsed.success) return null;
      const item = await repository.getApproved(parsed.data.toLowerCase());
      return item ? toApproved(item) : null;
    },
  };
}
