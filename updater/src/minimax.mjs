const trimSlash = value => value.replace(/\/+$/, "");

export class MiniMaxClient {
  constructor(env = process.env) {
    this.apiKey = env.MINIMAX_API_KEY;
    this.baseUrl = trimSlash(env.MINIMAX_BASE_URL || "https://api.minimax.io/v1");
    this.model = env.MINIMAX_MODEL || "MiniMax-M2.5";
    if (!this.apiKey) throw new Error("MINIMAX_API_KEY is required");
  }

  async json(system, user, temperature = 0.1) {
    const response = await fetch(`${this.baseUrl}/chat/completions`, {
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
      throw new Error(`MiniMax ${response.status}: ${(await response.text()).slice(0, 500)}`);
    }
    const payload = await response.json();
    const content = payload.choices?.[0]?.message?.content;
    if (!content) throw new Error("MiniMax returned no message content");
    return parseJson(content);
  }
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
