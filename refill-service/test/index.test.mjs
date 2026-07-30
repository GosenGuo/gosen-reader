import assert from "node:assert/strict";
import test from "node:test";
import worker from "../src/index.js";

const env = {
  APP_TRIGGER_TOKEN: "app-only",
  GITHUB_TOKEN: "github-only",
  GITHUB_OWNER: "GosenGuo",
  GITHUB_REPO: "gosen-reader",
  GITHUB_WORKFLOW: "monthly-update.yml",
  GITHUB_REF: "main"
};

test("dispatches exactly 30 articles when no recent refill exists", async () => {
  const originalFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url: String(url), options });
    if (String(url).includes("/runs?")) {
      return Response.json({ workflow_runs: [] });
    }
    return new Response(null, { status: 204 });
  };
  try {
    const response = await worker.fetch(new Request(
      "https://refill.example/refill",
      {
        method: "POST",
        headers: { Authorization: "Bearer app-only" }
      }
    ), env);
    assert.equal(response.status, 202);
    assert.equal(requests.length, 2);
    assert.deepEqual(JSON.parse(requests[1].options.body), {
      ref: "main",
      inputs: { target_count: "30" }
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("does not dispatch again while a refill is running", async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    return Response.json({
      workflow_runs: [{
        status: "in_progress",
        created_at: new Date().toISOString()
      }]
    });
  };
  try {
    const response = await worker.fetch(new Request(
      "https://refill.example/refill",
      {
        method: "POST",
        headers: { Authorization: "Bearer app-only" }
      }
    ), env);
    assert.equal(response.status, 202);
    assert.equal(calls, 1);
    assert.equal((await response.json()).reason, "already_running");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("rejects an invalid app token", async () => {
  const response = await worker.fetch(new Request(
    "https://refill.example/refill",
    { method: "POST" }
  ), env);
  assert.equal(response.status, 401);
});
