import { randomUUID } from 'node:crypto';
import type { RuleDraftInput, SafetyRule } from '@health/contracts';

export interface RuleDatabase {
  query(sql: string, values?: unknown[]): Promise<{ rows: Record<string, unknown>[] }>;
}

function mapRule(row: Record<string, unknown>): SafetyRule {
  return {
    id: row.id as string,
    name: row.name as string,
    conditions: row.conditions as RuleDraftInput['conditions'],
    requiredFields: row.required_fields as string[],
    careLevel: row.care_level as SafetyRule['careLevel'],
    specialtyIds: row.specialty_ids as string[],
    sourceName: row.source_name as string,
    sourceUrl: row.source_url as string,
    reviewStatus: row.review_status as SafetyRule['reviewStatus'],
    isSynthetic: row.is_synthetic as boolean,
    createdAt: (row.created_at as Date).toISOString(),
    updatedAt: (row.updated_at as Date).toISOString(),
  };
}

const fields = `id, name, conditions, required_fields, care_level, specialty_ids,
  source_name, source_url, review_status, is_synthetic, created_at, updated_at`;

export function createRuleRepository(db: RuleDatabase) {
  return {
    async create(input: RuleDraftInput): Promise<SafetyRule> {
      const id = randomUUID();
      const result = await db.query(
        `INSERT INTO safety.rules
          (id, name, conditions, required_fields, care_level, specialty_ids, source_name, source_url)
         VALUES ($1, $2, $3::jsonb, $4, $5, $6, $7, $8)
         RETURNING ${fields}`,
        [
          id,
          input.name,
          JSON.stringify(input.conditions),
          input.requiredFields,
          input.careLevel,
          input.specialtyIds,
          input.sourceName,
          input.sourceUrl,
        ],
      );
      return mapRule(result.rows[0]!);
    },
    async list(): Promise<SafetyRule[]> {
      const result = await db.query(
        `SELECT ${fields} FROM safety.rules ORDER BY created_at DESC, id`,
      );
      return result.rows.map(mapRule);
    },
    async get(id: string): Promise<SafetyRule | null> {
      const result = await db.query(`SELECT ${fields} FROM safety.rules WHERE id = $1`, [id]);
      return result.rows[0] ? mapRule(result.rows[0]) : null;
    },
    async update(id: string, input: RuleDraftInput): Promise<SafetyRule | null> {
      const result = await db.query(
        `UPDATE safety.rules SET
          name = $2, conditions = $3::jsonb, required_fields = $4, care_level = $5,
          specialty_ids = $6, source_name = $7, source_url = $8,
          review_status = 'DRAFT', updated_at = now()
         WHERE id = $1 AND review_status IN ('DRAFT', 'RETURNED')
         RETURNING ${fields}`,
        [
          id,
          input.name,
          JSON.stringify(input.conditions),
          input.requiredFields,
          input.careLevel,
          input.specialtyIds,
          input.sourceName,
          input.sourceUrl,
        ],
      );
      return result.rows[0] ? mapRule(result.rows[0]) : null;
    },
  };
}
