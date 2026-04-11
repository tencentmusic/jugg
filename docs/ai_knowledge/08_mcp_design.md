# MCP 设计说明（当前实现视角）

> 最后核对：2026-03-12  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述 MCP 的实现分层、校验策略与扩展方式，不提供具体工具参数表。

---

## 2. 分层结构

| 层 | 核心类 | 作用 |
|----|--------|------|
| HTTP 服务层 | `McpLocalServer` | 监听端口、处理 HTTP 请求、基础安全校验 |
| 通用协议层 | `McpBaseInvoker` | initialize/ping/resources/prompts/tools-list 等通用方法 |
| 工具执行层 | `McpToolInvoker` | 参数校验、工具路由、结果映射 |
| 校验层 | `McpRequestValidator` | schema 校验、默认值填充、projectDir 授权检查 |
| 注册层 | `McpToolRegistry`, `McpToolActionRegistry` | 工具定义与 action 注册 |
| 设备端桥接层 | `mcp/viewhierarchy/ViewHierarchyClient` + `jvmti_agent/.../viewhierarchy/*` | `layout-dump` / `tap` 元素模式 / `view-inspect` 的 App 内 LocalSocket 通道（Server-only，无 uiautomator 回退） |
| 布局验证层 | `mcp/layout/*` | `view-locate` / `figma-layout-verify` 的核心算法模块 |
| 运行时适配层 | `IMcpRuntime`, `IdeaMcpRuntime` | 将工具执行连接到 IDE 真实能力 |

---

## 3. 协议与约束

- 传输：HTTP + JSON-RPC 2.0。  
- 主入口：`/jugg-mcp`。  
- 统一业务返回：`structuredContent` 内含 `status/message/data/artifacts/errorCode`。  
- 工具调用前必须经过 schema 校验与项目初始化校验（除 `list_projects`）。
- 运行态工具在 action 内执行"参数组合校验 -> App ready 校验 -> 业务执行"；其中参数错误优先返回 `MCP_INVALID_PARAMS`，App 未就绪返回 `MCP_INTERNAL_ERROR`，并附带 next action 建议。
- `restart` 支持可选 `tap_actions`，用于在重启后串行执行触控导航；单步参数与 `tap` 工具保持一致（`tap/longPress/swipe` + 坐标/百分比/元素模式，`swipe` 不支持元素模式）。步骤失败会短路并返回失败步骤索引。

---

## 4. 错误模型

- 协议级错误：映射为 JSON-RPC error。  
- 业务级失败：通常仍走 tool result，`status=ERROR`，保留 `data/artifacts`。

这一设计可避免客户端把业务失败误判为协议失败。

---

## 5. 异步编译设计

`CompileJobManager` 提供统一异步任务模型：
- 触发接口可能先返回 `isFinal=false`。  
- 客户端通过 `get_compile_status(jobId)` 查询终态。  
- 运行中状态会返回轮询建议字段（`pollIntervalSuggestedMs`），用于避免高频查询。  
- 使用 `compile_latest.log` 作为统一日志出口路径。

---

## 6. ViewHierarchy 可靠性约束

- `ElementFinder` 在 selector 命中前先过滤不可操作节点（可见、已显示、非零尺寸、有效 bounds）。
- `ViewHierarchyServerLoader` 仅在 `ViewHierarchyServer.start(...)` 成功后设置初始化标志，失败可重试。
- `ViewHierarchyClient` 在多进程场景按"主进程优先 -> 其余 PID -> `jugg_vh` 兼容名"尝试 socket，避免首个 PID 误选导致不可用。
- ViewHierarchy socket 响应包含 `version` 字段；客户端当前为 warn-only 策略（版本不匹配仅告警，不拦截请求）。
- **Versioning rule**: 当 `ViewHierarchyServer` 响应结构发生 breaking change（字段删除/类型变更/语义不兼容）时，必须递增服务端协议版本号，并同步客户端常量与文档。

