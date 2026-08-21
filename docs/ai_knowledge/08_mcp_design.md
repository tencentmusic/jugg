# MCP 设计说明

> 最后核对：2026-08-05
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述 MCP 的实现分层、校验边界、异步模型和扩展同步规则，不提供完整工具参数表。

公开工具清单与 schema 以 [`08_mcp_tools_list.md`](08_mcp_tools_list.md)、`McpToolActionRegistry.defaultActions()` 和运行时 `tools/list` 为准。CLI 封装层见 [`08_cli_tools_list.md`](08_cli_tools_list.md)。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---------|------|------|
| `McpLocalServer` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpLocalServer.kt` | 本地 HTTP 服务入口，监听端口并承接 `/jugg-mcp` 请求 |
| `McpBaseInvoker` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpBaseInvoker.kt` | 处理 initialize/ping/tools/list 等通用 JSON-RPC 方法 |
| `McpToolInvoker` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpToolInvoker.kt` | 分发 `tools/call` 到具体 action，并做结果映射 |
| `McpRequestValidator` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/McpRequestValidator.kt` | schema 校验、默认值填充、unknown argument 拦截、projectDir 校验 |
| `McpToolActionRegistry` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolActionRegistry.kt` | 注册公开 MCP tool；`noProjectDirTools` 是全局工具白名单 |
| `McpToolSchemas` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolSchemas.kt` | 复用 schema 片段 |
| `IMcpRuntime` / `IdeaMcpRuntime` / `StandaloneProjectRuntime` | `main/.../ai/mcp/IMcpRuntime.kt`, `idea/.../ai/mcp/IdeaMcpRuntime.kt`, `cmd_line/.../standalone/StandaloneProjectRuntime.kt` | 以非空 host-neutral `projectDir` 将 action 连接到 IDEA 或 standalone 项目能力；所有能力均由 Host 显式实现，包括 unsupported 与 project-state lock 语义；action 不再读取 `Project.basePath` |
| `ViewHierarchyClient` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/viewhierarchy/ViewHierarchyClient.kt` | App 内 ViewHierarchy LocalSocket 客户端 |
| `LayoutDumpHelper` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/LayoutDumpHelper.kt` | `layout-dump`、`view-locate` 和内部布局验证复用的 dump 能力 |
| `McpAppReadyGuard` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpAppReadyGuard.kt` | runtime observe / mutate 类工具的 App ready 前后置检查 |

---

## 3. 分层结构

```text
McpLocalServer
  -> McpBaseInvoker
       -> initialize / ping / tools/list / global tools
  -> McpRequestValidator
       -> JSON-RPC params 解析
       -> inputSchema 校验
       -> projectDir 必填与初始化校验
  -> McpToolInvoker
       -> McpToolActionRegistry.getAction(toolName)
       -> action.execute(arguments, runtime)
       -> McpResultMapper 输出 JSON-RPC response
```

运行时观察类工具再进入：

```text
McpToolAction
  -> McpAppReadyGuard / DeviceSelectionResolver
  -> ViewHierarchyClient 或 compile/deploy runtime
  -> structuredContent(status/message/data/artifacts/errorCode)
