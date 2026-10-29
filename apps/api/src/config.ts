import { z } from 'zod';

const environmentSchema = z.object({
  HOST: z.string().default('127.0.0.1'),
  PORT: z.coerce.number().int().min(1).max(65535).default(4000),
  WEB_ORIGIN: z.url().default('http://localhost:5173'),
  DATABASE_URL: z.string().min(1).optional(),
  DATA_MODE: z.enum(['mock', 'postgres']).default('mock'),
  LLM_ENABLED: z
    .enum(['true', 'false'])
    .default('false')
    .transform((value) => value === 'true'),
  LLM_BASE_URL: z
    .url()
    .default('https://api.openai.com/v1')
    .refine((value) => {
      const url = new URL(value);
      return (
        url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash
      );
    }),
  LLM_API_KEY: z.string().default(''),
  LLM_MODEL: z.string().default(''),
  LLM_TIMEOUT_MS: z.coerce.number().int().min(100).max(120000).default(20000),
});

export function loadConfig(environment: NodeJS.ProcessEnv = process.env) {
  const result = environmentSchema.safeParse(environment);
  if (!result.success)
    throw new Error('Invalid environment configuration. Check the .env.example fields.');
  return result.data;
}
export type Config = ReturnType<typeof loadConfig>;
