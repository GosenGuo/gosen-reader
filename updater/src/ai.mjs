const trimSlash = value => value.replace(/\/+$/, "");

export class AiClient {
  constructor(env = process.env) {
    this.apiKey = env.AI_API_KEY?.trim();
    this.baseUrl = trimSlash(env.AI_BASE_URL || "https://xcode.best/v1");
    this.chatUrl = env.AI_CHAT_URL?.trim() || `${this.baseUrl}/chat/completions`;
    this.models = uniqueModels([
      env.AI_MODEL?.trim() || "deepseek-v4-pro",
      ...String(env.AI_FALLBACK_MODELS || "gemini-2.5-flash")
        .split(",")
        .map(value => value.trim())
    ]);
    this.model = this.models[0];
    this.activeModelIndex = 0;
    this.retryDelayMs = Number(env.AI_RETRY_DELAY_MS ?? 15_000);
    this.requestTimeoutMs = Number(env.AI_REQUEST_TIMEOUT_MS ?? 90_000);
    this.maxAttemptsPerModel = Math.max(
      1,
      Number(env.AI_MAX_ATTEMPTS_PER_MODEL ?? 2)
    );
    if (!this.apiKey) throw new Error("AI_API_KEY is required");
  }

  async json(system, user, temperature = 0.1) {
    let lastError;
    for (
      let modelIndex = this.activeModelIndex;
      modelIndex < this.models.length;
      modelIndex += 1
    ) {
      const model = this.models[modelIndex];
      try {
        const result = await this.jsonWithModel(model, system, user, temperature);
        this.activeModelIndex = modelIndex;
        this.model = model;
        return result;
      } catch (error) {
        lastError = error;
        const fallback = this.models[modelIndex + 1];
        if (!error.retryable || !fallback) throw error;
        console.warn(`AI model ${model} is temporarily unavailable; falling back to ${fallback}`);
      }
    }
    throw lastError || new Error("AI model fallback loop ended unexpectedly");
  }

  async jsonWithModel(model, system, user, temperature) {
    for (let attempt = 1; attempt <= this.maxAttemptsPerModel; attempt += 1) {
      let response;
      try {
        response = await fetch(this.chatUrl, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Authorization": `Bearer ${this.apiKey}`
          },
          body: JSON.stringify({
            model,
            temperature,
            messages: [
              { role: "system", content: system },
              { role: "user", content: user }
            ]
          }),
          signal: AbortSignal.timeout(this.requestTimeoutMs)
        });
      } catch (error) {
        const relayError = new AiRelayError(
          `AI relay network error for ${model}: ${error.message}`,
          true
        );
        if (attempt === this.maxAttemptsPerModel) throw relayError;
        await retryDelay(this.retryDelayMs, attempt, `AI relay network error for ${model}`);
        continue;
      }
      if (!response.ok) {
        const detail = (await response.text()).slice(0, 500);
        const retryable = isRetryableStatus(response.status);
        const relayError = new AiRelayError(
          `AI relay ${response.status} for ${model}: ${detail}`,
          retryable
        );
        if (retryable && attempt < this.maxAttemptsPerModel) {
          const retryAfter = Number(response.headers.get("retry-after")) * 1000;
          const delay = Number.isFinite(retryAfter) && retryAfter > 0
            ? retryAfter
            : this.retryDelayMs * attempt;
          console.warn(`AI relay ${response.status} for ${model}; retrying in ${delay} ms`);
          await sleep(delay);
          continue;
        }
        throw relayError;
      }
      const payload = await response.json();
      const content = payload.choices?.[0]?.message?.content;
      if (!content) {
        const message = payload.error?.message || "empty response";
        const relayError = new AiRelayError(
          `AI relay returned no message content for ${model}: ${message}`,
          true
        );
        if (attempt === this.maxAttemptsPerModel) throw relayError;
        await retryDelay(this.retryDelayMs, attempt, `AI relay returned no content for ${model}`);
        continue;
      }
      try {
        return parseJson(content);
      } catch (error) {
        const relayError = new AiRelayError(
          `AI relay output from ${model} was not valid JSON`,
          true
        );
        if (attempt === this.maxAttemptsPerModel) throw relayError;
        await retryDelay(this.retryDelayMs, attempt, `Invalid JSON from ${model}`);
      }
    }
    throw new Error("AI relay retry loop ended unexpectedly");
  }
}

class AiRelayError extends Error {
  constructor(message, retryable) {
    super(message);
    this.name = "AiRelayError";
    this.retryable = retryable;
  }
}

const uniqueModels = models => [...new Set(models.filter(Boolean))];
const isRetryableStatus = status =>
  [408, 425, 429, 500, 502, 503, 504, 520, 522, 524, 529].includes(status);

async function retryDelay(baseDelay, attempt, reason) {
  const delay = Math.max(0, baseDelay * attempt);
  console.warn(`${reason}; retrying in ${delay} ms`);
  await sleep(delay);
}

const sleep = delay => new Promise(resolve => setTimeout(resolve, delay));

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
