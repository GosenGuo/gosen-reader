export class WebSearchClient {
  constructor(env = process.env) {
    this.apiKey = env.SEARCH_API_KEY;
    this.githubToken = env.GITHUB_SEARCH_TOKEN;
    this.url = env.SEARCH_API_URL || "https://api.bochaai.com/v1/web-search";
    this.provider = env.SEARCH_PROVIDER || (this.githubToken ? "github-code" : (this.apiKey ? "api" : "so-html"));
  }

  async search(query, count = 10) {
    if (this.provider === "github-code") {
      return this.searchGithubCode(query, count);
    }
    if (this.provider === "so-html") {
      return this.searchSoHtml(query, count);
    }
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

  async searchGithubCode(query, count) {
    if (!this.githubToken) {
      throw new Error("GITHUB_SEARCH_TOKEN is required when SEARCH_PROVIDER=github-code");
    }
    const url = `https://api.github.com/search/code?q=${encodeURIComponent(query)}&per_page=${Math.min(count, 100)}`;
    const response = await fetch(url, {
      headers: {
        "Accept": "application/vnd.github+json",
        "Authorization": `Bearer ${this.githubToken}`,
        "User-Agent": "GosenReaderBot/0.3",
        "X-GitHub-Api-Version": "2022-11-28"
      },
      signal: AbortSignal.timeout(30_000)
    });
    if (!response.ok) {
      throw new Error(`GitHub code search ${response.status}: ${(await response.text()).slice(0, 300)}`);
    }
    const payload = await response.json();
    return (payload.items || []).map(item => {
      const fullName = item.repository?.full_name || "";
      const branch = item.repository?.default_branch || "main";
      const encodedPath = String(item.path || "")
        .split("/")
        .map(encodeURIComponent)
        .join("/");
      return {
        title: item.name || item.path || "",
        url: item.html_url || "",
        fetchUrl: fullName && encodedPath
          ? `https://raw.githubusercontent.com/${fullName}/${encodeURIComponent(branch)}/${encodedPath}`
          : "",
        snippet: `${fullName}/${item.path || ""}`
      };
    }).filter(item => /^https?:\/\//.test(item.url) && /^https?:\/\//.test(item.fetchUrl));
  }

  async searchBingRss(query, count) {
    const headers = {
      "User-Agent": "Mozilla/5.0 (compatible; GosenReaderBot/0.2; personal study)",
      "Accept": "application/rss+xml,application/xml,text/html;q=0.9"
    };
    const rssUrl = `https://www.bing.com/search?format=rss&mkt=zh-CN&setlang=zh-hans&q=${encodeURIComponent(query)}`;
    const response = await fetch(rssUrl, {
      headers,
      signal: AbortSignal.timeout(30_000)
    });
    if (!response.ok) {
      throw new Error(`Bing RSS ${response.status}: ${(await response.text()).slice(0, 300)}`);
    }
    const xml = await response.text();
    const rssResults = parseBingRss(xml, count);
    if (rssResults.length) return rssResults;

    const htmlUrl = `https://www.bing.com/search?mkt=zh-CN&setlang=zh-hans&q=${encodeURIComponent(query)}`;
    const htmlResponse = await fetch(htmlUrl, {
      headers: {
        ...headers,
        "Accept": "text/html,application/xhtml+xml"
      },
      signal: AbortSignal.timeout(30_000)
    });
    if (!htmlResponse.ok) {
      throw new Error(`Bing HTML ${htmlResponse.status}: ${(await htmlResponse.text()).slice(0, 300)}`);
    }
    return parseBingHtml(await htmlResponse.text(), count);
  }

  async searchSoHtml(query, count) {
    const url = `https://www.so.com/s?q=${encodeURIComponent(query)}`;
    const response = await fetch(url, {
      headers: {
        "User-Agent": "Mozilla/5.0 (compatible; GosenReaderBot/0.2; personal study)",
        "Accept": "text/html,application/xhtml+xml"
      },
      signal: AbortSignal.timeout(30_000)
    });
    if (!response.ok) {
      throw new Error(`360 Search ${response.status}: ${(await response.text()).slice(0, 300)}`);
    }
    return parseSoHtml(await response.text(), count);
  }
}

export async function downloadReadablePage(candidate) {
  const fetchUrl = candidate.fetchUrl || candidate.url;
  if (/\.pdf(?:$|\?)/i.test(fetchUrl)) return null;
  const response = await fetch(fetchUrl, {
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
    .replace(/&nbsp;/gi, " ")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&amp;/gi, "&")
    .replace(/\s+/g, " ")
    .trim();
}

function parseBingRss(xml, count) {
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

function parseBingHtml(html, count) {
  return [...html.matchAll(/<li\b[^>]*class=["'][^"']*\bb_algo\b[^"']*["'][^>]*>([\s\S]*?)<\/li>/gi)]
    .map(match => {
      const item = match[1];
      const heading = /<h2\b[^>]*>([\s\S]*?)<\/h2>/i.exec(item)?.[1] || "";
      const anchor = /<a\b[^>]*href=["'](https?:\/\/[^"']+)["'][^>]*>([\s\S]*?)<\/a>/i.exec(heading);
      const caption = /<div\b[^>]*class=["'][^"']*\bb_caption\b[^"']*["'][^>]*>([\s\S]*?)<\/div>/i.exec(item)?.[1] || "";
      return {
        title: cleanHtmlText(anchor?.[2] || ""),
        url: decodeHtml(anchor?.[1] || ""),
        snippet: cleanHtmlText(caption)
      };
    })
    .filter(item => /^https?:\/\//.test(item.url) && item.title)
    .slice(0, count);
}

function parseSoHtml(html, count) {
  return [...html.matchAll(/<li\b[^>]*class=["'][^"']*\bres-list\b[^"']*["'][^>]*>([\s\S]*?)<\/li>/gi)]
    .map(match => {
      const item = match[1];
      const heading = /<h3\b[^>]*>([\s\S]*?)<\/h3>/i.exec(item)?.[1] || "";
      const anchor = /<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/i.exec(heading);
      const description = /<(?:p|div)\b[^>]*class=["'][^"']*(?:res-desc|mh-desc)[^"']*["'][^>]*>([\s\S]*?)<\/(?:p|div)>/i
        .exec(item)?.[1] || "";
      const rawUrl = decodeHtml(anchor?.[1] || "");
      const url = rawUrl.startsWith("//") ? `https:${rawUrl}` : rawUrl;
      return {
        title: cleanHtmlText(anchor?.[2] || ""),
        url,
        snippet: cleanHtmlText(description)
      };
    })
    .filter(item => /^https?:\/\//.test(item.url) && isRelevantExamResult(item))
    .slice(0, count);
}

function isRelevantExamResult(item) {
  if (/ai\.so\.com/i.test(item.url)) return false;
  const text = `${item.title} ${item.snippet}`;
  return /(英语|english)/i.test(text)
    && /(阅读|试题|真题|答案|模拟|高考|联考|期中|期末)/i.test(text);
}

function cleanHtmlText(value) {
  return decodeHtml(String(value).replace(/<[^>]+>/g, " "))
    .replace(/\s+/g, " ")
    .trim();
}

function decodeHtml(value) {
  return String(value)
    .replace(/&#(\d+);/g, (_, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-f]+);/gi, (_, code) => String.fromCodePoint(parseInt(code, 16)))
    .replace(/&nbsp;/gi, " ")
    .replace(/&quot;/gi, "\"")
    .replace(/&apos;|&#39;/gi, "'")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&amp;/gi, "&");
}
