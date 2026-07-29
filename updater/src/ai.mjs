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
    throw new Error("AI relay output was not valid JSON");
  }
}
