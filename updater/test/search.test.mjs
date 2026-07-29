import assert from "node:assert/strict";
import test from "node:test";
import { WebSearchClient, downloadReadablePage } from "../src/search.mjs";

test("uses authenticated GitHub code search and constructs raw download URLs", async () => {
  const originalFetch = globalThis.fetch;
  let request;
  globalThis.fetch = async (url, options) => {
    request = { url: String(url), options };
    return Response.json({
      items: [{
        name: "exam.md",
        path: "题库/exam.md",
        html_url: "https://github.com/example/exams/blob/main/%E9%A2%98%E5%BA%93/exam.md",
        repository: {
          full_name: "example/exams",
          default_branch: "main"
        }
      }]
    });
  };
  try {
    const client = new WebSearchClient({
      SEARCH_PROVIDER: "github-code",
      GITHUB_SEARCH_TOKEN: "test-token"
    });
    assert.deepEqual(await client.search('"阅读理解" extension:md', 5), [{
      title: "exam.md",
      url: "https://github.com/example/exams/blob/main/%E9%A2%98%E5%BA%93/exam.md",
      fetchUrl: "https://raw.githubusercontent.com/example/exams/main/%E9%A2%98%E5%BA%93/exam.md",
      snippet: "example/exams/题库/exam.md"
    }]);
    assert.match(request.url, /api\.github\.com\/search\/code/);
    assert.equal(request.options.headers.Authorization, "Bearer test-token");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("downloads candidate raw content while keeping its public source URL", async () => {
  const originalFetch = globalThis.fetch;
  let fetchedUrl;
  globalThis.fetch = async url => {
    fetchedUrl = String(url);
    return new Response("A complete English passage. ".repeat(30), {
      status: 200,
      headers: { "Content-Type": "text/plain; charset=utf-8" }
    });
  };
  try {
    const candidate = {
      title: "exam.md",
      url: "https://github.com/example/exams/blob/main/exam.md",
      fetchUrl: "https://raw.githubusercontent.com/example/exams/main/exam.md"
    };
    const page = await downloadReadablePage(candidate);
    assert.equal(fetchedUrl, candidate.fetchUrl);
    assert.equal(page.url, candidate.url);
    assert.match(page.text, /complete English passage/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("falls back from empty Bing RSS to current HTML result cards", async () => {
  const originalFetch = globalThis.fetch;
  const urls = [];
  globalThis.fetch = async url => {
    urls.push(String(url));
    if (urls.length === 1) {
      return new Response("<rss><channel></channel></rss>", { status: 200 });
    }
    return new Response(`
      <ol id="b_results">
        <li class="b_algo" data-id="1">
          <h2><a href="https://example.com/reading">高中<strong>英语</strong>阅读题</a></h2>
          <div class="b_caption"><p>完整文章、题目与答案 &amp; 解析</p></div>
        </li>
      </ol>`, { status: 200 });
  };
  try {
    const client = new WebSearchClient({ SEARCH_PROVIDER: "bing-rss" });
    assert.deepEqual(await client.search("高中英语阅读", 5), [{
      title: "高中 英语 阅读题",
      url: "https://example.com/reading",
      snippet: "完整文章、题目与答案 & 解析"
    }]);
    assert.match(urls[0], /format=rss/);
    assert.doesNotMatch(urls[1], /format=rss/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("parses relevant 360 exam results and removes unrelated cards", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(`
    <ul class="result">
      <li class="res-list">
        <h3><a href="https://example.com/exam">高考英语阅读理解真题及答案</a></h3>
        <p class="res-desc">包含完整文章、四个选项和答案解析</p>
      </li>
      <li class="res-list">
        <h3><a href="https://example.com/cars">Used trucks for sale</a></h3>
        <p class="res-desc">Buy a car today</p>
      </li>
    </ul>`, { status: 200 });
  try {
    const client = new WebSearchClient({ SEARCH_PROVIDER: "so-html" });
    assert.deepEqual(await client.search("高考英语阅读", 10), [{
      title: "高考英语阅读理解真题及答案",
      url: "https://example.com/exam",
      snippet: "包含完整文章、四个选项和答案解析"
    }]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