```

---

## 4. 协议与返回模型

- 传输：HTTP + JSON-RPC 2.0。
- 主入口：`/jugg-mcp`。
- 协议级错误走 JSON-RPC `error`。
- 业务失败通常仍返回 tool result，`structuredContent.status=ERROR`，并保留 `message/data/artifacts/errorCode`。

业务失败不应被客户端当作协议失败；Agent 判断命令是否成功时必须读取 `structuredContent.status` 和对应业务字段。

---

## 5. 参数校验边界

`McpRequestValidator` 是 MCP 入参的统一闸口：

- unknown argument 会被拒绝。
- `required` 字段缺失会返回 `INVALID_PARAMS`。
- 嵌套 object 也按 schema 校验 `additionalProperties`。
- 只有 `McpToolActionRegistry.noProjectDirTools` 中的工具不要求 `projectDir`；当前是 `list-projects` 与 `version`。
- 非全局工具会校验项目是否已初始化。
- `projectDir` 在 schema 校验前经 `ProjectDirNormalizer.normalizeProjectDir` 统一规范化（`/` 分隔、Windows 盘符路径、MSYS `/d/...`、Cygwin `/cygdrive/d/...`、WSL `/mnt/d/...`）；`list-projects` 输出与 `JuggInitializer.getManager` 查找均使用同一 canonical 形式。

Action 内只保留业务组合校验，例如 `instrument` 的 sourcePath/baseline 校验、runtime observe 工具的 App ready 校验。未注册 action（如 `layout-verify`）即使保留内部校验，也不能视为公开 MCP 能力。

IDEA 与 standalone 可以监听同一端口范围内的不同端口。`version` 返回当前进程的 `runtimeType`、`runtimeVersion` 与 `capabilities`；`list-projects` 只列出当前进程已经初始化的项目。capability 由进程级 `McpToolRegistry` 统一提供，并同时约束 `tools/list` 和 action 分发，不属于 `RuntimeInfo` 或平台接口。standalone Step 11 注册 `version`、`list-projects`、`init`、`compile`、`deploy`、`gradle-build`、`get-compile-status`、`status`；`init` action 仅加入 standalone action registry，不改变 IDEA 的公开工具集合。

设备选择采用请求级上下文，不维护 MCP server 全局“当前设备”。设备相关 schema 公开可选 `serial`，`DeviceSelectionResolver` 对显式值做在线精确匹配且禁止回退；编译/部署通过 `CompileUiHandler.targetDeviceSerial` 将其贯穿首次运行判断、Gradle fallback、deploy、hasRun 与 app-ready。未传 serial 时继续使用 IDEA 选择或 standalone `ANDROID_SERIAL`。standalone 当前只在已注册的 `deploy`、`gradle-build`、`status` 能力中消费该上下文，UI、日志与运行控制工具仍不扩展 capability。

`McpLocalServer` 会在任意 HTTP 请求到达时触发外部活动回调；IDEA 使用默认空回调，standalone 用它刷新 4 小时 idle deadline。请求解析失败不影响该活动语义。

`status` 使用项目锁的非阻塞读取边界：成功取得锁时先完成 Runtime owner 恢复与可选 Git refresh，再返回一致性快照；同 Runtime 正在编译或锁被其他项目写事务持有时立即返回内存与持久化状态组成的真实只读快照，不刷新 Git、不更新 `DeployFileManager`，也不伪造空文件或默认部署状态。这样既避免读取半提交状态，也不会阻塞 CLI 的 wait/heartbeat。

---

## 6. 异步编译模型

`CompileJobManager` 统一承载 compile/deploy/gradle-build/instrument 的异步任务：

```text
compile/deploy/gradle-build/instrument
  -> 可能返回 data.status=running + jobId
  -> get-compile-status(projectDir, jobId, waitTimeoutMs)
  -> 返回终态 status/message/logPath/isCompileSuccess/isDeploySuccess
