import test from 'node:test';
import assert from 'node:assert/strict';
import { loadConfig } from '../src/config.js';
import { createLlmClient } from '../src/integrations/llm/client.js';

test('LLM defaults to disabled and makes no network request', async () => {
  let called = false;
  const transport: typeof fetch = async () => {
    called = true;
    throw new Error('Unexpected call');
  };
  await assert.rejects(
    createLlmClient(loadConfig({}), transport).complete([{ role: 'user', content: 'Synthetic' }]),
    /LLM_NOT_CONFIGURED/,
  );
  assert.equal(called, false);
});
for (const base of [
  'https://api.openai.com/v1',
  'https://generativelanguage.googleapis.com/v1beta/openai/',
]) {
  test(`OpenAI-compatible request works with ${new URL(base).hostname}`, async () => {
    const transport: typeof fetch = async (input, init) => {
      assert.equal(String(input), `${base.replace(/\/$/, '')}/chat/completions`);
      assert.equal(
        (init?.headers as Record<string, string>).Authorization,
        'Bearer synthetic-test-key',
      );
      const body = JSON.parse(String(init?.body)) as { model: string; stream: boolean };
      assert.equal(body.model, 'synthetic-test-model');
      assert.equal(body.stream, false);
      return Response.json({ choices: [{ message: { content: 'Synthetic response' } }] });
    };
    const config = loadConfig({
      LLM_ENABLED: 'true',
      LLM_API_KEY: 'synthetic-test-key',
      LLM_MODEL: 'synthetic-test-model',
      LLM_BASE_URL: base,
    });
    assert.equal(
      await createLlmClient(config, transport).complete([{ role: 'user', content: 'Synthetic' }]),
      'Synthetic response',
    );
  });
}
test('provider failures and malformed output do not leak provider details', async () => {
  const config = loadConfig({
    LLM_ENABLED: 'true',
    LLM_API_KEY: 'synthetic-test-key',
    LLM_MODEL: 'synthetic-test-model',
  });
  const badStatus: typeof fetch = async () =>
    new Response('sensitive-provider-body', { status: 429 });
  await assert.rejects(createLlmClient(config, badStatus).complete([]), {
    message: 'LLM_REQUEST_FAILED',
  });
  const malformed: typeof fetch = async () => Response.json({ choices: [] });
  await assert.rejects(createLlmClient(config, malformed).complete([]), {
    message: 'LLM_REQUEST_FAILED',
  });
  assert.throws(() => loadConfig({ LLM_BASE_URL: 'https://user:secret@example.invalid/v1' }));
});
