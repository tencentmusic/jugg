# VitePress Wiki Foundation Design

## 1. 背景

Jugg 目前已有 `docs/ai_knowledge` 承载内部开发与 AI 任务路由知识，但缺少面向已安装插件用户的公开 wiki。新 wiki 目标是帮助内部/外部使用者理解如何使用 Jugg、确认能力边界并完成问题排查。

本次只搭建 VitePress 基建与中英文双语目录骨架，不写正式文章内容，不做全量能力盘点。

## 2. 目标

- 在 `docs/wiki` 下建立独立 VitePress 站点根目录。
- 提供可运行、可预览、可扩展的文档站点基础配置。
- 建立面向使用者的信息架构，覆盖 onboarding、操作指南、概念、能力、排查与参考信息。
- 建立英文默认站点与中文子目录，保证对外英文链接访问根路径即可进入内容。
- 为后续补充依赖库增量编译、Android Test、Clean Reinstall、Release 编译等能力文档预留位置。
- 保持 `docs/wiki` 与 `docs/ai_knowledge` 边界清晰，避免公开 wiki 直接暴露内部开发知识库。

## 3. 非目标

- 不迁移或复制 `docs/ai_knowledge` 正文。
- 不编写正式 wiki 文章。
- 不盘点 Jugg 的全部能力清单。
- 不引入自定义 VitePress 主题或复杂前端组件。
- 不设计发布流水线。

## 4. 推荐方案

采用 `docs/wiki` 作为独立 VitePress 站点根目录，并采用英文 root locale、中文 `/zh/` locale：

```text
docs/wiki/
  .vitepress/
    config.mts
  index.md
  onboarding/
  guide/
  concepts/
  capabilities/
  troubleshooting/
  reference/
  zh/
    index.md
    onboarding/
    guide/
    concepts/
    capabilities/
    troubleshooting/
    reference/
```

英文是默认语言，访问 `/`、`/guide/`、`/capabilities/` 时直接进入英文内容。中文访问 `/zh/`、`/zh/guide/`、`/zh/capabilities/`。根目录不做语言选择页，避免用户进入站点后多一次选择。

根目录新增 Node 工具链入口：

```text
package.json
package-lock.json
```

`package.json` 提供最小脚本：

```json
{
  "scripts": {
    "wiki:dev": "vitepress dev docs/wiki",
    "wiki:build": "vitepress build docs/wiki",
    "wiki:preview": "vitepress preview docs/wiki"
  },
  "devDependencies": {
    "vitepress": "<current stable version>"
  }
}
```

实际版本以创建时 `npm install -D vitepress` 解析出的 lockfile 为准。

## 5. 双语结构约定

- 英文 root locale 是对外默认文档入口。
- 中文放在 `docs/wiki/zh`，路径与英文保持镜像。
- 同一主题优先保持英文与中文文件名一致，例如：

```text
docs/wiki/capabilities/compile/release-compile.md
docs/wiki/zh/capabilities/compile/release-compile.md
```

- 本次只创建双语占位页。英文页标题使用英文，中文页标题使用中文；正文均只放 `Coming soon.`。
- 后续正式写作时，英文与中文可以分批补齐，但新增页面时应同时预留另一种语言的镜像占位，避免导航结构分叉。

## 6. 目录含义

以下目录含义同时适用于英文 root locale 与中文 `/zh/` locale。

### 6.1 `onboarding/`

面向第一次接触或刚装好 Jugg 的用户，回答“我怎么开始用”。

适合放：
- 安装与环境准备。
- 首次运行。
- 基础工作流。
- 从普通 Run 切换到 Jugg 的入门引导。

不适合放复杂技术原理、内部架构和长篇排查材料。

### 6.2 `guide/`

面向已经能基本使用 Jugg、希望完成具体任务的用户，回答“我要做 X，步骤是什么”。

适合放：
- 编译操作指南。
- 部署操作指南。
- Android Test 操作指南。
- Debug 操作指南。
- 查看日志或执行某个工作流的步骤。

文章应以操作步骤、前置条件、预期结果为主。

### 6.3 `concepts/`

面向希望理解机制和边界的用户，回答“为什么是这样、什么时候会这样”。