---

## 7. UI 布局验证设计

> 设计详情参见：`docs/task/ui_fuzzy_match_design_v3.md`

### 7.1 设计哲学

**核心原则**：调用成功率优先，支持范围其次

**关键决策**：
- **验证相对关系而非绝对位置**：屏幕比例不同导致 bounds 绝对值不可比，相对关系（间距/对齐）才是布局本质
- **MCP 自动提取期望值**：Agent 只需提供 `figmaJson + dpr`，MCP 自动从 Figma 提取所有相对关系并验证
- **IoU 模糊匹配**：解决 Figma 与 Android 结构不一致问题，打平树结构用 IoU 匹配

### 7.2 工具体系

| 工具 | 状态 | 职责 |
|------|------|------|
| `view-locate` | **新增** | 根据文本/位置查找元素，返回 bounds + position + size |
| `figma-layout-verify` | **新增** | 自动提取 Figma 相对关系并批量验证 |
| `layout-dump` | 保留 | `view-locate` 内部调用 |
| `layout_verify` | **废弃** | 被 `view-locate` + `figma-layout-verify` 替代 |
| `view-inspect` | 保留 | 颜色验证时可能需要 |

### 7.3 核心模块架构

```
┌─────────────────────────────────────────────────────────┐
│                    MCP Tool Layer                        │
│  ┌─────────────────┐      ┌──────────────────────┐    │
│  │   view-locate    │      │ figma-layout-verify  │    │
│  │  (单点查询)       │      │   (批量验证)          │    │
│  └────────┬─────────┘      └──────────┬───────────┘    │
└───────────┼───────────────────────────┼─────────────────┘
            │                           │
┌───────────┼───────────────────────────┼─────────────────┐
│           │      Core Logic Layer     │                 │
│           ▼                           ▼                 │
│  ┌─────────────────┐      ┌──────────────────────┐    │
│  │  ElementFinder  │      │  RelationExtractor   │    │
│  │  (元素查找)      │      │  (关系提取)           │    │
│  └─────────────────┘      └──────────────────────┘    │
│           │                           │                 │
│           ▼                           ▼                 │
│  ┌─────────────────┐      ┌──────────────────────┐    │
│  │  ElementMatcher │      │  RelationVerifier    │    │
│  │  (IoU 模糊匹配)  │      │  (关系验证)           │    │
│  └─────────────────┘      └──────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

### 7.4 关键算法

#### 7.4.1 IoU 模糊匹配

用于匹配 Figma 节点到 Android View：

```kotlin
fun calculateIoU(bounds1: IntArray, bounds2: IntArray): Float {
    // Intersection over Union
    // 归一化到相同屏幕尺寸后计算
    // 置信度 >= 0.7 判定为匹配成功
}
```

#### 7.4.2 自动提取相对关系

从 Figma JSON 自动提取：

- **间距关系 (spacing)**：水平/垂直相邻元素的距离
- **对齐关系 (alignment)**：按坐标分组检测对齐

```kotlin
class RelationExtractor(private val dpr: Float) {
    fun extractRelations(figmaJson: FigmaNode): List<Relation>
    // 相邻判断容差: 20dp
}
```

#### 7.4.3 固定容差标准

参考 Android Espresso 标准，**不支持自定义**：

- **绝对容差**：±2dp
- **百分比容差**：±5%
- **判断逻辑**：`absDiff <= 2dp OR percentDiff <= 5%`

### 7.5 dpr 参数处理

| Figma 画板宽度 | dpr 值 | 说明 |
|---------------|--------|------|
| 750px | 2 | 2x 像素设计稿 |
| 375px | 1 | dp 单位设计稿 |
| 1125px | 3 | 3x 像素设计稿 |
| 411px | 1 | Android dp 设计稿 |

**验证逻辑**：检查画板宽度是否为常见值，不常见时返回 warning。

### 7.6 返回格式

#### view-locate 返回

```json
{
  "status": "OK",
  "data": {
    "matched": {
      "selector": {"text": "Avatar", "className": "KRView"},
      "bounds": [16, 278, 67, 324],
      "position": {"left": "16dp", "top": "278dp", "centerX": "41.5dp", "centerY": "301dp"},
      "size": {"width": "51dp", "height": "46dp"}
    },
    "confidence": 0.92
  }
}
```

#### figma-layout-verify 返回

```json
{
  "status": "OK",
  "data": {
    "summary": {"total": 15, "passed": 12, "failed": 3},
    "results": [
      {
        "type": "spacing",
        "element1": {"figmaId": "34:12200", "androidSelector": {"text": "Avatar"}},
        "element2": {"figmaId": "34:12202", "androidSelector": {"text": "App"}},
        "axis": "x",
        "expected": "18dp",
        "actual": "14dp",
        "match": false,
        "diff": "-4dp"
      },
      {
        "type": "alignment",
        "elements": [...],
        "axis": "y",
        "expected": "centerY aligned",
        "actual": "centerY: [293, 293, 293, 293]",
        "match": true
      }
    ],
    "unmatched": [
      {"figmaId": "34:12179", "figmaName": "Group 1912055492", "reason": "No similar element found"}
    ]
  }
}
```

### 7.7 使用场景

#### 场景 1: 有 Figma 设计稿 - 批量验证

```
用户: "检查这个页面是否符合 Figma 设计稿"