```

约束：

- `compile_latest.log` 是统一日志出口路径。
- `get-compile-status` 用 job 状态收口，不应重新触发业务动作。
- CLI 的自动轮询只是一层封装；MCP tool 本身仍允许客户端自行轮询。

---

## 7. ViewHierarchy 与 UI 工具约束

- `layout-dump` 公开 HTML artifact；内部 JSON 文件只给 `view-locate` 等实现消费。
- `ViewHierarchyClient` 使用 App 内 LocalSocket Server-only 通道，不走 uiautomator 回退。
- App 侧 ViewHierarchy 统一由 `DragonflyHierarchySource` 提取 Android View 与 Compose 节点；dump、selector、元素点击、getter 查询和旧布局验证不再维护独立 ViewTree 数据源，MCP 参数、响应 envelope 和 HTML artifact 不变。
- Android 节点继续使用 Dragonfly 暴露的原始 View 执行 `performClick` / getter；Compose 节点当前以 bounds 中心 MotionEvent 点击，并对 Dragonfly 节点对象执行 getter，缺失能力返回明确错误。
- 无 id 节点使用确定性 `_vir_id_<hash>`；只有 Dragonfly window/children 顺序与 UI 结构不变时才保证跨请求一致，不作为业务稳定身份。
- 多进程场景按“主进程优先 -> 其余 PID -> `jugg_vh` 兼容名”尝试 socket。
- `ElementFinder` 在 selector 命中前过滤不可操作节点：可见、已显示、非零尺寸、有效 bounds。
- ViewHierarchy socket 响应包含 `version`；当前客户端为 warn-only，版本不匹配只告警。
- Breaking change（字段删除、类型变更、语义不兼容）必须递增服务端协议版本，并同步客户端常量与文档。

公开 UI 验证工具边界：

| 工具 | 当前公开状态 | 入口 |
|------|--------------|------|
| `layout-dump` | 公开 MCP + CLI | `LayoutDumpMcpToolAction` |
| `view-locate` | 公开 MCP + CLI | `UiFindMcpToolAction` |
| `view-inspect` | 公开 MCP + CLI | `EvalViewMcpToolAction` |
| `tap` | 公开 MCP + CLI | `TapMcpToolAction` |
| `layout-verify` | action 类存在，但未进入 `defaultActions()`，不是当前公开工具 | `LayoutVerifyMcpToolAction` |
| `figma-layout-verify` | action 类存在，但未进入 `defaultActions()`，不是当前公开工具 | `FigmaLayoutVerifyMcpToolAction` |

`layout-verify` 与 `figma-layout-verify` 不要写进公开工具清单，除非先注册到 `McpToolActionRegistry.defaultActions()` 并同步 `tools/list`、CLI/skill 文档。`figma-layout-verify` 的内部算法见 [`08_mcp_figma_layout_verify_internals.md`](08_mcp_figma_layout_verify_internals.md)。

---

## 8. 产物与日志

- MCP 拉取类工具产物落在 `build/jugg/mcp_fetch/<toolName>/`。
- 项目级 `ExpiredArtifactCleaner` 负责清理超过 30 天的 MCP 拉取产物。
- compile/deploy 类日志优先看 `build/jugg/log/compile_latest.log`。
- `wait-logs` 读取 App 日志时会复用 deploy/restart 时间戳作为起点；`LastDeployTimestampRegistry` 按 `projectDir + serial` 隔离设备记录，并仅在设备记录缺失时兼容回退旧项目级记录。

---

## 9. 扩展新工具流程

1. 新增 `McpToolAction` 实现，并定义 `McpToolDefinition`。
2. 注册到 `McpToolActionRegistry.defaultActions()`；若无需 `projectDir`，同步 `noProjectDirTools`。
3. 补充 action 级参数组合校验和成功/失败测试。
4. 同步 [`08_mcp_tools_list.md`](08_mcp_tools_list.md)。
5. 如需 CLI 暴露，同步 `jugg.py::COMMANDS`、`help_registry.py`、对应 `cmd_*.py` 与 [`08_cli_tools_list.md`](08_cli_tools_list.md)。
6. 检查 `docs/skills/jugg-android-dev-loop/` 中的 skill、CLI manual、error patterns 是否仍描述旧行为。

---

## 10. MCP/CLI 变更时的 Skill 同步规则

| 改动类型 | 需检查/更新的 Skill 文档 |
|----------|--------------------------|
| `deploy` / `compile` / `restart` 等命令行为变更（默认参数、重启策略、阻塞/异步等） | `SKILL.md` §Build & Deploy Commands、`flow_compile_deploy.md`、`flow_with_auto_run.md` |
| MCP/CLI 新增或修改参数 | `SKILL.md` §Advanced Commands、`references/cli_manual.md` |
| 错误码或错误消息变更 | `references/error_patterns.md` |
| deploy 后 app 状态变化（是否重启、是否保留 runtime state） | `SKILL.md` §Mandatory Rules、`flow_with_auto_run.md` Step 3 |

判断方法：改动完成后，在上表对应 Skill 文档中搜索与旧行为匹配的描述，有则更新或删除。

---

## 11. 关联文档

- MCP 工具参数清单：`08_mcp_tools_list.md`
- CLI 参数与 MCP 映射：`08_cli_tools_list.md`
- UI 布局验证设计：`08_mcp_layout_verify_design.md`
- figma-layout-verify 内部算法：`08_mcp_figma_layout_verify_internals.md`
- 代码路径速查：`98_code_map.md`
