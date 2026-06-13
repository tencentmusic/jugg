# Wiki 目录遗漏校验报告

> 结论：本次审计未发现用户可见主功能域在 Wiki 目录层面的遗漏。当前 Wiki 目录已覆盖主要模块、公开 MCP 工具、公开 CLI 子命令、AI 知识库专题与高频排障域。本文是阶段性审计报告，不作为长期同步源；长期以 `docs/wiki/**` 与 VitePress sidebar 为准。

## 1. 审计目标

本次只确认“目录层面是否有入口”，不写 Wiki 正文，不证明正文内容已经完整。校验对象包括：

- 中英文页面镜像是否完整。
- VitePress sidebar/nav 链接是否都能落到文件。
- 是否存在未被导航覆盖的孤儿页面。
- `settings.gradle` 模块是否都有 Wiki 归宿。
- MCP 注册工具是否都有 Wiki 归宿。
- CLI 公开子命令是否都有 Wiki 归宿。
- `docs/ai_knowledge` 专题域是否都有 Wiki 归宿。
- 代码中存在但未注册的 MCP Action 是否被误当作公开能力。

## 2. 自动化校验结果

### 2.1 Wiki 页面与导航

| 检查项 | 结果 |
|---|---:|
| sidebar/nav 链接数 | 164 |
| Markdown 页面总数 | 166 |
| 英文页面数 | 83 |
| 中文页面数 | 83 |
| 缺失的 sidebar/nav 目标文件 | 0 |
| 孤儿页面 | 0 |
| 英文有、中文缺的镜像页 | 0 |
| 中文有、英文缺的镜像页 | 0 |

执行口径：解析 `docs/wiki/.vitepress/config.mts` 中所有 `link`，映射到 `docs/wiki/**.md`；同时比对 `docs/wiki` 与 `docs/wiki/zh` 的相对路径集合。

### 2.2 VitePress 构建

已执行：

```bash
npm run wiki:build
```

结果：通过。VitePress 成功渲染所有页面，无构建期死链错误。

## 3. 入口枚举覆盖结果

### 3.1 Gradle 模块覆盖

`settings.gradle` 当前包含 18 个模块/子模块。目录覆盖结论如下：

| 模块 | Wiki 归宿 | 结论 |
|---|---|---|
| `:main` | `concepts/how-jugg-works`、`concepts/compile-pipeline`、`capabilities/*`、`reference/modules` | 已覆盖 |
| `:idea` | `onboarding/*`、`guide/*`、`concepts/how-jugg-works`、`reference/modules` | 已覆盖 |
| `:cmd_line` | `guide/cli`、`reference/cli-commands`、`reference/modules` | 已覆盖 |
| `:jvmti_agent` | `concepts/jvmti-agent`、`capabilities/deploy/jvmti-runtime`、`capabilities/tools/ui-automation`、`reference/modules` | 已覆盖 |
| `:aapt2-inclink` | `capabilities/compile/resource-compile`、`reference/modules` | 已覆盖 |
| `:custom_compilers` | `guide/custom-compiler`、`capabilities/compile/custom-compiler`、`reference/modules` | 已覆盖 |
| `:deploy_compat:*` | `concepts/compatibility-layer`、`reference/compatibility`、`reference/modules` | 已覆盖 |
| `:platform_compat:base_api` | `concepts/compatibility-layer`、`reference/modules` | 已覆盖 |

### 3.2 MCP 注册工具覆盖

`McpToolActionRegistry.defaultActions()` 当前注册 18 个公开工具：

