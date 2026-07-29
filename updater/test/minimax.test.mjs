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
