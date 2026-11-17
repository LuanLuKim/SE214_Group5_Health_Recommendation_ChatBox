import type {
  ArticleSummary,
  FacilityLookup,
  FacilitySearchParams,
  FacilitySummary,
} from '@health/contracts';
import type { Pool } from 'pg';
import { z } from 'zod';
import { syntheticArticles, syntheticFacilities } from './synthetic.js';
import type { Config } from '../../config.js';

interface FacilityRow {
  id: string;
  name: string;
  specialties: string[];
  province: string;
  address: string;
  official_url: string;
  source_name: string;
  source_url: string;
  verified_at: Date;
}

const verifiedFacilityFilter =
  "verified_at IS NOT NULL AND btrim(source_name) <> '' AND btrim(source_url) <> ''";

function isVerifiedFacility(item: FacilitySummary): boolean {
  return (
    item.source.name.trim() !== '' &&
    item.source.url.trim() !== '' &&
    Number.isFinite(Date.parse(item.source.verifiedAt))
  );
}

function toFacilitySummary(row: FacilityRow): FacilitySummary {
  return {
    id: row.id,
    name: row.name,
    specialties: row.specialties,
    province: row.province,
    address: row.address,
    officialUrl: row.official_url,
    source: {
      id: row.id,
      name: row.source_name,
      url: row.source_url,
      verifiedAt: row.verified_at.toISOString(),
    },
  };
}

export function createKnowledgeService(
  config: Config,
  pool: Pool | null,
): FacilityLookup & { listArticles(query: string): Promise<ArticleSummary[]> } {
  return {
    async listArticles(query: string): Promise<ArticleSummary[]> {
      if (config.DATA_MODE === 'mock')
        return syntheticArticles.filter((item) =>
          `${item.title} ${item.topic}`
            .toLocaleLowerCase('vi')
            .includes(query.toLocaleLowerCase('vi')),
        );
      if (!pool) throw new Error('DATABASE_NOT_CONFIGURED');
      const result = await pool.query(
        `SELECT id, title, topic, source_name, source_url, verified_at, updated_at FROM knowledge.articles WHERE review_status = 'APPROVED' AND verified_at IS NOT NULL AND (title ILIKE $1 OR topic ILIKE $1) ORDER BY title LIMIT 20`,
        [`%${query}%`],
      );
      return result.rows.map((row) => ({
        id: row.id as string,
        title: row.title as string,
        topic: row.topic as string,
        source: {
          id: row.id as string,
          name: row.source_name as string,
          url: row.source_url as string,
          verifiedAt: (row.verified_at as Date).toISOString(),
        },
        updatedAt: (row.updated_at as Date).toISOString(),
      }));
    },
    async listFacilities(filters: FacilitySearchParams = {}): Promise<FacilitySummary[]> {
      const query = filters.q?.trim() ?? '';
      const province = filters.province?.trim() ?? '';
      const specialty = filters.specialty?.trim() ?? '';
      if (config.DATA_MODE === 'mock')
        return syntheticFacilities
          .filter(
            (item) =>
              isVerifiedFacility(item) &&
              item.name.toLocaleLowerCase('vi').includes(query.toLocaleLowerCase('vi')) &&
              (!province ||
                item.province.toLocaleLowerCase('vi') === province.toLocaleLowerCase('vi')) &&
              (!specialty ||
                item.specialties.some(
                  (value) => value.toLocaleLowerCase('vi') === specialty.toLocaleLowerCase('vi'),
                )),
          )
          .sort(
            (left, right) =>
              left.name.localeCompare(right.name, 'vi') || left.id.localeCompare(right.id),
          )
          .slice(0, 20);
      if (!pool) throw new Error('DATABASE_NOT_CONFIGURED');
      const result = await pool.query<FacilityRow>(
        `SELECT id, name, specialties, province, address, official_url,
                source_name, source_url, verified_at
         FROM knowledge.facilities
         WHERE ${verifiedFacilityFilter}
           AND ($1 = '' OR strpos(lower(name), lower($1)) > 0)
           AND ($2 = '' OR lower(province) = lower($2))
           AND ($3 = '' OR EXISTS (
             SELECT 1 FROM unnest(specialties) AS specialty(value)
             WHERE lower(specialty.value) = lower($3)
           ))
         ORDER BY name, id LIMIT 20`,
        [query, province, specialty],
      );
      return result.rows.map(toFacilitySummary);
    },
    async getFacility(id: string): Promise<FacilitySummary | null> {
      const parsed = z.uuid().safeParse(id);
      if (!parsed.success) return null;
      const normalizedId = parsed.data.toLowerCase();
      if (config.DATA_MODE === 'mock')
        return (
          syntheticFacilities.find(
            (item) => item.id === normalizedId && isVerifiedFacility(item),
          ) ?? null
        );
      if (!pool) throw new Error('DATABASE_NOT_CONFIGURED');
      const result = await pool.query<FacilityRow>(
        `SELECT id, name, specialties, province, address, official_url,
                source_name, source_url, verified_at
         FROM knowledge.facilities WHERE id = $1 AND ${verifiedFacilityFilter}`,
        [normalizedId],
      );
      const row = result.rows[0];
      return row ? toFacilitySummary(row) : null;
    },
  };
}
