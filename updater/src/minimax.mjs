const trimSlash = value => value.replace(/\/+$/, "");

export class MiniMaxClient {
  constructor(env = process.env) {
    this.apiKey = env.MINIMAX_API_KEY;
    this.baseUrl = trimSlash(env.MINIMAX_BASE_URL || "https://api.minimaxi.com/v1");
    this.chatUrl = env.MINIMAX_CHAT_URL?.trim()
      || (this.baseUrl.includes("api.minimaxi.com")
        ? `${this.baseUrl}/text/chatcompletion_v2`
        : `${this.baseUrl}/chat/completions`);
    this.model = env.MINIMAX_MODEL || "MiniMax-M2.5";
    this.githubToken = env.GITHUB_MODELS_TOKEN?.trim() || "";
    this.githubModel = env.GITHUB_MODELS_MODEL?.trim() || "openai/gpt-4o-mini";
    if (!this.apiKey) throw new Error("MINIMAX_API_KEY is required");
  }

  async json(system, user, temperature = 0.1) {
    try {
      return await requestJson({
        url: this.chatUrl,
        apiKey: this.apiKey,
        model: this.model,
        system,
        user,
        temperature
      });
    } catch (error) {
      if (!this.githubToken) throw error;
      console.warn(`MiniMax unavailable; using free GitHub Models fallback: ${error.message}`);
      return requestJson({
        url: "https://models.github.ai/inference/chat/completions",
        apiKey: this.githubToken,
        model: this.githubModel,
        system,
        user,
        temperature,
        github: true
      });
    }
  }
}

async function requestJson({
  url,
  apiKey,
  model,
  system,
  user,
  temperature,
  github = false
}) {
    const response = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${apiKey}`,
        ...(github ? {
          "Accept": "application/vnd.github+json",
          "X-GitHub-Api-Version": "2022-11-28"
        } : {})
      },
      body: JSON.stringify({
        model,
        temperature,
        messages: [
          { role: "system", content: system },
          { role: "user", content: user }
        ]
      }),
      signal: AbortSignal.timeout(120_000)
    });
    if (!response.ok) {
      const provider = github ? "GitHub Models" : "MiniMax";
      throw new Error(`${provider} ${response.status}: ${(await response.text()).slice(0, 500)}`);
    }
    const payload = await response.json();
    const content = payload.choices?.[0]?.message?.content;
    if (!content) {
      const code = payload.base_resp?.status_code ?? payload.error?.code ?? "unknown";
      const message = payload.base_resp?.status_msg
        ?? payload.error?.message
        ?? "empty response";
      const provider = github ? "GitHub Models" : "MiniMax";
      throw new Error(`${provider} returned no message content (${code}: ${message})`);
    }
    return parseJson(content);
}

function parseJson(content) {
  const cleaned = String(content)
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/, "")
    .trim();
  try {
    return JSON.parse(cleaned);
  } catch {
    const objectStart = cleaned.indexOf("{");
    const objectEnd = cleaned.lastIndexOf("}");
    const arrayStart = cleaned.indexOf("[");
    const arrayEnd = cleaned.lastIndexOf("]");
    if (arrayStart >= 0 && arrayEnd > arrayStart
        && (objectStart < 0 || arrayStart < objectStart)) {
      return JSON.parse(cleaned.slice(arrayStart, arrayEnd + 1));
    }
    if (objectStart >= 0 && objectEnd > objectStart) {
      return JSON.parse(cleaned.slice(objectStart, objectEnd + 1));
    }
    throw new Error("MiniMax output was not valid JSON");
  }
}
