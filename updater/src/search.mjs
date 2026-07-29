export class WebSearchClient {
  constructor(env = process.env) {
    this.apiKey = env.SEARCH_API_KEY;
    this.url = env.SEARCH_API_URL || "https://api.bochaai.com/v1/web-search";
    this.provider = env.SEARCH_PROVIDER || (this.apiKey ? "api" : "bing-rss");
  }

  async search(query, count = 10) {
    if (this.provider === "bing-rss") {
      return this.searchBingRss(query, count);
    }
    if (!this.apiKey) {
      throw new Error("SEARCH_API_KEY is required when SEARCH_PROVIDER=api");
    }
    const response = await fetch(this.url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${this.apiKey}`
      },
      body: JSON.stringify({
        query,
        freshness: "noLimit",
        summary: true,
        count
      }),
      signal: AbortSignal.timeout(30_000)
    });
    if (!response.ok) {
      throw new Error(`Search ${response.status}: ${(await response.text()).slice(0, 300)}`);
    }
    const payload = await response.json();
    const values = payload?.data?.webPages?.value
      || payload?.webPages?.value
      || payload?.results
      || [];
    return values.map(item => ({
      title: item.name || item.title || "",
      url: item.url || item.link || "",
      snippet: item.summary || item.snippet || ""
    })).filter(item => /^https?:\/\//.test(item.url));
  }

  async searchBingRss(query, count) {
    const url = `https://cn.bing.com/search?format=rss&q=${encodeURIComponent(query)}`;
    const response = await fetch(url, {
      headers: {
        "User-Agent": "Mozilla/5.0 (compatible; GosenReaderBot/0.2; personal study)",
        "Accept": "application/rss+xml,application/xml,text/xml"
      },
      signal: AbortSignal.timeout(30_000)
    });
    if (!response.ok) {
      throw new Error(`Bing RSS ${response.status}: ${(await response.text()).slice(0, 300)}`);
    }
    const xml = await response.text();
    return [...xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)]
      .slice(0, count)
      .map(match => {
        const item = match[1];
        return {
          title: readXmlTag(item, "title"),
          url: readXmlTag(item, "link"),
          snippet: readXmlTag(item, "description")
        };
      })
      .filter(item => /^https?:\/\//.test(item.url));
  }
}

export async function downloadReadablePage(candidate) {
  if (/\.pdf(?:$|\?)/i.test(candidate.url)) return null;
  const response = await fetch(candidate.url, {
    headers: {
      "User-Agent": "Mozilla/5.0 (compatible; GosenReaderBot/0.1; personal study)",
      "Accept": "text/html,text/plain;q=0.9"
    },
    redirect: "follow",
    signal: AbortSignal.timeout(20_000)
  });
  if (!response.ok) return null;
  const type = response.headers.get("content-type") || "";
  if (!/html|text/i.test(type)) return null;
  const buffer = await response.arrayBuffer();
  const charset = /charset=([^;\s]+)/i.exec(type)?.[1]?.replace(/["']/g, "") || "utf-8";
  let html;
  try {
    html = new TextDecoder(charset).decode(buffer);
  } catch {
    html = new TextDecoder("utf-8").decode(buffer);
  }
  const text = cleanHtml(html);
  if (text.length < 500) return null;
  return {
    ...candidate,
    text: text.slice(0, 50_000)
  };
}

function cleanHtml(html) {
  return html
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, " ")
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, " ")
    .replace(/<noscript\b[^>]*>[\s\S]*?<\/noscript>/gi, " ")
    .replace(/<!--[\s\S]*?-->/g, " ")
    .replace(/<\/(?:p|div|li|h[1-6]|tr|br)>/gi, "\n")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, "\"")
    .replace(/&#39;/gi, "'")
    .replace(/[ \t]+/g, " ")
    .replace(/\n\s*\n\s*\n+/g, "\n\n")
    .trim();
}

function readXmlTag(xml, tag) {
  const value = new RegExp(`<${tag}>([\\s\\S]*?)<\\/${tag}>`, "i")
    .exec(xml)?.[1] || "";
  return value
    .replace(/^<!\[CDATA\[|\]\]>$/g, "")
    .replace(/<[^>]+>/g, " ")
    .replace(/&#(\d+);/g, (_, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-f]+);/gi, (_, code) => String.fromCodePoint(parseInt(code, 16)))
    .replace(/&quot;/gi, "\"")
    .replace(/&apos;|&#39;/gi, "'")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&amp;/gi, "&")
    .replace(/\s+/g, " ")
    .trim();
}
