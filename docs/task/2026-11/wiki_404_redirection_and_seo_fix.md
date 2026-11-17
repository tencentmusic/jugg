# Wiki 404 访问归因分析与重定向治理方案

> 创建时间：2026-11-17  
> 状态：进行中

---

## 1. 背景与现象

Google Analytics 4 (GA4) 报表（20261110 - 20261116）显示，Jugg 官方 Wiki（GitHub Pages 站点 `https://tencentmusic.github.io/jugg/`）出现了大量 404 页面访问。

经归类分析，所有 404 路径均存在强烈的规则特征：
1. **中文目录层级丢失**（如 `/jugg/zh/run`、`/jugg/zh/debug`、`/jugg/zh/clean-data`、`/jugg/zh/limits`）：本应位于 `/zh/guide/` 或 `/zh/reference/` 等子目录下。
2. **历史技术文章路径缺失 `/articles/`**（如 `/jugg/zh/03-resource-incremental-compilation`、`/jugg/zh/04-incremental-deployment`、`/jugg/zh/06-time-savings`、`/jugg/zh/08-jugg-3-0`）。
3. **英文概念篇缺失 `/incremental-compile/`**（如 `/jugg/concepts/const-ref`、`/jugg/concepts/resource`、`/jugg/concepts/source`）。
4. **能力篇层级混乱**：部分请求丢失子分类目录（如 `/jugg/capabilities/source-compile`），部分请求丢失父目录 `/capabilities/`（如 `/jugg/compile/source-compile`、`/jugg/deploy/code-swap`、`/jugg/tools/cli`）。
5. **多语言命名误解**：部分访问带有多余的 `/en/` 前缀（如 `/jugg/en/guide/report-issue`），而 Jugg Wiki 默认将英文作为根目录部署。
6. **下载包名误当 URL**（如 `/jugg/Download jugg-3.4.3-release.zip`）。

---

## 2. 根因追溯

核心技术根因包含以下维度：

1. **Trailing Slash（末尾斜杠）缺失与 RFC 3986 相对路径解析机制**：
   当用户或爬虫访问不带尾部斜杠的目录 URL（如 `https://tencentmusic.github.io/jugg/zh/guide`）时，浏览器将 `guide` 视作普通资源而非目录，导致 Base URI 被升阶为 `/jugg/zh/`。页面内原本写为相对路径 `[运行 App](./run.md)` 的链接，被错误解析为 `/jugg/zh/run`。
2. **GitHub Pages 静态托管与 VitePress cleanUrls 特性**：
   静态服务器默认不会对所有无尾斜杠目录请求强制 301 重定向，网络爬虫在抓取页面后顺着错误的 Base URI 遍历，导致大批量 404 链接被爬取和收录。
3. **历史版本结构迁移与外链残留**：
   早期在微信、知乎、掘金等平台分享的技术文章曾使用扁平命名，迁移至 `/zh/articles/` 后缺少重定向映射。

---

## 3. 治理设计与实施措施

采取“构建期静态重定向生成 + 运行时 404 智能兜底 + 源头导航链接绝对化”的三层治理策略：

### 3.1 映射规则中心 (`docs/wiki/scripts/redirect-rules.mjs`)
统一收敛所有已知 404 路径及通用规则映射：
- 中文扁平路径自动寻址到对应分类（`guide/`, `onboarding/`, `reference/`, `concepts/`, `capabilities/`）。
- 历史技术文章 `01` ~ `08` 映射到 `/zh/articles/`。
- 英文 `concepts/*` 自动补全 `/concepts/incremental-compile/*`。
- `compile/*`, `deploy/*`, `test/*`, `tools/*` 自动补全 `/capabilities/` 前缀。
- `capabilities/*` 自动路由至对应子分类。
- 剥离多余的 `/en/` 前缀。
- 下载文件名重定向至 GitHub Releases。

### 3.2 构建期静态 HTML 重定向文件生成 (`docs/wiki/scripts/generate-redirects.mjs`)
在 `vitepress build` 完成后执行：
- 在 `docs/wiki/.vitepress/dist` 中为每一个别名路径生成独立的 `.html` 静态文件。
- 文件包含 `<meta http-equiv="refresh">`、`<link rel="canonical">` 与 JS `window.location.replace`。
- 保证用户和 Googlebot 请求这些历史路径时，GitHub Pages 直接返回 HTTP 200 并顺畅跳转，彻底消灭服务端 404。

### 3.3 客户端 404 兜底 (`docs/wiki/.vitepress/theme/NotFound.vue` / `index.ts`)
当 SPA 或动态访问遇到未覆盖的长尾路径时，在 404 页面中调用规则库自动重定向，提供良好的用户体验。

### 3.4 核心 Index 页面链接绝对化
将各模块 `index.md` 页面中的核心导航相对链接改为绝对站内链接（如 `/zh/guide/run`），彻底杜绝由于当前页面无斜杠导致的 Base URI 偏移问题。

---

## 4. 验证计划

1. 语法与内链校验：运行 `.agents/skills/wiki-writer/scripts/validate_wiki.py` 必须全部通过。
2. 构建与渲染校验：运行 `npm run check:homepage` 确保 VitePress 构建无异常。
3. 静态产物检查：核查 `docs/wiki/.vitepress/dist` 下是否已成功输出对应的重定向文件（如 `zh/run.html`, `concepts/const-ref.html`, `capabilities/cli.html` 等），且 canonical 与 target URL 准确无误。
