const trimSlash = value => value.replace(/\/+$/, "");

export class AiClient {
  constructor(env = process.env) {
    this.apiKey = env.AI_API_KEY?.trim();
    this.baseUrl = trimSlash(env.AI_BASE_URL || "https://xcode.best/v1");
    this.chatUrl = env.AI_CHAT_URL?.trim() || `${this.baseUrl}/chat/completions`;
    this.model = env.AI_MODEL?.trim() || "deepseek-v4-flash";
    if (!this.apiKey) throw new Error("AI_API_KEY is required");
  }

  async json(system, user, temperature = 0.1) {
    const response = await fetch(this.chatUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${this.apiKey}`
      },
      body: JSON.stringify({
        model: this.model,
        temperature,
        messages: [
          { role: "system", content: system },
          { role: "user", content: user }
        ]
      }),
      signal: AbortSignal.timeout(120_000)
    });
    if (!response.ok) {
      throw new Error(`AI relay ${response.status}: ${(await response.text()).slice(0, 500)}`);
    }
    const payload = await response.json();
    const content = payload.choices?.[0]?.message?.content;
    if (!content) {
      const message = payload.error?.message || "empty response";
      throw new Error(`AI relay returned no message content: ${message}`);
    }
    return parseJson(content);
  }
}

function parseJson(content) {
  const cleaned = String(content)
    .replace(/<think>[\s\S]*?<\/think>/gi, "")
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/, "")
    .trim();
  try {
    return JSON.parse(cleaned);
  } catch {
    for (const candidate of balancedJsonCandidates(cleaned)) {
      try {
        return JSON.parse(candidate);
      } catch {
        // Keep scanning past prose brackets and incomplete fragments.
      }
    }
    throw new Error("AI relay output was not valid JSON");
  }
}

function balancedJsonCandidates(text) {
  const candidates = [];
  for (let start = 0; start < text.length; start++) {
    const first = text[start];
    if (first !== "{" && first !== "[") continue;
    const stack = [first];
    let quoted = false;
    let escaped = false;
    for (let index = start + 1; index < text.length; index++) {
      const character = text[index];
      if (quoted) {
        if (escaped) escaped = false;
        else if (character === "\\") escaped = true;
        else if (character === "\"") quoted = false;
        continue;
      }
      if (character === "\"") {
        quoted = true;
      } else if (character === "{" || character === "[") {
        stack.push(character);
      } else if (character === "}" || character === "]") {
        const expected = character === "}" ? "{" : "[";
        if (stack.pop() !== expected) break;
        if (stack.length === 0) {
          candidates.push(text.slice(start, index + 1));
          break;
        }
      }
    }
  }
  return candidates;
}