| MCP tool | Wiki 归宿 | 结论 |
|---|---|---|
| `version` | `capabilities/tools/mcp-tool-reference`、`reference/mcp-tools` | 已覆盖 |
| `list-projects` | `capabilities/tools/mcp-tool-reference`、`reference/mcp-tools` | 已覆盖 |
| `restart` | `capabilities/deploy/restart`、`capabilities/tools/mcp-tool-reference` | 已覆盖 |
| `compile` | `guide/compile`、`capabilities/compile/incremental-compile`、`reference/mcp-tools` | 已覆盖 |
| `deploy` | `guide/deploy`、`capabilities/deploy/*`、`reference/mcp-tools` | 已覆盖 |
| `instrument` | `guide/android-test`、`capabilities/test/android-test`、`reference/mcp-tools` | 已覆盖 |
| `clean-reinstall` | `capabilities/deploy/clean-reinstall`、`reference/mcp-tools` | 已覆盖 |
| `gradle-build` | `capabilities/compile/gradle-fallback`、`guide/compile`、`reference/mcp-tools` | 已覆盖 |
| `get-compile-status` | `guide/mcp`、`capabilities/tools/mcp-tool-reference`、`reference/mcp-tools` | 已覆盖 |
| `ssh-info` | `capabilities/tools/remote-diagnosis`、`reference/mcp-tools` | 已覆盖 |
| `devices` | `guide/deploy`、`capabilities/deploy/multi-device`、`reference/mcp-tools` | 已覆盖 |
| `layout-dump` | `guide/ui-inspection`、`capabilities/tools/ui-automation`、`reference/mcp-tools` | 已覆盖 |
| `view-locate` | `guide/ui-inspection`、`capabilities/tools/ui-automation`、`reference/mcp-tools` | 已覆盖 |
| `view-inspect` | `guide/ui-inspection`、`capabilities/tools/ui-automation`、`reference/mcp-tools` | 已覆盖 |
| `activity-stack` | `guide/ui-inspection`、`capabilities/tools/ui-automation`、`reference/mcp-tools` | 已覆盖 |
| `tap` | `guide/ui-inspection`、`capabilities/tools/ui-automation`、`reference/mcp-tools` | 已覆盖 |
| `status` | `guide/mcp`、`capabilities/tools/mcp-tool-reference`、`reference/mcp-tools` | 已覆盖 |
| `wait-logs` | `guide/ui-inspection`、`troubleshooting/logs`、`reference/mcp-tools` | 已覆盖 |

### 3.3 CLI 公开子命令覆盖

`docs/skills/jugg-android-dev-loop/scripts/jugg.py::COMMANDS` 当前公开 16 个子命令：

| CLI command | Wiki 归宿 | 结论 |
|---|---|---|
| `version` | `guide/cli`、`reference/cli-commands` | 已覆盖 |
| `compile` | `guide/compile`、`guide/cli`、`reference/cli-commands` | 已覆盖 |
| `deploy` | `guide/deploy`、`guide/cli`、`reference/cli-commands` | 已覆盖 |
| `gradle-build` | `capabilities/compile/gradle-fallback`、`reference/cli-commands` | 已覆盖 |
| `clean-reinstall` | `capabilities/deploy/clean-reinstall`、`reference/cli-commands` | 已覆盖 |
| `restart` | `capabilities/deploy/restart`、`reference/cli-commands` | 已覆盖 |
| `instrument` | `guide/android-test`、`capabilities/test/android-test`、`reference/cli-commands` | 已覆盖 |
| `status` | `guide/cli`、`reference/cli-commands` | 已覆盖 |
| `layout-dump` | `guide/ui-inspection`、`capabilities/tools/ui-automation`、`reference/cli-commands` | 已覆盖 |
| `view-locate` | `guide/ui-inspection`、`capabilities/tools/ui-automation`、`reference/cli-commands` | 已覆盖 |
| `view-inspect` | `guide/ui-inspection`、`capabilities/tools/ui-automation`、`reference/cli-commands` | 已覆盖 |
| `tap` | `guide/ui-inspection`、`capabilities/tools/ui-automation`、`reference/cli-commands` | 已覆盖 |
| `devices` | `capabilities/deploy/multi-device`、`reference/cli-commands` | 已覆盖 |
| `activity-stack` | `guide/ui-inspection`、`reference/cli-commands` | 已覆盖 |
| `ssh-info` | `capabilities/tools/remote-diagnosis`、`reference/cli-commands` | 已覆盖 |
| `wait-logs` | `guide/ui-inspection`、`troubleshooting/logs`、`reference/cli-commands` | 已覆盖 |

## 4. AI 知识库专题覆盖结果

