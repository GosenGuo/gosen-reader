import assert from "node:assert/strict";
import test from "node:test";
import { WebSearchClient } from "../src/search.mjs";

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
