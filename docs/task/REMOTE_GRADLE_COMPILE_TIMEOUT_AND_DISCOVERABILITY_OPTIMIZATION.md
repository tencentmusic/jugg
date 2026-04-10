# Remote Gradle Compile 精简优化任务（按维护者约束）

## 1. 背景

本次真实排障暴露两个核心问题：

1. `gradle-build` 在长编译场景下容易表现为 MCP 请求超时。
2. 调用方无法第一时间获知当前是 `local` 还是 `remote` 编译，定位链路成本高。

本任务目标是做最小改动的协议优化：接口尽量少、可观测性足够、无自动修复策略。

## 2. 任务目标

1. 保持 MCP 接口数量尽可能少。
2. 触发编译时可快速返回，不阻塞等待长时间任务完成。
3. 使用 `jobId` 作为编译任务唯一标识。
4. 编译状态统一通过 `get_compile_status(jobId)` 查询。
5. 触发响应返回 `executionType=local|remote`, `logPath=build/jugg/log/compile_latest.log`。
6. `build/jugg/log/compile_latest.log` 主要用于编译失败后的排障读取。
7. 提供“申请 SSH 登录信息”的 MCP tool，但必须先征求用户同意，并由 IDE 弹窗确认。

## 3. 约束与注意事项

1. **接口最小化**：仅保留必要工具，不扩散出大量查询接口。
2. **保留状态查询**：`get_compile_status` 是必需能力。
3. **日志策略**：不新增 `get_compile_log`；日志只走本地文件 `build/jugg/log/compile_latest.log`。
4. **`compile_latest.log` 语义**：
   - 编译失败时：作为主要排障入口。
   - 编译成功后：系统会刷新到最新文件，不能把它当作“本次成功结果唯一真相”。
5. **成功判定来源**：以 `get_compile_status(jobId)` 返回为准。
6. **无 Streamable HTTP / Notification**：本方案不提供推送通道。
7. **无自动修复**：不自动清缓存、不自动重试。
8. **无错误分类体系**：不维护 error code，Agent 直接读日志判断。
9. **SSH 信息敏感流程**：
   - 默认不暴露。
   - 仅在 Agent 明确需要远端人工排障时，先询问用户。
   - 用户同意后，才可调用 SSH 信息 tool。
   - 调用时 IDE 必须弹窗二次确认，并保留审计信息。

## 4. 方案建议（精简版）

## 4.1 MCP 接口最小集

建议仅保留/新增以下能力：

1. `gradle-build`（增强现有）
2. `get_compile_status`（保留/新增，必需）
3. `ssh-info`（新增，低频）

不建议新增：

1. `get_compile_log`
2. Streamable HTTP / Notification 相关接口
3. 自动修复类接口
4. 错误分类查询接口

## 4.2 `gradle-build` 自适应超时策略（Adaptive Response）

在 Tool 内部设置**软超时阈值 25 秒**。

### 逻辑

1. Tool 启动，创建 `jobId`，开始执行编译任务。
2. 同时启动 25 秒内部计时器。
3. 若任务在 25 秒内完成：直接返回最终结果。
4. 若 25 秒到达且任务未完成：立即返回中间状态。

### 中间状态返回文案（建议）

`任务仍在运行，请通过 get_compile_status 关注进度，Job ID 为 <jobId>`

### 返回字段建议

1. `accepted`: `true|false`
2. `jobId`: 编译任务 ID（必返）
3. `executionType`: `local|remote`
4. `logPath`: 固定 `build/jugg/log/compile_latest.log`
5. `isFinal`: `true|false`
6. `status`: `success|failed|running`
7. `message`: 简短说明

说明：

1. 若 `isFinal=true`，表示 25 秒内已拿到终态结果。
2. 若 `isFinal=false` 且 `status=running`，调用方必须用 `jobId` 继续查询。

## 4.3 `get_compile_status(jobId)` 最小返回建议

目标是“够用即可”，不引入复杂状态机。

字段建议：

1. `jobId`
2. `status`: `running|success|failed|canceled|unknown`
3. `executionType`: `local|remote`
4. `finishedAt`（完成时返回）
5. `message`（可选简短文本）

说明：

1. `status` 只保留最小集合，不引入细粒度阶段枚举。
2. 调用方以 `status` 判断成功失败。

## 4.4 SSH 信息工具（低频）

工具名示例：`ssh-info`。

行为约束：

1. 工具仅返回 SSH 登录信息，不直接执行远端命令。
2. 调用前必须已获得用户明确同意。
3. 调用时 IDE 二次确认弹窗。
4. 必须记录审计字段（调用人、时间、理由、确认结果）。

## 4.5 Agent 协作约定

1. 先调用 `gradle-build`，拿到 `jobId`。
2. 若 `isFinal=false`，轮询 `get_compile_status(jobId)` 直到终态。
3. 当 `status=failed` 时，读取 `build/jugg/log/compile_latest.log` 进行原因分析。
4. 若确需远端登录排障，先征求用户同意，再申请 SSH 信息。

## 5. 验证方式

## 5.1 功能验证

1. 触发 `gradle-build`：
   - 返回中包含 `jobId`、`executionType`、`logPath`。
2. 快任务（<=25 秒）：
   - 期望直接返回终态（`isFinal=true`）。
3. 慢任务（>25 秒）：
   - 期望在约 25 秒时返回中间态（`isFinal=false`、`status=running`）。
   - 且 message 包含“通过 get_compile_status 关注进度，Job ID 为 XXX”。
4. 查询状态：
   - `get_compile_status(jobId)` 能返回 `running` 到终态（`success|failed|canceled`）。
5. 失败场景：
   - `status=failed`。
   - `build/jugg/log/compile_latest.log` 可用于定位错误。

## 5.2 SSH 流程验证

1. 未经用户同意时，Agent 不得调用 `ssh-info`。
2. 用户同意后调用工具，IDE 出现确认弹窗。
3. 用户拒绝时调用失败且可审计。
4. 用户确认时返回 SSH 信息并记录审计。

## 5.3 回归验证

1. 不影响现有 `compile` / `gradle-build` 主流程。
2. local 与 remote 场景都能返回正确 `executionType`。
3. 旧调用方忽略新增字段仍可继续工作。

## 6. 交付物

1. `gradle-build` 与 `get_compile_status` 响应字段说明（含 JSON 示例）。
2. `gradle-build` 25 秒 Adaptive Response 说明（含中间态示例文案）。
3. `ssh-info` 工具说明（授权流程 + IDE 确认 + 审计）。
4. 最小使用说明：
   - 触发后拿 `jobId`
   - 通过 `get_compile_status(jobId)` 判断成功/失败
   - 失败时看 `build/jugg/log/compile_latest.log`

## 7. 同步要求

本次策略调整完成后，需同步更新：

1. `ai_knowledge` 文档
2. `skills`（与 Jugg 编译排障相关技能）

说明：本任务文档只定义策略与接口要求，具体更新文件由 jugg 工程 session 按仓库约定执行。
