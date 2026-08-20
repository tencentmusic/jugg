# CLI：未初始化项目可发现性 + view-inspect 字段读取

## 背景

在 `android_demo_project` 真机验证中：

- 目标工程尚未被 Jugg 初始化时，`jugg status` / `jugg devices` 只返回 `PROJECT_NOT_INITIALIZED`，不列出当前 Runtime 已打开的工程。Agent 只能绕开 CLI 直接调 MCP `list-projects`。
- `view-inspect` 表达式 `getLayoutParams().leftMargin` 在解析阶段失败：`expected '(' after method name 'leftMargin'`。`getLayoutParams().getMarginStart()` 作为方法调用是成功的。

`deploy` 成功文案与 `detail` 不一致本次不处理。standalone 缺 jar 是独立 CLI 回退，符合预期，不处理。

## 已确认事实

- CLI 实际命中的未初始化错误来自 `IdeaMcpRuntime.invokeMcp()`：`JuggInitializer.getManager(projectDir)` 为空时返回 `invoke_mcp failed. Reason: project is not initialized.`。`McpResultMapper.toolError()` 已支持附带 `data`。
- MCP `list-projects` 已存在，且在 `noProjectDirTools` 中。本次**不**公开为 CLI 子命令。
- `--project-dir` 显式传入时，CLI 会把该路径原样发给 MCP，即使 Runtime 是靠父目录前缀匹配到的。这正是 demo 子工程未打开时的失败路径。
- `view-inspect` 表达式在 App 内由 `ViewExpressionEvaluator` 求值。解析不是 `split(".")`：按字符位置逐段读 identifier，段之间才要求 `.`。当前每段强制 `(` `)`，所以 `getLayoutParams().leftMargin` 在 `leftMargin` 处失败。
- `leftMargin` 是 `ViewGroup.MarginLayoutParams` 的 Java public 字段；`marginStart` / `layoutParams` 在 Kotlin 里是 property，对应 `getMarginStart()` / `getLayoutParams()`。
- 求值侧已有安全闸：blocked prefix、getter allowlist。现有 L1 owner 是 `ViewExpressionEvaluatorTest`。

## 推荐方案

两项都做最小实现，不引入新抽象层。

### 2. 未初始化时能看见已打开工程

**主路径：给 `PROJECT_NOT_INITIALIZED` 附上已初始化项目列表。**

这是失败现场本身该带的信息，Agent 不必先猜要调哪个工具。

错误仍用 `errorCode=PROJECT_NOT_INITIALIZED`。`message` 追加已初始化项目路径；`data.projects` 复用 `list-projects` 同结构，避免 Agent 解析两套字段。

```text
status failed. Reason: project is not initialized.
Requested: /Users/me/foo/android_demo_project
Initialized projects:
  /Users/me/foo/jugg
  /Users/me/bar/app
```

无已初始化项目时，`data.projects` 为空数组，message 明确写 `Initialized projects: (none)`。

实现落在 `IdeaMcpRuntime.invokeMcp()` 这一条 CLI 真实路径。列表来源复用 `PlatformApi.getInitializedProjectDirs()`，与 `ListProjectsMcpToolAction` 一致。

不公开 `jugg list-projects`。`McpRequestValidator` 的另一条 `PROJECT_NOT_INITIALIZED` 本次不扩 `Invalid.data`。

### 4. view-inspect 无括号 identifier 双读

无 `()` 的 identifier 在**求值期**双读，而不是只当 Java 字段。解析期只记录段名；有 `()` 的仍是显式方法调用，行为不变。

```text
expression := access ("." access)*
access     := method_call | name
method_call := name "()" | name "(" literal ")"
```

无括号 `name` 的求值顺序：

1. public instance 字段 `name`（`Class.getField`，不用 `getDeclaredField`）
2. 无参 getter：
   - `name` 已是 `get`/`is`/`has`/`can`/`should` 前缀 → 直接当方法名（`getLayoutParams` → `getLayoutParams()`）
   - 否则按 Kotlin property：`get` + 首字母大写（`layoutParams` → `getLayoutParams()`，`leftMargin` → `getLeftMargin()`，`marginStart` → `getMarginStart()`）
   - 仍没有则再试 `is` + 首字母大写（`enabled` → `isEnabled()`）

显式 `getLayoutParams()` 保持现状，不走字段分支。方法名仍过现有 allowlist / blocked prefix。字段名也拦 blocked prefix。字段与 getter 步骤计入 `MAX_CHAIN_DEPTH`。标识符大小写敏感，不把 `layoutparams` 当成 `layoutParams`。

应同时成功的写法：

```text
getLayoutParams().leftMargin
getLayoutParams().getMarginStart()
layoutParams.leftMargin
getLayoutParams.leftMargin
layoutParams.marginStart
```

`leftMargin` 以字段命中为主（SDK 没有 `getLeftMargin()`）。`layoutParams` / `marginStart` 以 getter 命中。禁止赋值、下标、静态 Class 字段。

## 改动清单

### 项 2

