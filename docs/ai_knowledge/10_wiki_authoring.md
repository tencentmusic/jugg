# Wiki 文章编写准则

> 最后核对：2026-06-14
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页面向维护 Jugg 用户 Wiki 的 AI 和开发者，回答：

- 正式用户文章应该如何组织页面元数据、标题、正文、链接和提示块。
- Markdown 与工程语义块应该怎么使用。
- 中英文页面如何同步。
- 写能力篇前应该读取哪份专门规范。

本页不描述 Wiki 工程如何运行；开发服务、构建、预览与发布见 `10_wiki_architecture.md`。

---

## 2. 页面 frontmatter 规范

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

## 3. 支持的 Markdown 基础元素

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

## 4. 提示块写法

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

## 5. 工程语义块写法

Jugg Wiki 除了普通 Markdown，还鼓励使用固定工程语义块，让 AI 和开发者快速定位重点。

### 5.1 核心源码索引

```markdown
## Core Source Index

| Class or file | Path | Role |
|---|---|---|
| VitePress config | `docs/wiki/.vitepress/config.mts` | Owns nav, sidebar, search, and dev-only route exclusion. |
```

适用场景：页面涉及实现、配置、工具链、运行链路时。

### 5.2 调用链 / 流程

```text
entry
  -> key decision
  -> collaborator
  -> state update
```

写调用链时必须突出业务含义，不要机械罗列方法名。

### 5.3 隐形约束

```markdown
## Hidden Constraints

- Dev-only pages must use `visibility: dev` frontmatter.
- Production builds must not expose dev pages through nav, search, or direct routes.
```

适合记录代码不显眼、跨文件难推断、历史上容易误判的规则。

### 5.4 排查入口

```markdown
## Troubleshooting

| Symptom | First entry |
|---|---|
| Demo page appears in production nav | Check `docs/wiki/.vitepress/config.mts`. |
```

只给第一跳，不把页面写成完整排查剧本。

---

### 5.5 高价值技术提炼与客观叙事准则
为了使 Wiki 具备高信息密度、良好可读性与工程公信力，AI 在自动重构或扩写页面时，必须能够自发提取核心技术点，并遵循“问题驱动 + 克制硬核”的叙事风格，严禁引入情绪渲染或主观修饰。

#### 5.5.1 问题驱动的“侦探式”三段结构
- 在编写核心概念、编译核心或底层模块时，严禁平铺直叙地罗列属性，应自发组织为以下三部分结构：
- 痛点与常规方案瓶颈 (The Pain & Bottleneck)：描述在超大型项目（百万行、数百依赖）下，常规链路或原生机制面临的瓶颈。通过原理解释或量化事实说明固定耗时。
- 核心突破与架构解法 (The Core Hack)：重点阐述 Jugg 如何通过架构设计、内存级缓存或 Runtime 级别的改动绕过或消除上述瓶颈，亮出技术含金量。
- 架构边界与代价 (Trade-offs & Edge Cases)：必须明确交代方案的副作用、适用场景和隐形约束。用明确的技术边界和防御性设计提升文档的可信度。

#### 5.5.2 自动审计：无人工 Notes 提示下的提取逻辑
- 面对大量已有文档或代码，AI 必须自发分析上下文，寻找以下 “架构摩擦点” 并提升为主体结构，无需依赖人工补充备注：
- 深度定制/魔改特征：凡出现“深度定制 aapt2”、“自建隔离 ClassLoader”、“自有 JVMTI Agent”等行为，必属核心解法。
- 环境对抗与填坑特征：凡提及“国内定制系统兼容（如鸿蒙 4.2、HyperOS 等）”、“特定 IDE/AS 版本环境裁剪/缺陷绕过”的防御性代码，必须作为核心填坑历史予以提炼。
- 时序与依赖冲突特征：凡涉及“类冲突死锁”、“初始化时机提前”、“Smart Cast 失败”等对齐 Runtime 状态的逻辑，必须单独阐述其对抗机制。

#### 5.5.3 语气与修辞约束
- 严格禁用主观情绪词与焦虑渲染：禁止使用“哪怕”、“纯粹”、“死死卡在”、“绝望”、“惊人”、“断崖式”等修饰语。
- 坚持数据与原理叙事：用具体的量化指标（如：15秒、100毫秒）和技术原理解释（如：局部状态缓存缺失、内存级覆盖、沙盒化隔离）来平实陈述事实。

##### 示例对比
- ❌ 不推荐的浮夸/焦虑风格：
    > “哪怕只改动一行资源，也会被原生编译死死卡在 link 阶段，常规流程让人非常绝望。Jugg 带来惊人的效能增益，耗时断崖式下跌，堪称智商压制。”
- ❌ 不推荐的干瘪流水账风格：
    > “Android 资源由 aapt2 处理，link 需要读取完整输入，大型工程耗时较长。Jugg 定制了 aapt2，新增 inclink 命令，减少了耗时。”
- 推荐的克制硬核风格：
    > “在微小资源改动场景下，传统的 aapt2 link 流程由于缺乏局部状态缓存，每次触发构建均需全量扫描并加载 flat 中间产物，在超大型工程中面临 10 至 15 秒的固定耗时。Jugg 通过定制 aapt2 源码引入 inclink 机制，在守护进程内将基线符号表常驻为内存级缓存，使日常增量运行转变为轻量级 Overlay 注入。这一取舍将资源链接耗时缩短至 100~200 毫秒。”

## 6. 中英文页面同步规则

Jugg Wiki 当前有英文根路径和中文 `/zh/` 路径。新增正式用户页面时：

- 优先同时新增英文和中文页面。
- 路径尽量镜像，例如 `/guide/compile` 与 `/zh/guide/compile`。
- nav/sidebar 需要同时更新英文和中文配置。
- 如果只能先写一个语言版本，应在任务结论中说明未同步原因。

Dev-only demo 页面也应保持中英文各一份，方便分别验证 locale 下的样式和导航。

---

## 7. 不推荐写法

- 不要把内部维护规则放进正式用户 Wiki 页面。
- 不要为了视觉效果大量使用自定义 HTML。
- 不要在正式页面暴露 dev-only demo、AI 维护流程、内部任务计划。
- 不要用截图替代表格、代码块或可搜索文本。
- 不要把代码实现逐句翻译成 Wiki 内容；应提炼入口、链路、状态和约束。

---

## 8. 关联文档

- `10_wiki_architecture.md`：Wiki 工程结构、本地运行、构建、预览和发布边界。
- `97_maintenance_manual.md`：AI 知识库维护质量标准。
- `99_index.md`：AI 文档检索入口和专题目录。
