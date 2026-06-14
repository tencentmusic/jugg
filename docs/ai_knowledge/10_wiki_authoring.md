# Wiki 编写准则

> 最后核对：2026-06-14
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页面向维护 Jugg 用户 Wiki 的 AI 和开发者，回答：

- Wiki 页面应该使用哪些 Markdown 元素。
- 页面元数据、提示块、工程语义块应该怎么写。
- dev-only 页面如何约定，避免发布给用户。
- 如何启动本地实时预览服务，以及如何打包 production 产物。
- 后续新增 Wiki 编写规范时应该放在哪里。

本页不描述用户功能本身；正式用户内容仍放在 `docs/wiki`。

---

## 2. 核心文件索引

| 文件 | 作用 |
|---|---|
| `docs/wiki/package.json` | Wiki 开发、打包、产物预览的 npm scripts 入口；后续 npm 操作都在 `docs/wiki` 下执行。 |
| `docs/wiki/.vitepress/config.mts` | VitePress 站点配置，包含 nav/sidebar/search/dev-only 页面排除。 |
| `docs/wiki/dev/elements-demo.md` | 英文 dev-only 元素样板页，只用于开发环境视觉验收。 |
| `docs/wiki/zh/dev/elements-demo.md` | 中文 dev-only 元素样板页，只用于开发环境视觉验收。 |
| `docs/wiki/dev/assets/wiki-elements-demo.svg` | Demo 页使用的示例图片资源。 |

---

## 3. 页面 frontmatter 规范

Wiki 页面顶部可以使用 frontmatter 描述页面元数据：

```yaml
---
title: Wiki Elements Demo
description: Demonstrates every supported Markdown element for the Jugg Wiki.
visibility: dev
status: draft
tags:
  - dev
  - demo
---
```

常用字段：

| 字段 | 是否推荐 | 说明 |
|---|---|---|
| `title` | 推荐 | 页面标题，可用于目录、搜索和后续自动索引。 |
| `description` | 推荐 | 页面摘要，可用于搜索结果说明。 |
| `visibility` | 按需 | `dev` 表示仅开发环境可见。正式用户页面不需要写。 |
| `status` | 按需 | `draft` / `active` / `deprecated`，用于标记维护状态。 |
| `tags` | 按需 | 用于未来搜索、聚合或批量维护。 |

约束：

- dev-only 页面必须写 `visibility: dev`。
- dev-only 页面必须放在 `docs/wiki/dev/` 或 `docs/wiki/zh/dev/` 下。
- 正式用户页面不要使用 `visibility: dev`。

---

## 4. 支持的 Markdown 基础元素

Wiki 页面优先使用 Markdown 原生或 VitePress 稳定支持的元素：

| 元素 | 写法 | 说明 |
|---|---|---|
| 页面标题 | `# Title` | 每页只保留一个 H1。 |
| 多级标题 | `##` / `###` / `####` | 用于生成页面结构和锚点。 |
| 段落 | 普通文本 | 不要用过长段落堆叙述。 |
| 加粗 / 斜体 / 删除线 | `**bold**` / `*italic*` / `~~deleted~~` | 用于局部强调。 |
| 行内代码 | `` `ClassName` `` | 用于类名、方法名、路径、命令参数。 |
| 代码块 | ```` ```kotlin ```` | 用于命令、配置、伪代码、调用链。 |
| 无序列表 | `- item` | 用于规则和要点。 |
| 有序列表 | `1. item` | 用于必须按顺序执行的步骤。 |
| 任务列表 | `- [ ] item` | 用于 TODO、验收清单、迁移清单。 |
| 表格 | Markdown table | 用于源码索引、状态机、能力矩阵、排查入口。 |
| 链接 | `[text](path)` | 优先使用相对路径或站内绝对路径。 |
| 图片 | `![alt](path)` | 必须提供有意义的 alt。 |
| 引用 | `> quote` | 用于普通引用，不要冒充提示块。 |
| 分割线 | `---` | 只在长页面大段分隔时使用。 |
| 目录 | `[[toc]]` | 仅长页面需要。 |

不建议在正式用户页大量使用原生 HTML；确有必要时，应保证 VitePress build 通过且移动端可读。

---

## 5. 提示块写法

提示块统一使用 GitHub Alert 风格的 blockquote，保持 Markdown 可读和可降级：

```markdown
> [!NOTE]
> 普通说明。
```

支持等级：

| 等级 | 用途 |
|---|---|
| `NOTE` | 背景说明、补充信息。 |
| `TIP` | 推荐做法、最佳实践。 |
| `IMPORTANT` | 重要但不一定危险的信息。 |
| `WARNING` | 继续操作前必须注意的风险。 |
| `CAUTION` | 严重风险或可能破坏状态的操作。 |

带标题写法：

```markdown
> [!WARNING]
> **缓存一致性风险**
>
> scoped data 只能用于 transport，不能直接更新全局 deploy state。
```

约束：

- 不新增自定义等级，优先使用上表五类。
- 风险类内容优先使用 `WARNING` 或 `CAUTION`，不要只用普通加粗文字。
- 提示块内容可以包含列表和代码块，但应保持短小。

---

## 6. 工程语义块写法

Jugg Wiki 除了普通 Markdown，还鼓励使用固定工程语义块，让 AI 和开发者快速定位重点。

### 6.1 核心源码索引

```markdown
## Core Source Index

| Class or file | Path | Role |
|---|---|---|
| VitePress config | `docs/wiki/.vitepress/config.mts` | Owns nav, sidebar, search, and dev-only route exclusion. |
```

适用场景：页面涉及实现、配置、工具链、运行链路时。

### 6.2 调用链 / 流程

```text
entry
  -> key decision
  -> collaborator
  -> state update
