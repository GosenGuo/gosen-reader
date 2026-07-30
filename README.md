# 每日英语阅读（Gosen Reader）

一款自用、无需登录的原生安卓英语阅读 App。目标是每天完成一篇高中英语阅读，通过直接点击单词查看语境释义，逐步增加阅读量和词汇量。

## 第一版已经实现

- 首页今日阅读和本月进度
- 题库列表
- 类似多邻国的逐词点击交互
- 词卡展示当前语境义、词性、常见义、词形和历史点击次数
- 词卡内展示所在句及整句翻译
- 阅读结束后再显示四选一题目
- 提交后展示正确答案和中文简析
- 每天完成阅读并提交题目后打卡，正确率不影响打卡
- 当前连续天数、最长连续天数、累计文章、累计词数和点击次数
- 所有学习记录保存在手机本地
- App 每次启动检查远程题库，失败时继续使用已有题库
- 生成时自动检查每个可点击单词，缺失词义会由 AI 补齐并写入公共单词表
- 动态适配状态栏、挖孔、折叠屏和底部手势安全区域
- 内置两篇无需联网即可体验的示例文章

## 项目结构

```text
app/                         原生 Java 安卓应用
  src/main/assets/
    articles.json            内置题库
updater/                     按需自动找题和处理管线
  src/run.mjs                主流程
  src/search.mjs             免费联网搜索适配器
  src/ai.mjs                 OpenAI 兼容中转站客户端
  src/schema.mjs             数据验证和去重
refill-service/              手机端触发 GitHub Actions 的免费服务
```

安卓端刻意不使用 Firebase、Google 登录或第三方运行库。App 日常只下载处理完成的 JSON 题库，适合中国大陆网络环境。

## 构建 APK

需要 JDK 17、Android SDK 35 和 Gradle 8.9：

```powershell
$env:JAVA_HOME = "你的JDK 17目录"
$env:ANDROID_SDK_ROOT = "你的Android SDK目录"
.\gradlew.bat assembleDebug
```

APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 配置远程题库地址

构建时传入中国大陆可访问的 HTTPS 地址：

```powershell
.\gradlew.bat assembleDebug -PCONTENT_FEED_URL=https://你的域名/articles.json
```

API Key 不能放进 APK。App 只读取生成后的题库，AI 中转站和搜索均由 GitHub Actions 更新器调用。

## 免费的按需题库更新

当手机本地未读文章少于 5 篇时，App 会请求补充 30 篇。触发服务只负责安全地启动 GitHub Actions；生成成功且校验通过后，工作流追加更新 `docs/articles.json`，GitHub Pages 随即发布。仓库不再按月定时生成，也仍可在 Actions 页面手动运行。

- `AI_API_KEY`：在仓库 Settings → Secrets and variables → Actions 中添加
- `AI_BASE_URL`：默认 `https://xcode.best/v1`
- `AI_MODEL`：默认使用该密钥允许的 `deepseek-v4-flash`
- `AI_MODEL_PRICING_JSON`：可选，按模型配置每百万输入/输出 token 的美元单价
- `USD_CNY_RATE`：可选，用于把美元估算费用同时换算为人民币

默认使用免费的 360 中文搜索，不需要搜索 API Key，并过滤明显无关结果。AI 或搜索失败时，工作流会停止，不会覆盖上一次可用题库。

更新器的工作顺序：

1. AI 规划面向中文网页搜索的试题检索词；
2. 免费联网搜索找出候选网页；
3. 自动下载和清理网页；
4. AI 只提取网页里已经存在的文章、题目、选项和答案；
5. AI 生成逐词数据、常见词形、整句翻译和简析；
6. 扫描文章中的每个可点击单词，自动补齐缺失词卡并更新 `word-bank.json`；
7. 检查文章长度、四个选项、答案、翻译覆盖率、词卡 100% 覆盖和重复内容；
8. 逐篇记录输入、输出、总 token 和费用，并写入 `generation-ledger.json`；
9. 合格文章达到约 30 篇后追加到 `articles.json`；
10. 提交题库、公共单词表和生成账本到 GitHub Pages，App 下次检查时自动取得。

执行：

```powershell
Set-Location updater
node src/run.mjs
```

若一次补充不足 30 篇通过检查，更新器会发布较少的合格文章，不会让 AI 虚构内容凑数。

## GitHub Pages 地址

本仓库的题库地址为：

```text
https://gosenguo.github.io/gosen-reader/articles.json
```

公共单词表地址：

```text
https://gosenguo.github.io/gosen-reader/word-bank.json
```

中国大陆网络若访问 GitHub Pages 不稳定，可以使用 VPN。API 密钥只存在 GitHub Secrets，不进入代码仓库和手机。
