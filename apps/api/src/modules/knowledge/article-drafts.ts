import { articleDraftInputSchema, articleDraftPatchSchema } from '@health/contracts';
import { z } from 'zod';
import { assertContentAuthor, AuthoringError, type ContentActor } from './authoring-access.js';
import { createArticleRepository, type KnowledgeDatabase } from './repository.js';

export function createArticleDraftService(db: KnowledgeDatabase) {
  const repository = createArticleRepository(db);
  const requireId = (id: string) => {
    const result = z.uuid().safeParse(id);
    if (!result.success) throw new AuthoringError(404, 'NOT_FOUND', 'Không tìm thấy bài viết.');
    return result.data.toLowerCase();
  };
  const missing = () => new AuthoringError(404, 'NOT_FOUND', 'Không tìm thấy bài viết.');
  const conflict = () =>
    new AuthoringError(
      409,
      'CONTENT_NOT_EDITABLE',
      'Chỉ được sửa hoặc xóa bài nháp và bài được trả về.',
    );
  return {
    async create(actor: ContentActor, input: unknown) {
      assertContentAuthor(actor);
      return repository.create(articleDraftInputSchema.parse(input), actor.userId);
    },
    async list(actor: ContentActor) {
      assertContentAuthor(actor);
      return repository.list();
    },
    async get(actor: ContentActor, id: string) {
      assertContentAuthor(actor);
      const result = await repository.get(requireId(id));
      if (!result) throw missing();
      return result;
    },
    async update(actor: ContentActor, id: string, input: unknown) {
      assertContentAuthor(actor);
      const normalizedId = requireId(id);
      const result = await repository.update(
        normalizedId,
        articleDraftPatchSchema.parse(input),
        actor.userId,
      );
      if (result) return result;
      if (!(await repository.get(normalizedId))) throw missing();
      throw conflict();
    },
    async delete(actor: ContentActor, id: string) {
      assertContentAuthor(actor);
      const normalizedId = requireId(id);
      if (await repository.delete(normalizedId)) return;
      if (!(await repository.get(normalizedId))) throw missing();
      throw conflict();
    },
  };
}
