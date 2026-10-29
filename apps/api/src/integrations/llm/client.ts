import { z } from 'zod';
import type { Config } from '../../config.js';

export interface LlmMessage {
  role: 'system' | 'user' | 'assistant';
  content: string;
}
const completionSchema = z.object({
  choices: z.array(z.object({ message: z.object({ content: z.string().min(1) }) })).min(1),
});

// Transport adapter only. It is deliberately NOT connected to the public chat route.
// M4 must apply M1's Safety Gate, approved RAG, PII minimization and output checking first.
export function createLlmClient(config: Config, transport: typeof fetch = fetch) {
  return {
    async complete(messages: LlmMessage[]): Promise<string> {
      if (!config.LLM_ENABLED || !config.LLM_API_KEY || !config.LLM_MODEL)
        throw new Error('LLM_NOT_CONFIGURED');
      try {
        const response = await transport(
          `${config.LLM_BASE_URL.replace(/\/$/, '')}/chat/completions`,
          {
            method: 'POST',
            redirect: 'error',
            signal: AbortSignal.timeout(config.LLM_TIMEOUT_MS),
            headers: {
              'Content-Type': 'application/json',
              Authorization: `Bearer ${config.LLM_API_KEY}`,
            },
            body: JSON.stringify({ model: config.LLM_MODEL, messages, stream: false }),
          },
        );
        if (!response.ok) throw new Error('Provider request failed');
        const parsed = completionSchema.parse(await response.json());
        return parsed.choices[0]!.message.content;
      } catch {
        throw new Error('LLM_REQUEST_FAILED');
      }
    },
  };
}
