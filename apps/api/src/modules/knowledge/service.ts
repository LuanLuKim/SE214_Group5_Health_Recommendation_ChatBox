import type { ArticleSummary, FacilitySummary } from '@health/contracts';
import type { Pool } from 'pg';
import { syntheticArticles, syntheticFacilities } from './synthetic.js';
import type { Config } from '../../config.js';

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
    async listFacilities(query: string): Promise<FacilitySummary[]> {
      if (config.DATA_MODE === 'mock')
        return syntheticFacilities.filter((item) =>
          item.name.toLocaleLowerCase('vi').includes(query.toLocaleLowerCase('vi')),
        );
      if (!pool) throw new Error('DATABASE_NOT_CONFIGURED');
      const result = await pool.query(
        `SELECT * FROM knowledge.facilities WHERE verified_at IS NOT NULL AND name ILIKE $1 ORDER BY name LIMIT 20`,
        [`%${query}%`],
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