适合放：
- Jugg 工作原理。
- 增量编译机制。
- 部署策略。
- Gradle 回退机制。
- 能力边界与限制。

文章应解释概念、决策逻辑和边界，不承担操作手册职责。

### 6.4 `capabilities/`

面向希望确认某项能力是否支持、支持到什么程度的用户，回答“Jugg 能不能做 X”。

建议按能力域拆分：

```text
capabilities/
  compile/
  deploy/
  test/
  tools/
```

适合放：
- `capabilities/compile/dependency-incremental.md`：依赖库增量编译。
- `capabilities/compile/release-compile.md`：Release 编译。
- `capabilities/deploy/clean-reinstall.md`：Clean Reinstall。
- `capabilities/deploy/code-swap.md`：Code Swap。
- `capabilities/deploy/full-swap.md`：Full Swap。
- `capabilities/test/android-test.md`：Android Test。
- `capabilities/tools/cli.md`：CLI 能力。
- `capabilities/tools/mcp.md`：MCP 能力。

能力页应优先说明支持范围、入口、限制、典型场景和关联排查入口。

### 6.5 `troubleshooting/`

面向已经遇到问题的用户，回答“出错了怎么办”。

适合放：
- 编译失败。
- 部署失败。
- 运行时 crash。
- 日志位置。
- 常见错误与用户侧处理建议。

文章入口应是现象、错误、日志或失败场景。

### 6.6 `reference/`

面向需要稳定查询信息的用户，回答“完整列表、参数、兼容性或术语是什么”。

适合放：
- 兼容版本。
- 配置项。
- 命令参数。
- 术语表。
- 限制清单。

文章更像字典、表格或索引，不承担教学主线。

## 7. 后续新增文档归位规则

- 新用户第一天会读：放 `onboarding/`。
- 用户按步骤完成任务：放 `guide/`。
- 解释机制和边界：放 `concepts/`。
- 介绍某项能力是否支持：放 `capabilities/`。
- 用户已经失败并在排查：放 `troubleshooting/`。
- 需要稳定查询的列表、参数、术语：放 `reference/`。

同一个主题可以拆成多篇。例如 Android Test：

- `onboarding/basic-workflow.md`：入门流程中轻量提到 Android Test。
- `guide/android-test.md`：完整操作步骤。
- `capabilities/test/android-test.md`：支持范围和限制。
- `troubleshooting/android-test.md`：失败排查。
- `concepts/android-test-flow.md`：后续需要时再解释机制。

中文镜像路径在 `/zh/` 下保持同名，例如：

- `zh/guide/android-test.md`
- `zh/capabilities/test/android-test.md`
- `zh/troubleshooting/android-test.md`

## 8. 初始占位策略

本次实现只创建极简占位页：

- 英文与中文每个目录各一个 `index.md`。
- 已确认需要预留的能力页只包含标题和 `Coming soon.`。
- 不从 `docs/ai_knowledge` 复制正文。
- 不在占位页中写未核实的能力描述。

## 9. 导航设计

顶部导航保持简洁：

- Guide
- Concepts
- Capabilities
- Troubleshooting
- Reference

英文首页承担默认入口聚合职责，链接到英文各大类，不写长篇介绍。中文首页位于 `/zh/`，链接到中文各大类。

VitePress 配置使用 `locales.root` 与 `locales.zh`。两个 locale 各自维护 nav 与 sidebar，结构保持镜像；后续新增文章时同步更新 `docs/wiki/.vitepress/config.mts`。

对外链接建议直接给语言入口：

```text
English: /guide/
中文: /zh/guide/
```

## 10. 验证方式

实现完成后执行：

```bash
npm run wiki:build
```

预期：
- VitePress build 成功。
- 无死链。
- `docs/wiki/.vitepress/dist` 只作为构建产物，不提交。

必要时执行：

```bash
npm run wiki:dev
```

人工打开本地地址确认导航和侧边栏可用。

## 11. 文档边界

`docs/wiki` 面向用户，使用用户能理解的概念组织内容。

`docs/ai_knowledge` 继续面向内部开发和 AI 代码任务，保留源码索引、任务路由、内部链路和测试策略。

后续如果 wiki 需要解释技术方案，应写成用户视角的“工作原理 / 能力边界 / 排查决策”，不要直接搬运内部实现方案。