| AI 专题域 | Wiki 归宿 | 结论 |
|---|---|---|
| 整体架构 / 模块划分 | `concepts/how-jugg-works`、`reference/modules` | 已覆盖 |
| 编译核心流程 | `concepts/incremental-compile`、`concepts/compile-pipeline`、`capabilities/compile/*` | 已覆盖 |
| 源码编译 Java/Kotlin/Dex/APT/KSP | `capabilities/compile/incremental-compile` | 已覆盖 |
| 资源编译 / aapt2 / arsc / assets | `capabilities/compile/resource-compile` | 已覆盖 |
| DataBinding / ViewBinding | `capabilities/compile/databinding-viewbinding` | 已覆盖 |
| Manifest / release obfuscation | `capabilities/compile/manifest`、`capabilities/compile/release-compile`、`troubleshooting/runtime` | 已覆盖 |
| 自定义编译器 / 编译交互 | `guide/custom-compiler`、`capabilities/compile/custom-compiler` | 已覆盖 |
| 部署核心 / 端到端流程 | `guide/deploy`、`concepts/deploy-strategy`、`capabilities/deploy/*` | 已覆盖 |
| 常量引用 / 影响分析 / 部署数据 | `capabilities/compile/const-ref`、`concepts/deploy-data-and-impact` | 已覆盖 |
| JVMTI / runtime agent | `concepts/jvmti-agent`、`capabilities/deploy/jvmti-runtime` | 已覆盖 |
| IDE 生命周期 / RunConfig / Debug attach | `onboarding/*`、`guide/debug`、`troubleshooting/debug` | 已覆盖 |
| 项目模型 / Gradle 集成 / 远端 Gradle | `concepts/project-model`、`guide/remote-gradle`、`capabilities/compile/gradle-fallback` | 已覆盖 |
| 兼容层 / 命令行模块 | `concepts/compatibility-layer`、`reference/compatibility`、`reference/modules` | 已覆盖 |
| MCP / CLI / UI 验证 | `guide/mcp`、`guide/cli`、`guide/ui-inspection`、`capabilities/tools/*`、`reference/*` | 已覆盖 |
| AndroidTest | `guide/android-test`、`concepts/android-test-flow`、`capabilities/test/*`、`troubleshooting/android-test` | 已覆盖 |
| 插件运行时排查 | `troubleshooting/*`、`reference/log-files` | 已覆盖 |
| 公共工具模块 | `reference/modules`、`reference/log-files`、`reference/configuration` | 已覆盖 |

## 5. 未注册 MCP Action 的处理结论

代码中存在但未注册到 `McpToolActionRegistry.defaultActions()` 的 Action 不算当前公开用户功能，不需要单独作为公开工具页承诺可调用。已确认这些能力应在后续正文里以“未注册 / 内部实现 / 历史实现”口径说明，避免误导：

| Action / tool name | 当前目录处理 |
|---|---|
| `FigmaLayoutVerifyMcpToolAction` / `figma-layout-verify` | `capabilities/tools/layout-verify`、`reference/mcp-tools` 中说明为未注册内部算法 |
| `LayoutVerifyMcpToolAction` / `layout-verify` | `capabilities/tools/layout-verify`、`reference/mcp-tools` 中说明为未注册旧批量验证 |
| `ScreenshotMcpToolAction` / `screenshot` | `reference/mcp-tools` 中说明为未注册，不作为默认公开证据来源 |
| `StartRecordMcpToolAction` / `record-start` | `reference/mcp-tools` 中说明为未注册 |
| `StopRecordMcpToolAction` / `record-stop` | `reference/mcp-tools` 中说明为未注册 |
| `StartAppMcpToolAction` / `start_app` | `reference/mcp-tools` 中说明为未注册 |
| `StartActivityMcpToolAction` / `start_activity` | `reference/mcp-tools` 中说明为未注册 |
| `EmulatorListMcpToolAction` / `emulator_list` | `reference/mcp-tools` 中说明为未注册 |
| `StartEmulatorMcpToolAction` / `start_emulator` | `reference/mcp-tools` 中说明为未注册 |

## 6. 本次确认结论

- 未发现需要新增 Wiki 目录页的遗漏。
- 未发现中英文镜像缺失。
- 未发现 sidebar/nav 指向不存在文件。
- 未发现孤儿页面。
- 公开 MCP 工具、公开 CLI 子命令、Gradle 模块与 AI 专题域均已有 Wiki 目录归宿。
- 代码中未注册 MCP Action 已识别为“非公开能力 / 内部实现 / 历史实现”，后续写正文时需要明确说明，不能承诺可直接调用。

## 7. 后续维护建议

- 长期不要人工维护一份完整功能清单；以 `docs/wiki/**` 与 sidebar 为准。
- 大版本发布前再执行一次本类审计。
- 后续如新增 MCP tool 或 CLI command，应同步：
  - 对应 Guide / Capability / Reference 页面；
  - `docs/wiki/.vitepress/config.mts`；
  - 中英文镜像占位或正文。
- 后续可把本次手工审计脚本固化为轻量校验脚本，用于 CI 或发布前检查。
