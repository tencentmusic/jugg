# MCP UI 布局验证设计

> 最后核对：2026-05-23
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页说明当前 MCP UI 验证能力的公开工具边界、核心数据流和容易误判的限制。它不复述完整参数表；参数以 [`08_mcp_tools_list.md`](08_mcp_tools_list.md)、`McpToolActionRegistry.defaultActions()` 和运行时 `tools/list` 为准。

当前可对外使用的 UI 证据链是：

```text
activity-stack
  -> layout-dump / view-locate / view-inspect
  -> tap（需要交互时）
  -> wait-logs（需要运行时日志闭环时）
```

`layout-verify` 与 `figma-layout-verify` 的 action 类仍存在，但没有注册到 `McpToolActionRegistry.defaultActions()`，不是当前公开 MCP tool；不要在 Agent 流程或公开工具清单中承诺可直接调用。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---------|------|------|
| `McpToolActionRegistry` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolActionRegistry.kt` | 公开工具注册表；判断 UI 工具是否真的可通过 MCP 调用的第一入口 |
| `LayoutDumpMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/LayoutDumpMcpToolAction.kt` | 公开 `layout-dump`，导出 HTML 视图树 artifact |
| `LayoutDumpHelper` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/LayoutDumpHelper.kt` | 复用的内部 dump 能力；生成公开 HTML 和内部 JSON |
| `UiFindMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/UiFindMcpToolAction.kt` | 公开 `view-locate`，按 text/resourceId/contentDesc 查元素 bounds |
| `EvalViewMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/EvalViewMcpToolAction.kt` | 公开 `view-inspect`，通过 App 内反射读取 getter 链 |
| `TapMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/TapMcpToolAction.kt` | 公开 `tap`，支持坐标、百分比和元素选择器模式 |
| `McpAppReadyGuard` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpAppReadyGuard.kt` | runtime observe/mutate 工具的 App 在线、前台和设备交互态检查 |
| `ViewHierarchyClient` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/viewhierarchy/ViewHierarchyClient.kt` | IDE 侧 LocalSocket 客户端，连接 App 内 ViewHierarchy server |
| `ViewHierarchyServer*` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/viewhierarchy/` | App 内视图树、点击、反射查询服务 |
| `LayoutVerifyMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/LayoutVerifyMcpToolAction.kt` | 未注册的旧批量断言 action；只能作为历史实现或内部参考 |
| `FigmaLayoutVerifyMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/FigmaLayoutVerifyMcpToolAction.kt` | 未注册的 Figma 关系验证 action；算法细节见 `08_mcp_figma_layout_verify_internals.md` |

---

## 3. 公开工具边界

| 工具 | 当前状态 | 适合回答的问题 | 不适合回答的问题 |
|------|----------|----------------|------------------|
| `activity-stack` | 公开 MCP | 当前页面是否在目标 Activity | 具体 View 属性 |
| `layout-dump` | 公开 MCP + CLI | 全局视图树、候选节点、窗口/弹窗结构 | 直接断言颜色、字号等 View getter 属性 |
| `view-locate` | 公开 MCP + CLI | 元素是否存在、bounds、size、间距、对齐 | maxLines、ellipsize、颜色、圆角等内部属性 |
| `view-inspect` | 公开 MCP + CLI | getter 可读的 View 属性、density、隐藏但仍在树里的 View 属性 | 点击坐标、是否安全可点 |
| `tap` | 公开 MCP + CLI | 执行 tap/long-press/swipe | 作为验证工具替代 `view-locate` |
| `wait-logs` | 公开 MCP + CLI | App 日志 marker、crash、auto-run 闭环 | UI 几何属性 |
| `layout-verify` | 未注册 | 旧批量断言实现参考 | 公开 MCP/CLI 调用 |
| `figma-layout-verify` | 未注册 | 内部算法研究 | 公开 MCP/CLI 调用 |

---

## 4. 核心数据流

### 4.1 无 Figma 的 UI 证据链

```text
activity-stack
  -> 确认当前页面，避免在错误 Activity 上采证
layout-dump
  -> App 内 ViewHierarchy LocalSocket dump
  -> LayoutDumpHelper 输出 HTML artifact，并保留内部 JSON
view-locate
  -> 复用内部 JSON，按 text/resourceId/contentDesc 精确匹配节点
  -> 返回 bounds/position/size/matchCount/matches
view-inspect
  -> 通过 ViewHierarchyClient 在 App 侧执行 getter 链
  -> 返回 expression/value/type/density
```

间距与对齐目前由 Agent 根据 `view-locate` 返回的 dp bounds 计算：

```text
horizontalSpacing = rightElement.left - leftElement.right
verticalSpacing   = bottomElement.top - topElement.bottom
centerX           = (left + right) / 2
centerY           = (top + bottom) / 2
```

推荐判定口径沿用旧批量验证约束：绝对差 `<= 2dp`，或相对差 `<= 5%`。该口径是 Agent 报告约定，不代表当前公开工具有 `tolerance` 参数。

### 4.2 有 Figma 的 UI 证据链

```text
Figma 结构化数据
  -> Agent 从设计稿提取 expected value（尺寸、间距、对齐、颜色等）
  -> view-locate 获取 Android actual bounds
  -> view-inspect 获取 getter 属性 actual
  -> Agent 在报告中列出 expected / actual / diff / verdict
