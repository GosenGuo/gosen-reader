import assert from "node:assert/strict";
import test from "node:test";
import { MiniMaxClient } from "../src/minimax.mjs";

test("uses the domestic MiniMax text endpoint for a minimaxi.com key", async () => {
  const originalFetch = globalThis.fetch;
  let requestedUrl = "";
  globalThis.fetch = async url => {
    requestedUrl = String(url);
    return new Response(JSON.stringify({
      choices: [{ message: { content: "{\"ok\":true}" } }]
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  };
  try {
    const client = new MiniMaxClient({
      MINIMAX_API_KEY: "test-only",
      MINIMAX_BASE_URL: "https://api.minimaxi.com/v1"
    });
    assert.deepEqual(await client.json("system", "user"), { ok: true });
    assert.equal(requestedUrl, "https://api.minimaxi.com/v1/text/chatcompletion_v2");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("keeps the OpenAI-compatible endpoint for minimax.io", () => {
  const client = new MiniMaxClient({
    MINIMAX_API_KEY: "test-only",
    MINIMAX_BASE_URL: "https://api.minimax.io/v1"
  });
  assert.equal(client.chatUrl, "https://api.minimax.io/v1/chat/completions");
});

test("falls back to free GitHub Models when the MiniMax plan is exhausted", async () => {
  const originalFetch = globalThis.fetch;
  const urls = [];
  globalThis.fetch = async url => {
    urls.push(String(url));
    if (urls.length === 1) {
      return new Response(JSON.stringify({
        base_resp: { status_code: 2056, status_msg: "plan limit reached" }
      }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response(JSON.stringify({
      choices: [{ message: { content: "{\"fallback\":true}" } }]
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  };
  try {
    const client = new MiniMaxClient({
      MINIMAX_API_KEY: "test-only",
      GITHUB_MODELS_TOKEN: "github-test-only"
    });
    assert.deepEqual(await client.json("system", "user"), { fallback: true });
    assert.deepEqual(urls, [
      "https://api.minimaxi.com/v1/text/chatcompletion_v2",
      "https://models.github.ai/inference/chat/completions"
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
