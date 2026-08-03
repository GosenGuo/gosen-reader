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
    this.primaryRetryCooldownMs = Math.max(
      0,
      Number(env.AI_PRIMARY_RETRY_COOLDOWN_MS ?? 300_000)
    );
    this.primaryRetryAt = 0;
    this.pricing = parsePricing(env.AI_MODEL_PRICING_JSON);
    this.usdCnyRate = positiveNumber(env.USD_CNY_RATE);
    this.reportedCostCurrency =
      env.AI_REPORTED_COST_CURRENCY?.trim() || "provider_units";
    this.usageEntries = [];
    if (!this.apiKey) throw new Error("AI_API_KEY is required");
  }

  usageSnapshot() {
    return this.usageEntries.length;
  }

  usageSince(snapshot = 0) {
    return summarizeUsage(this.usageEntries.slice(snapshot), this.usdCnyRate);
  }

  async json(system, user, temperature = 0.1) {
    let lastError;
    const startModelIndex = this.activeModelIndex > 0
      && Date.now() < this.primaryRetryAt
      ? this.activeModelIndex
      : 0;
    for (
      let modelIndex = startModelIndex;
      modelIndex < this.models.length;
      modelIndex += 1
    ) {
      const model = this.models[modelIndex];
      try {
        const result = await this.jsonWithModel(model, system, user, temperature);
        this.activeModelIndex = modelIndex;
        this.model = model;
        this.primaryRetryAt = modelIndex === 0
          ? 0
          : Date.now() + this.primaryRetryCooldownMs;
        return result;
      } catch (error) {
        lastError = error;
        const fallback = this.models[modelIndex + 1];
        if (!error.retryable || !fallback) throw error;
        console.warn(`${error.message}; falling back to ${fallback}`);
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
      this.recordUsage(model, payload);
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

  recordUsage(model, payload) {
    const usage = payload?.usage;
    if (!usage || typeof usage !== "object") return;
    const inputTokens = tokenNumber(usage.prompt_tokens ?? usage.input_tokens);
    const outputTokens = tokenNumber(usage.completion_tokens ?? usage.output_tokens);
    const totalTokens = tokenNumber(
      usage.total_tokens ?? inputTokens + outputTokens
    );
    const rate = this.pricing[model];
    const estimatedCostUsd = rate
      ? (inputTokens * rate.inputUsdPerMillion
        + outputTokens * rate.outputUsdPerMillion) / 1_000_000
      : null;
    const reportedCost = optionalNumber(
      usage.cost
      ?? usage.total_cost
      ?? payload.total_cost
      ?? payload.cost
    );
    this.usageEntries.push({
      model,
      inputTokens,
      outputTokens,
      totalTokens,
      reportedCost,
      reportedCostCurrency: reportedCost == null
        ? null
        : String(usage.currency || payload.currency || this.reportedCostCurrency),
      estimatedCostUsd,
      pricing: rate || null
    });
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
const tokenNumber = value => {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : 0;
};
const positiveNumber = value => {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : null;
};
const optionalNumber = value => {
  if (value == null || value === "") return null;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : null;
};

function parsePricing(raw) {
  if (!raw?.trim()) return {};
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new Error("AI_MODEL_PRICING_JSON must be valid JSON");
  }
  const pricing = {};
  for (const [model, value] of Object.entries(parsed)) {
    const inputUsdPerMillion = positiveNumber(value?.inputUsdPerMillion);
    const outputUsdPerMillion = positiveNumber(value?.outputUsdPerMillion);
    if (!inputUsdPerMillion || !outputUsdPerMillion) {
      throw new Error(
        `AI_MODEL_PRICING_JSON has invalid input/output rates for ${model}`
      );
    }
    pricing[model] = { inputUsdPerMillion, outputUsdPerMillion };
  }
  return pricing;
}

function summarizeUsage(entries, usdCnyRate) {
  const summary = {
    requests: entries.length,
    inputTokens: 0,
    outputTokens: 0,
    totalTokens: 0,
    estimatedCostUsd: null,
    estimatedCostCny: null,
    reportedCosts: {},
    pricingComplete: entries.length > 0
  };
  let estimatedCostUsd = 0;
  const models = {};
  for (const entry of entries) {
    summary.inputTokens += entry.inputTokens;
    summary.outputTokens += entry.outputTokens;
    summary.totalTokens += entry.totalTokens;
    if (entry.estimatedCostUsd == null) summary.pricingComplete = false;
    else estimatedCostUsd += entry.estimatedCostUsd;
    if (entry.reportedCost != null) {
      const currency = entry.reportedCostCurrency || "provider_units";
      summary.reportedCosts[currency] =
        roundMoney((summary.reportedCosts[currency] || 0) + entry.reportedCost);
    }
    const model = models[entry.model] ||= {
      requests: 0,
      inputTokens: 0,
      outputTokens: 0,
      totalTokens: 0,
      estimatedCostUsd: null
    };
    model.requests += 1;
    model.inputTokens += entry.inputTokens;
    model.outputTokens += entry.outputTokens;
    model.totalTokens += entry.totalTokens;
    if (entry.estimatedCostUsd != null) {
      model.estimatedCostUsd = (model.estimatedCostUsd || 0) + entry.estimatedCostUsd;
    }
  }
  if (summary.pricingComplete && entries.length > 0) {
    summary.estimatedCostUsd = roundMoney(estimatedCostUsd);
    if (usdCnyRate) {
      summary.estimatedCostCny = roundMoney(estimatedCostUsd * usdCnyRate);
    }
  }
  summary.models = models;
  return summary;
}

const roundMoney = value => Math.round(value * 100_000_000) / 100_000_000;
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
