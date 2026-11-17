import { randomUUID } from 'node:crypto';
import type {
  PlanTemplateDraft,
  PlanTemplateDraftInput,
  PlanTemplateDraftPatch,
} from '@health/contracts';
import type { KnowledgeDatabase } from './repository.js';

const fields = `id, title, goal, activities, excluded_allergens, duration_days, applicability,
  contraindications, advisory, source_name, source_url, source_updated_at, copyright_notes,
  verified_at, review_status, created_by, updated_by, created_at, updated_at,
  revision, submitted_at, submitted_by`;
const approvedFilter = `review_status = 'APPROVED' AND verified_at IS NOT NULL
  AND btrim(source_name) <> '' AND btrim(source_url) <> ''`;

function mapTemplate(row: Record<string, unknown>): PlanTemplateDraft {
  return {
    id: row.id as string,
    title: row.title as string,
    goal: row.goal as PlanTemplateDraft['goal'],
    activities: row.activities as PlanTemplateDraft['activities'],
    excludedAllergens: row.excluded_allergens as string[],
    durationDays: row.duration_days as number | null,
    applicability: row.applicability as string,
    contraindications: row.contraindications as string[],
    advisory: row.advisory as string,
    sourceName: row.source_name as string,
    sourceUrl: row.source_url as string,
    sourceUpdatedAt: (row.source_updated_at as Date | null)?.toISOString() ?? null,
    copyrightNotes: row.copyright_notes as string,
    verifiedAt: (row.verified_at as Date | null)?.toISOString() ?? null,
    reviewStatus: row.review_status as PlanTemplateDraft['reviewStatus'],
    createdBy: row.created_by as string | null,
    updatedBy: row.updated_by as string | null,
    createdAt: (row.created_at as Date).toISOString(),
    updatedAt: (row.updated_at as Date).toISOString(),
    revision: row.revision as number,
    submittedAt: (row.submitted_at as Date | null)?.toISOString() ?? null,
    submittedBy: row.submitted_by as string | null,
  };
}

export function createTemplateRepository(db: KnowledgeDatabase) {
  return {
    async create(input: PlanTemplateDraftInput, actorId: string): Promise<PlanTemplateDraft> {
      const result = await db.query(
        `INSERT INTO lifestyle.templates
           (id, title, goal, activities, excluded_allergens, duration_days, applicability,
            contraindications, advisory, source_name, source_url, source_updated_at,
            copyright_notes, created_by, updated_by, review_status, verified_at)
         VALUES ($1,$2,$3,$4::jsonb,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$14,'DRAFT',NULL)
         RETURNING ${fields}`,
        [
          randomUUID(),
          input.title,
          input.goal,
          JSON.stringify(input.activities),
          input.excludedAllergens,
          input.durationDays,
          input.applicability,
          input.contraindications,
          input.advisory,
          input.sourceName,
          input.sourceUrl,
          input.sourceUpdatedAt ?? null,
          input.copyrightNotes ?? '',
          actorId,
        ],
      );
      return mapTemplate(result.rows[0]!);
    },
    async list(): Promise<PlanTemplateDraft[]> {
      const result = await db.query(
        `SELECT ${fields} FROM lifestyle.templates ORDER BY created_at DESC, id`,
      );
      return result.rows.map(mapTemplate);
    },
    async get(id: string): Promise<PlanTemplateDraft | null> {
      const result = await db.query(`SELECT ${fields} FROM lifestyle.templates WHERE id = $1`, [
        id,
      ]);
      return result.rows[0] ? mapTemplate(result.rows[0]) : null;
    },
    async update(
      id: string,
      input: PlanTemplateDraftPatch,
      actorId: string,
    ): Promise<PlanTemplateDraft | null> {
      const columns = {
        title: 'title',
        goal: 'goal',
        activities: 'activities',
        excludedAllergens: 'excluded_allergens',
        durationDays: 'duration_days',
        applicability: 'applicability',
        contraindications: 'contraindications',
        advisory: 'advisory',
        sourceName: 'source_name',
        sourceUrl: 'source_url',
        sourceUpdatedAt: 'source_updated_at',
        copyrightNotes: 'copyright_notes',
      } as const;
      const values: unknown[] = [id, actorId];
      const assignments: string[] = [];
      for (const key of Object.keys(columns) as Array<keyof typeof columns>) {
        if (input[key] === undefined) continue;
        values.push(key === 'activities' ? JSON.stringify(input[key]) : input[key]);
        assignments.push(
          `${columns[key]} = $${values.length}${key === 'activities' ? '::jsonb' : ''}`,
        );
      }
      const result = await db.query(
        `UPDATE lifestyle.templates SET ${assignments.join(', ')}, updated_by = $2,
           review_status = 'DRAFT', verified_at = NULL, updated_at = now(),
           revision = revision + 1, submitted_at = NULL, submitted_by = NULL
         WHERE id = $1 AND review_status IN ('DRAFT','RETURNED') RETURNING ${fields}`,
        values,
      );
      return result.rows[0] ? mapTemplate(result.rows[0]) : null;
    },
    async submit(id: string, actorId: string, revision: number): Promise<PlanTemplateDraft | null> {
      const result = await db.query(
        `UPDATE lifestyle.templates SET review_status = 'PENDING_REVIEW', verified_at = NULL,
           submitted_at = now(), submitted_by = $2, updated_by = $2, updated_at = now(), revision = revision + 1
         WHERE id = $1 AND review_status IN ('DRAFT','RETURNED') AND revision = $3 RETURNING ${fields}`,
        [id, actorId, revision],
      );
      return result.rows[0] ? mapTemplate(result.rows[0]) : null;
    },
    async delete(id: string): Promise<boolean> {
      const result = await db.query(
        `DELETE FROM lifestyle.templates WHERE id = $1 AND review_status IN ('DRAFT','RETURNED')
           AND NOT EXISTS (SELECT 1 FROM lifestyle.plans WHERE template_id = $1) RETURNING id`,
        [id],
      );
      return result.rows.length > 0;
    },
    async listApproved(): Promise<PlanTemplateDraft[]> {
      const result = await db.query(
        `SELECT ${fields} FROM lifestyle.templates WHERE ${approvedFilter} ORDER BY title, id`,
      );
      return result.rows.map(mapTemplate);
    },
    async getApproved(id: string): Promise<PlanTemplateDraft | null> {
      const result = await db.query(
        `SELECT ${fields} FROM lifestyle.templates WHERE id = $1 AND ${approvedFilter}`,
        [id],
      );
      return result.rows[0] ? mapTemplate(result.rows[0]) : null;
    },
  };
}