| 路径 | 职责 |
|---|---|
| `idea/src/main/java/com/sickworm/intellij/jugg/ai/mcp/IdeaMcpRuntime.kt` | `getManager` 为空时构造带 `data.projects` 的 `PROJECT_NOT_INITIALIZED` |
| `docs/skills/jugg-android-dev-loop/references/error_patterns.md` | 未初始化时读取 message / `data.projects` |
| `docs/ai_knowledge/08_mcp_tools_list.md` | `PROJECT_NOT_INITIALIZED` 返回已初始化项目 |
| `docs/wiki/zh/reference/mcp-tools.md` | 错误码说明 |

不新增 Java/Kotlin 公共类。列表序列化直接在 `IdeaMcpRuntime` 内复用 `list-projects` 已有字段名。不改 CLI 子命令表。

### 项 4

| 路径 | 职责 |
|---|---|
| `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/viewhierarchy/ViewExpressionEvaluator.java` | 无括号段双读：字段优先，再 Kotlin/`getXxx`/`isXxx` |
| `jvmti_agent/src/test/java/com/sickworm/intellij/jugg/viewhierarchy/ViewExpressionEvaluatorTest.java` | 解析、双读顺序、安全、原 getter 回归 |
| `docs/ai_knowledge/08_mcp_tools_list.md` | 无括号 identifier 的字段/getter 双读 |
| `docs/ai_knowledge/08_mcp_layout_verify_design.md` | 如有“仅 getter”表述则同步 |
| `docs/skills/jugg-android-dev-loop/references/cli_manual.md` | 示例含 `layoutParams.leftMargin` 与 `getLayoutParams().getMarginStart()` |
| `docs/wiki/zh/capabilities/tools/ui-automation.md` | 能力说明 |
| `docs/wiki/zh/guide/ui-inspection.md` | 常用表达式 |
| `docs/wiki/zh/reference/cli-commands.md` / `mcp-tools.md` | 参数说明 |

`EvalViewMcpToolAction` 的 schema/description 若写死“getter method”则改成只读表达式，不改参数形状。

## 测试与验证

### 项 2

- 价值：错误 payload 是 Agent 可观察契约，可能被静默退化成空列表或旧文案。
- Owner：`McpInvokerErrorHandlingTest` 覆盖 validator 文案保持 `project is not initialized`；`IdeaMcpRuntime` 若现有测试难以驱动 `getManager==null`，用定向手工验证补齐：对未打开工程跑 `jugg --console=json status`，确认 `errorCode` 与 `data.projects`。
- 层级：L1 错误契约；CLI 用命令输出替代验证。

### 项 4

- 价值：表达式语言是稳定公开契约。
- Owner：`ViewExpressionEvaluatorTest`。
- 先改测试再改实现。覆盖：
  - 无括号 identifier 可解析，不再在 parse 阶段要求 `(`。
  - 字段优先：对象有 public `leftMargin` 时读字段，即使同时有 `getLeftMargin()`。
  - Kotlin property：`layoutParams` → `getLayoutParams()`，`marginStart` → `getMarginStart()`。
  - 无括号 getter 名：`getLayoutParams` → `getLayoutParams()`。
  - `isEnabled` 风格：`enabled` → `isEnabled()`。
  - blocked prefix 拒绝。
  - 字段和 getter 都不存在时失败。
  - 原有 `getText()` 链回归不被破坏。
  - 可用普通 Java 对象模拟，不必 Android SDK。
- 层级：L1。
- 替代验证：demo 上对可见 View 跑一次 `view-inspect --resource-id ... "getLayoutParams().leftMargin"`。字段求值在 App 内 agent，验证前需该 agent 已随当前改动部署。

定向测试示例：

```bash
./gradlew :jvmti_agent:test --tests com.sickworm.intellij.jugg.viewhierarchy.ViewExpressionEvaluatorTest
./gradlew :main:test --tests com.sickworm.intellij.jugg.ai.mcp.McpInvokerErrorHandlingTest
./gradlew :idea:compileKotlin
```

禁止无过滤的 `:main:test` / `:idea:test`。

## 非目标

- 不处理 `deploy` 的 `No pending file changes` 与 `detail` 不一致。
- 不修复 standalone 缺 jar。
- 不给 `McpValidationResult.Invalid` 加 `data` 字段。
- 不支持字段赋值、数组下标、大小写折叠（`layoutparams` ≠ `layoutParams`）。
- 不公开 `jugg list-projects`，也不把 `get-compile-status` 做成公开 CLI。
- 不改 `PROJECT_NOT_INITIALIZED` 错误码本身。

## 风险

- 字段求值依赖设备上的 Jugg agent。只更新 IDE 插件、未重新部署 runtime 时，旧 agent 仍会拒绝无括号字段。
- `getText` 无括号会走双读：无同名字段时因已是 getter 前缀而调用 `getText()`，因此也会成功。
- 同一名字既有字段又有 getter 时字段优先，可能和 Kotlin 合成 property 不一致；Android `leftMargin` 正需要字段优先。
