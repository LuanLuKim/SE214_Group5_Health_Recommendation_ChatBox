import { randomUUID } from 'node:crypto';
import type { ArticleDraft, ArticleDraftInput, ArticleDraftPatch } from '@health/contracts';

export interface KnowledgeDatabase {
  query(sql: string, values?: unknown[]): Promise<{ rows: Record<string, unknown>[] }>;
}

const articleFields = `id, title, topic, content, source_name, source_url, source_updated_at,
  copyright_notes, verified_at, review_status, created_by, updated_by, created_at, updated_at,
  revision, submitted_at, submitted_by`;

function mapArticle(row: Record<string, unknown>): ArticleDraft {
  return {
    id: row.id as string,
    title: row.title as string,
    topic: row.topic as string,
    content: row.content as string,
    sourceName: row.source_name as string,
    sourceUrl: row.source_url as string,
    sourceUpdatedAt: (row.source_updated_at as Date | null)?.toISOString() ?? null,
    copyrightNotes: row.copyright_notes as string,
    verifiedAt: (row.verified_at as Date | null)?.toISOString() ?? null,
    reviewStatus: row.review_status as ArticleDraft['reviewStatus'],
    createdBy: row.created_by as string | null,
    updatedBy: row.updated_by as string | null,
    createdAt: (row.created_at as Date).toISOString(),
    updatedAt: (row.updated_at as Date).toISOString(),
    revision: row.revision as number,
    submittedAt: (row.submitted_at as Date | null)?.toISOString() ?? null,
    submittedBy: row.submitted_by as string | null,
  };
}

export function createArticleRepository(db: KnowledgeDatabase) {
  return {
    async create(input: ArticleDraftInput, actorId: string): Promise<ArticleDraft> {
      const result = await db.query(
        `INSERT INTO knowledge.articles
           (id, title, topic, content, source_name, source_url, source_updated_at,
            copyright_notes, created_by, updated_by, review_status, verified_at)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$9,'DRAFT',NULL) RETURNING ${articleFields}`,
        [
          randomUUID(),
          input.title,
          input.topic,
          input.content,
          input.sourceName,
          input.sourceUrl,
          input.sourceUpdatedAt ?? null,
          input.copyrightNotes ?? '',
          actorId,
        ],
      );
      return mapArticle(result.rows[0]!);
    },
    async list(): Promise<ArticleDraft[]> {
      const result = await db.query(
        `SELECT ${articleFields} FROM knowledge.articles ORDER BY created_at DESC, id`,
      );
      return result.rows.map(mapArticle);
    },
    async get(id: string): Promise<ArticleDraft | null> {
      const result = await db.query(
        `SELECT ${articleFields} FROM knowledge.articles WHERE id = $1`,
        [id],
      );
      return result.rows[0] ? mapArticle(result.rows[0]) : null;
    },
    async update(
      id: string,
      input: ArticleDraftPatch,
      actorId: string,
    ): Promise<ArticleDraft | null> {
      const columns = {
        title: 'title',
        topic: 'topic',
        content: 'content',
        sourceName: 'source_name',
        sourceUrl: 'source_url',
        sourceUpdatedAt: 'source_updated_at',
        copyrightNotes: 'copyright_notes',
      } as const;
      const values: unknown[] = [id, actorId];
      const assignments: string[] = [];
      for (const key of Object.keys(columns) as Array<keyof typeof columns>) {
        if (input[key] === undefined) continue;
        values.push(input[key]);
        assignments.push(`${columns[key]} = $${values.length}`);
      }
      const result = await db.query(
        `UPDATE knowledge.articles SET ${assignments.join(', ')}, updated_by = $2,
           review_status = 'DRAFT', verified_at = NULL, updated_at = now(),
           revision = revision + 1, submitted_at = NULL, submitted_by = NULL
         WHERE id = $1 AND review_status IN ('DRAFT','RETURNED') RETURNING ${articleFields}`,
        values,
      );
      return result.rows[0] ? mapArticle(result.rows[0]) : null;
    },
    async submit(id: string, actorId: string, revision: number): Promise<ArticleDraft | null> {
      const result = await db.query(
        `UPDATE knowledge.articles SET review_status = 'PENDING_REVIEW', verified_at = NULL,
           submitted_at = now(), submitted_by = $2, updated_by = $2, updated_at = now(),
           revision = revision + 1
         WHERE id = $1 AND review_status IN ('DRAFT','RETURNED') AND revision = $3
         RETURNING ${articleFields}`,
        [id, actorId, revision],
      );
      return result.rows[0] ? mapArticle(result.rows[0]) : null;
    },
    async delete(id: string): Promise<boolean> {
      const result = await db.query(
        `DELETE FROM knowledge.articles WHERE id = $1 AND review_status IN ('DRAFT','RETURNED') RETURNING id`,
        [id],
      );
      return result.rows.length > 0;
    },
  };
}