```

当前不要调用 `figma-layout-verify`。若需要了解其试验性自动关系提取算法，阅读 [`08_mcp_figma_layout_verify_internals.md`](08_mcp_figma_layout_verify_internals.md)，但公开流程仍必须由 Agent 显式列出 expected value 的来源和计算。

### 4.3 交互后的闭环

```text
tap
  -> App ready guard 检查设备交互态、目标 App 前台、Activity 稳定
  -> 执行坐标/百分比/元素模式触控
  -> activity-stack 或 layout-dump 确认页面变化
  -> wait-logs 在需要时确认 marker/crash/timeout
```

元素模式多匹配时 `tap` 不执行；应先用更强选择器或坐标模式消歧。

---

## 5. 关键模型与单位

| 数据 | 来源 | 单位 / 语义 |
|------|------|-------------|
| `layout-dump` HTML | `LayoutDumpHelper` | 面向 Agent 阅读的公开 artifact |
| 内部 layout JSON | `LayoutDumpHelper.dumpInternal()` | 仅供 action 内部消费，不作为公开 API |
| `view-locate.data.bounds` | `UiFindMcpToolAction` | `[left, top, right, bottom]`，单位 dp |
| `view-locate.data.matchCount` | `UiFindMcpToolAction` | 大于 1 时，首个结果不能直接作为安全点击目标 |
| `view-inspect.data.values` | `EvalViewMcpToolAction` | getter 原始值，Agent 负责解释与换算 |
| `view-inspect.data.density` | App 侧 ViewHierarchy 响应 | px -> dp 换算依据 |
| Figma `dpr` | 设计稿约定 | 只用于 Agent 手动换算或未注册的 Figma 内部算法 |

---

## 6. 隐形约束与容易误判点

| 约束 / 风险 | 影响 |
|-------------|------|
| 注册表是公开能力的唯一可靠入口 | action 类存在不代表 MCP 可调用；先看 `defaultActions()` / `tools/list` |
| `layout-dump` 公开 HTML，不公开内部 JSON | Agent 不应依赖内部 JSON 文件路径作为稳定接口 |
| ViewHierarchy 是 App 内 LocalSocket Server-only | socket 不可用时不要假设会自动回退 uiautomator |
| `view-locate` 目前只按 text/resourceId/contentDesc 匹配 | `figmaNode` 参数存在于 schema，但当前实现没有用它做 IoU 选择 |
| `view-locate` 多命中仍返回首个节点 | `matchCount > 1` 时必须消歧，不能把首个节点当作稳定断言或点击目标 |
| `view-inspect` 可读隐藏节点 | hidden/GONE 节点属性可作为状态证据，但不能证明可点击 |
| `screenshot` action 未注册 | 截图不能作为当前 MCP 公开流程的默认证据来源 |
| `layout-verify` 未注册 | checklist 和报告应使用 `view-locate` / `view-inspect` 的实际输出，而不是旧 `checks[]` 批量断言 |

---

## 7. 排查入口

| 现象 | 优先入口 |
|------|----------|
| Agent 声称某 UI 工具可调用但运行返回 `TOOL_NOT_FOUND` | `McpToolActionRegistry.defaultActions()` 与 `08_mcp_tools_list.md` |
| `view-locate` 找不到元素 | `UiFindMcpToolAction.findMatches()`，再用 `layout-dump` HTML 检查 text/id/contentDesc 是否存在 |
| `view-locate` 多命中 | `view-locate.data.matches[]`，补充更稳定 selector 或改用坐标 |
| 坐标/间距看起来不对 | 检查 bounds 单位是否已是 dp；px 值只能经 `view-inspect.data.density` 换算 |
| `view-inspect` getter 失败 | `EvalViewMcpToolAction` 白名单、App 侧 `ViewExpressionEvaluator` |
| runtime observe 工具报 socket 不可用 | `McpAppReadyGuard`、`ViewHierarchyFailureDiagnoser`、目标 App 前台状态 |
| Figma 自动验证结果与公开工具不一致 | 先确认 `figma-layout-verify` 是否仍未注册；算法问题看 `08_mcp_figma_layout_verify_internals.md` |

---

## 8. 关联文档

- MCP 工具参数清单：`08_mcp_tools_list.md`
- MCP 协议与扩展规则：`08_mcp_design.md`
- figma-layout-verify 内部算法：`08_mcp_figma_layout_verify_internals.md`
- UI 验证检查清单：`08_mcp_ui_verify_checklist.md`
- CLI 封装层：`08_cli_tools_list.md`
- 代码路径速查：`98_code_map.md`
