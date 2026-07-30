const WORKFLOW_FILE = "monthly-update.yml";
const REFILL_COOLDOWN_MS = 6 * 60 * 60 * 1000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return Response.json({ ok: true });
    }
    if (request.method !== "POST" || url.pathname !== "/refill") {
      return new Response("Not found", { status: 404 });
    }
    if (!isAuthorized(request, env.APP_TRIGGER_TOKEN)) {
      return new Response("Unauthorized", { status: 401 });
    }

    const repository = `${env.GITHUB_OWNER}/${env.GITHUB_REPO}`;
    const workflow = env.GITHUB_WORKFLOW || WORKFLOW_FILE;
    const headers = {
      "Accept": "application/vnd.github+json",
      "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
      "User-Agent": "gosen-reader-refill-service",
      "X-GitHub-Api-Version": "2022-11-28"
    };
    const runsUrl =
      `https://api.github.com/repos/${repository}/actions/workflows/`
      + `${encodeURIComponent(workflow)}/runs?event=workflow_dispatch&per_page=1`;
    const runsResponse = await fetch(runsUrl, { headers });
    if (!runsResponse.ok) {
      return githubError("run check", runsResponse);
    }
    const latestRun = (await runsResponse.json()).workflow_runs?.[0];
    if (latestRun && shouldSuppress(latestRun)) {
      return Response.json({
        accepted: true,
        dispatched: false,
        reason: latestRun.status === "completed" ? "cooldown" : "already_running"
      }, { status: 202 });
    }

    const dispatchUrl =
      `https://api.github.com/repos/${repository}/actions/workflows/`
      + `${encodeURIComponent(workflow)}/dispatches`;
    const dispatchResponse = await fetch(dispatchUrl, {
      method: "POST",
      headers: { ...headers, "Content-Type": "application/json" },
      body: JSON.stringify({
        ref: env.GITHUB_REF || "main",
        inputs: { target_count: "30" }
      })
    });
    if (!dispatchResponse.ok) {
      return githubError("dispatch", dispatchResponse);
    }
    return Response.json({ accepted: true, dispatched: true }, { status: 202 });
  }
};

function isAuthorized(request, expectedToken) {
  if (!expectedToken) return false;
  return request.headers.get("Authorization") === `Bearer ${expectedToken}`;
}

function shouldSuppress(run) {
  if (run.status === "queued" || run.status === "in_progress") return true;
  const createdAt = Date.parse(run.created_at);
  return Number.isFinite(createdAt)
    && Date.now() - createdAt < REFILL_COOLDOWN_MS;
}

async function githubError(operation, response) {
  const detail = (await response.text()).slice(0, 300);
  return Response.json({
    accepted: false,
    error: `GitHub ${operation} failed`,
    status: response.status,
    detail
  }, { status: 502 });
}
