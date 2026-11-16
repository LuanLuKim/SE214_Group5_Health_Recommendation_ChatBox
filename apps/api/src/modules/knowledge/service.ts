import type { ArticleSummary, FacilitySummary } from '@health/contracts';
import type { Pool } from 'pg';
import { syntheticArticles, syntheticFacilities } from './synthetic.js';
import type { Config } from '../../config.js';

export interface ArticleSearchParams {
  query: string;
  topic: string | undefined;
  limit: number;
  offset: number;
}

export interface ArticleSearchResult {
  items: ArticleSummary[];
  total: number;
}

export function createKnowledgeService(config: Config, pool: Pool | null) {
  return {
    async listArticles({
      query,
      topic,
      limit,
      offset,
    }: ArticleSearchParams): Promise<ArticleSearchResult> {
      if (config.DATA_MODE === 'mock') {
        const normalizedQuery = query.toLocaleLowerCase('vi');
        const normalizedTopic = topic?.toLocaleLowerCase('vi');
        const matches = syntheticArticles
          .filter(
            (item) =>
              (!normalizedQuery ||
                `${item.title} ${item.topic}`.toLocaleLowerCase('vi').includes(normalizedQuery)) &&
              (!normalizedTopic || item.topic.toLocaleLowerCase('vi') === normalizedTopic),
          )
          .sort(
            (left, right) =>
              left.title.localeCompare(right.title, 'vi') || left.id.localeCompare(right.id),
          );
        return { items: matches.slice(offset, offset + limit), total: matches.length };
      }
      if (!pool) throw new Error('DATABASE_NOT_CONFIGURED');
      const filter = `review_status = 'APPROVED' AND verified_at IS NOT NULL
        AND ($1 = '' OR strpos(lower(title), lower($1)) > 0 OR strpos(lower(topic), lower($1)) > 0)
        AND ($2 = '' OR lower(topic) = lower($2))`;
      const values = [query, topic ?? ''];
      const countResult = await pool.query<{ total: number }>(
        `SELECT count(*)::int AS total FROM knowledge.articles WHERE ${filter}`,
        values,
      );
      const result = await pool.query(
        `SELECT id, title, topic, source_name, source_url, verified_at, updated_at
         FROM knowledge.articles WHERE ${filter}
         ORDER BY title, id LIMIT $3 OFFSET $4`,
        [...values, limit, offset],
      );
      const items = result.rows.map((row) => ({
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
      return { items, total: countResult.rows[0]?.total ?? 0 };
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
