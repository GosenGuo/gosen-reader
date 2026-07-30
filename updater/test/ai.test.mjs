import assert from "node:assert/strict";
import test from "node:test";
import { AiClient } from "../src/ai.mjs";

test("uses the configured OpenAI-compatible relay and allowed model", async () => {
  const originalFetch = globalThis.fetch;
  let requestedUrl = "";
  let requestedBody;
  globalThis.fetch = async (url, options) => {
    requestedUrl = String(url);
    requestedBody = JSON.parse(options.body);
    return new Response(JSON.stringify({
      choices: [{ message: { content: "{\"ok\":true}" } }]
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  };
  try {
    const client = new AiClient({
      AI_API_KEY: "test-only",
      AI_BASE_URL: "https://xcode.best/v1",
      AI_MODEL: "deepseek-v4-flash"
    });
    assert.deepEqual(await client.json("system", "user"), { ok: true });
    assert.equal(requestedUrl, "https://xcode.best/v1/chat/completions");
    assert.equal(requestedBody.model, "deepseek-v4-flash");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("requires the relay secret", () => {
  assert.throws(() => new AiClient({}), /AI_API_KEY is required/);
});

test("extracts JSON after DeepSeek reasoning text", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({
    choices: [{
      message: {
        content: "<think>I should return [a list] now.</think>\nResult:\n```json\n[\"query one\",\"query two\"]\n```"
      }
    }]
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });
  try {
    const client = new AiClient({ AI_API_KEY: "test-only" });
    assert.deepEqual(await client.json("system", "user"), ["query one", "query two"]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("retries temporary relay throttling without changing the request", async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    if (calls === 1) {
      return new Response("busy", { status: 429 });
    }
    return Response.json({
      choices: [{ message: { content: "{\"ok\":true}" } }]
    });
  };
  try {
    const client = new AiClient({
      AI_API_KEY: "test-key",
      AI_MODEL: "deepseek-v4-flash",
      AI_RETRY_DELAY_MS: "0"
    });
    assert.deepEqual(await client.json("system", "user"), { ok: true });
    assert.equal(calls, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("falls back to the next model after repeated temporary failures", async () => {
  const originalFetch = globalThis.fetch;
  const requestedModels = [];
  globalThis.fetch = async (_url, options) => {
    const body = JSON.parse(options.body);
    requestedModels.push(body.model);
    if (body.model === "deepseek-v4-pro") {
      return new Response("overloaded", { status: 503 });
    }
    return Response.json({
      choices: [{ message: { content: "{\"ok\":true}" } }]
    });
  };
  try {
    const client = new AiClient({
      AI_API_KEY: "test-key",
      AI_MODEL: "deepseek-v4-pro",
      AI_FALLBACK_MODELS: "gemini-2.5-flash",
      AI_RETRY_DELAY_MS: "0"
    });
    assert.deepEqual(await client.json("system", "user"), { ok: true });
    assert.deepEqual(await client.json("system", "user again"), { ok: true });
    assert.equal(client.model, "gemini-2.5-flash");
    assert.deepEqual(requestedModels, [
      "deepseek-v4-pro",
      "deepseek-v4-pro",
      "gemini-2.5-flash",
      "gemini-2.5-flash"
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