```

写调用链时必须突出业务含义，不要机械罗列方法名。

### 6.3 隐形约束

```markdown
## Hidden Constraints

- Dev-only pages must use `visibility: dev` frontmatter.
- Production builds must not expose dev pages through nav, search, or direct routes.
```

适合记录代码不显眼、跨文件难推断、历史上容易误判的规则。

### 6.4 排查入口

```markdown
## Troubleshooting

| Symptom | First entry |
|---|---|
| Demo page appears in production nav | Check `docs/wiki/.vitepress/config.mts`. |
```

只给第一跳，不把页面写成完整排查剧本。

---

## 7. dev-only 页面规则

需要视觉验收但不应该发布给用户的页面，放在 dev-only 路径：

```text
docs/wiki/dev/
docs/wiki/zh/dev/
```

必须同时满足：

1. 文件路径位于 dev-only 目录。
2. frontmatter 写 `visibility: dev`。
3. `docs/wiki/.vitepress/config.mts` 在 production build 中通过 `srcExclude` 排除路径。
4. dev 模式下才在 nav/sidebar 中挂入口。

当前 dev-only 识别逻辑：

```text
JUGG_WIKI_DEV=true 或 vitepress dev
  -> include dev pages

production build
  -> exclude dev/** and zh/dev/**
```

验证方式：

```bash
cd docs/wiki
JUGG_WIKI_DEV=true npm run build
npm run build
```

production build 后应确认 dist 中不存在 dev-only 页面标题。

---

## 8. 中英文页面同步规则

Jugg Wiki 当前有英文根路径和中文 `/zh/` 路径。新增正式用户页面时：

- 优先同时新增英文和中文页面。
- 路径尽量镜像，例如 `/guide/compile` 与 `/zh/guide/compile`。
- nav/sidebar 需要同时更新英文和中文配置。
- 如果只能先写一个语言版本，应在任务结论中说明未同步原因。

Dev-only demo 页面也应保持中英文各一份，方便分别验证 locale 下的样式和导航。

---

## 9. 不推荐写法

- 不要把内部维护规则放进正式用户 Wiki 页面。
- 不要为了视觉效果大量使用自定义 HTML。
- 不要在正式页面暴露 dev-only demo、AI 维护流程、内部任务计划。
- 不要用截图替代表格、代码块或可搜索文本。
- 不要把代码实现逐句翻译成 Wiki 内容；应提炼入口、链路、状态和约束。

---

## 10. 本地预览、打包与发布

Wiki 使用 VitePress，命令入口在 `docs/wiki/package.json`。所有 npm 操作都以 `docs/wiki` 为工作目录；首次拉取或依赖变化后先安装依赖：

```bash
cd docs/wiki
npm ci
```

### 10.1 实时更新预览服务

编辑 Wiki 时使用 dev server：

```bash
npm run dev
```

默认启动 VitePress dev server，保存 Markdown 或配置文件后会自动热更新页面。需要固定监听地址或端口时，通过 `--` 继续传 VitePress 参数：

```bash
npm run dev -- --host 127.0.0.1 --port 5173
```

dev 模式会自动包含 dev-only 页面，因为 `docs/wiki/.vitepress/config.mts` 中的 `isWikiDev` 会识别 `vitepress dev`。因此本地视觉验收可以直接访问：

```text
/dev/elements-demo
/zh/dev/elements-demo
```

### 10.2 Production 打包

发布前使用 production build：

```bash
npm run build
```

构建产物输出到：

```text
docs/wiki/.vitepress/dist/
```

production build 不应带 `JUGG_WIKI_DEV=true`。默认配置会通过 `srcExclude` 排除：

```text
dev/**
zh/dev/**
```

如果需要临时验证 dev-only 页面能否独立构建，可以单独执行：

```bash
JUGG_WIKI_DEV=true npm run build
```

该命令只用于开发验收，不作为发布产物。

### 10.3 预览打包产物

`npm run dev` 预览的是源码开发态；发布前还需要预览已经生成的静态产物：

```bash
npm run preview
```

需要固定地址或端口时：

```bash
npm run preview -- --host 127.0.0.1 --port 4173
```

`npm run preview` 读取 `docs/wiki/.vitepress/dist/`，因此必须先执行 `npm run build`。

### 10.4 发布边界

当前仓库只定义了静态站点打包命令，没有绑定具体托管平台。发布时应把 `docs/wiki/.vitepress/dist/` 作为静态站点根目录交给实际托管系统，例如内部静态资源服务、Nginx、GitHub Pages 或 CI/CD artifact。

发布前检查：

1. 在 `docs/wiki` 下执行 `npm run build` 成功。
2. 在 `docs/wiki` 下执行 `npm run preview` 检查中英文首页、nav/sidebar、搜索和新增页面。
3. 确认 production 产物不包含 `dev/elements-demo` 与 `zh/dev/elements-demo`。
4. 若改动了 nav/sidebar，同时检查英文根路径和中文 `/zh/` 路径。

---

## 11. 关联文档

- `docs/ai_knowledge/97_maintenance_manual.md`：AI 知识库维护质量标准。
- `docs/ai_knowledge/99_index.md`：AI 文档检索入口和专题目录。
- `docs/wiki/dev/elements-demo.md`：英文 Wiki 元素视觉样板页。
- `docs/wiki/zh/dev/elements-demo.md`：中文 Wiki 元素视觉样板页。