Agent 流程:
1. 调用 figma-layout-verify(figmaJson="design.json", dpr=1)
2. 获取验证报告
3. 输出差异摘要，根据 diff 修复代码
```

#### 场景 2: 无 Figma - 单点验证

```
用户: "Avatar 和 App 按钮之间的间距"

Agent 流程:
1. 调用 view_locate(target={text:"Avatar"})
2. 调用 view_locate(target={text:"App"})
3. 计算间距: spacing = app.bounds[0] - avatar.bounds[2]
4. 询问: "当前间距 18dp，期望间距是多少?"
```

#### 场景 3: 部分验证

```json
{
  "projectDir": "/path/to/project",
  "figmaJson": "design_structure.json",
  "dpr": 1,
  "targetNodes": ["34:12200", "34:12202"]
}
```

### 7.8 Kuikly 框架支持

`KuiklyViewResolver` 支持从 Kuikly 特有 View 提取属性：

- **KRRichTextView / KRGradientRichTextView**：文本提取
  - 主路径：`view.richTextShadow.textProps.text`
  - 回退路径：`view.textLayout.getText()`

### 7.9 实现优先级

| Phase | 内容 | 状态 |
|-------|------|------|
| Phase 1 | `view-locate` 基础功能、Bounds 归一化/匹配/验证 | MVP |
| Phase 2 | `figma-layout-verify` 批量验证、returnMode="all" | 进行中 |
| Phase 3 | 颜色验证（仅纯色）| 待定 |

### 7.10 设计文档

- 详细设计：`docs/task/ui_fuzzy_match_design_v3.md`
- 问题澄清：`docs/task/ui_fuzzy_match_design_clarifications.md`
- 开发方案：`docs/task/ui_fuzzy_match_implementation_plan.md`

---

## 8. 扩展新工具建议

1. 新增 `McpToolAction` 实现。  
2. 定义 `McpToolDefinition`（含 input/output schema）。  
3. 注册到 `McpToolActionRegistry.defaultActions()`。  
4. 在 `08_mcp_usage.md` 同步用途与参数。  
5. 增加对应测试（参数校验 + 成功/失败路径）。

---

## 9. 关联文档

- 使用说明：`08_mcp_usage.md`
- 代码定位：`98_code_map.md`
- UI 验证设计：`docs/task/ui_fuzzy_match_design_v3.md`
