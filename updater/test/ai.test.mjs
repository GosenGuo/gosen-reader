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

test("records token usage and estimates per-model cost", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json({
    choices: [{ message: { content: "{\"ok\":true}" } }],
    usage: {
      prompt_tokens: 2_000,
      completion_tokens: 500,
      total_tokens: 2_500
    }
  });
  try {
    const client = new AiClient({
      AI_API_KEY: "test-only",
      AI_MODEL: "test-model",
      AI_MODEL_PRICING_JSON: JSON.stringify({
        "test-model": {
          inputUsdPerMillion: 1,
          outputUsdPerMillion: 4
        }
      }),
      USD_CNY_RATE: "7.2"
    });
    const before = client.usageSnapshot();
    await client.json("system", "user");
    assert.deepEqual(client.usageSince(before), {
      requests: 1,
      inputTokens: 2_000,
      outputTokens: 500,
      totalTokens: 2_500,
      estimatedCostUsd: 0.004,
      estimatedCostCny: 0.0288,
      reportedCosts: {},
      pricingComplete: true,
      models: {
        "test-model": {
          requests: 1,
          inputTokens: 2_000,
          outputTokens: 500,
          totalTokens: 2_500,
          estimatedCostUsd: 0.004,
          estimatedCostCny: 0.0288
        }
      }
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("tracks relay-native CNY pricing across shared strict and bulk clients", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (_url, options) => {
    const { model } = JSON.parse(options.body);
    const usage = model === "gemini-2.5-flash"
      ? { prompt_tokens: 1_000, completion_tokens: 500, total_tokens: 1_500 }
      : { prompt_tokens: 2_000, completion_tokens: 500, total_tokens: 2_500 };
    return Response.json({
      choices: [{ message: { content: "{\"ok\":true}" } }],
      usage
    });
  };
  try {
    const pricing = JSON.stringify({
      "gemini-2.5-flash": {
        inputCnyPerMillion: 0.3,
        outputCnyPerMillion: 2.5
      },
      "claude-haiku-4-5-20251001": {
        inputCnyPerMillion: 0.028,
        outputCnyPerMillion: 0.14
      }
    });
    const strict = new AiClient({
      AI_API_KEY: "test-only",
      AI_MODEL: "gemini-2.5-flash",
      AI_MODEL_PRICING_CNY_JSON: pricing
    });
    const bulk = new AiClient({
      AI_API_KEY: "test-only",
      AI_MODEL: "claude-haiku-4-5-20251001",
      AI_MODEL_PRICING_CNY_JSON: pricing
    }, { usageEntries: strict.usageEntries });
    await strict.json("system", "strict");
    await bulk.json("system", "bulk");
    const usage = strict.usageSince(0);
    assert.equal(usage.requests, 2);
    assert.equal(usage.totalTokens, 4_000);
    assert.equal(usage.estimatedCostUsd, null);
    assert.equal(usage.estimatedCostCny, 0.001676);
    assert.equal(usage.pricingComplete, true);
    assert.equal(usage.models["gemini-2.5-flash"].estimatedCostCny, 0.00155);
    assert.equal(
      usage.models["claude-haiku-4-5-20251001"].estimatedCostCny,
      0.000126
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("keeps a provider-reported charge without guessing its currency", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json({
    choices: [{ message: { content: "{\"ok\":true}" } }],
    usage: {
      prompt_tokens: 10,
      completion_tokens: 5,
      total_tokens: 15,
      cost: 0.0025
    }
  });
  try {
    const client = new AiClient({
      AI_API_KEY: "test-only",
      AI_MODEL: "test-model",
      AI_REPORTED_COST_CURRENCY: "CNY"
    });
    await client.json("system", "user");
    const usage = client.usageSince(0);
    assert.deepEqual(usage.reportedCosts, { CNY: 0.0025 });
    assert.equal(usage.estimatedCostUsd, null);
    assert.equal(usage.pricingComplete, false);
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

test("retries the primary model after the fallback cooldown", async () => {
  const originalFetch = globalThis.fetch;
  const requestedModels = [];
  let primaryCalls = 0;
  globalThis.fetch = async (_url, options) => {
    const body = JSON.parse(options.body);
    requestedModels.push(body.model);
    if (body.model === "deepseek-v4-flash" && primaryCalls++ === 0) {
      return new Response("temporarily overloaded", { status: 503 });
    }
    return Response.json({
      choices: [{ message: { content: "{\"ok\":true}" } }]
    });
  };
  try {
    const client = new AiClient({
      AI_API_KEY: "test-key",
      AI_MODEL: "deepseek-v4-flash",
      AI_FALLBACK_MODELS: "gemini-2.5-flash",
      AI_MAX_ATTEMPTS_PER_MODEL: "1",
      AI_PRIMARY_RETRY_COOLDOWN_MS: "0",
      AI_RETRY_DELAY_MS: "0"
    });
    assert.deepEqual(await client.json("system", "first"), { ok: true });
    assert.deepEqual(await client.json("system", "second"), { ok: true });
    assert.deepEqual(requestedModels, [
      "deepseek-v4-flash",
      "gemini-2.5-flash",
      "deepseek-v4-flash"
    ]);
    assert.equal(client.model, "deepseek-v4-flash");
  } finally {
    globalThis.fetch = originalFetch;
  }
});
