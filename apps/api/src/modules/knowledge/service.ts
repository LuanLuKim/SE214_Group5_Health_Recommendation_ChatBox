import type { ArticleSummary, FacilitySummary } from '@health/contracts';
import type { Pool } from 'pg';
import { syntheticArticles, syntheticFacilities } from './synthetic.js';
import type { Config } from '../../config.js';

export interface FacilitySearchParams {
  q?: string | undefined;
  province?: string | undefined;
  specialty?: string | undefined;
}

export function createKnowledgeService(config: Config, pool: Pool | null) {
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
              item.source.name.trim() !== '' &&
              item.source.url.trim() !== '' &&
              Number.isFinite(Date.parse(item.source.verifiedAt)) &&
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
      const result = await pool.query(
        `SELECT id, name, specialties, province, address, official_url,
                source_name, source_url, verified_at
         FROM knowledge.facilities
         WHERE verified_at IS NOT NULL AND btrim(source_name) <> '' AND btrim(source_url) <> ''
           AND ($1 = '' OR strpos(lower(name), lower($1)) > 0)
           AND ($2 = '' OR lower(province) = lower($2))
           AND ($3 = '' OR EXISTS (
             SELECT 1 FROM unnest(specialties) AS specialty(value)
             WHERE lower(specialty.value) = lower($3)
           ))
         ORDER BY name, id LIMIT 20`,
        [query, province, specialty],
      );
      return result.rows.map((row) => ({
        id: row.id as string,
        name: row.name as string,
        specialties: row.specialties as string[],
        province: row.province as string,
        address: row.address as string,
        officialUrl: row.official_url as string,
        source: {
          id: row.id as string,
          name: row.source_name as string,
          url: row.source_url as string,
          verifiedAt: (row.verified_at as Date).toISOString(),
        },
      }));
    },
  };
}
