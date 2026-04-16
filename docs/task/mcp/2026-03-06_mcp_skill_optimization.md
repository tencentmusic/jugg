# MCP / Skill 优化计划落地记录（2026-03-06）

## 范围

- MCP 运行态工具校验顺序与错误语义优化：
  - `tap`
  - `layout-dump`
  - `activity-stack`
  - `screenshot`
  - `record-start`
  - `record-stop`
- `crash-report` 降噪与输出语义优化。
- Skill 文档同步：
  - `tool_cards_build_deploy.md`
  - `tool_cards_troubleshoot.md`

## 关键变更

1. 运行态工具校验顺序
- 参数组合合法性在 app-ready 校验前执行，参数冲突优先返回 `INVALID_PARAMS`。
- App 未就绪时统一返回 `INTERNAL_ERROR`，并在 message 给出 next action（`restart` + retry）。
- `record-start` / `record-stop` 纳入前置 app-ready 校验链。

2. `crash-report` 降噪
- 改为 `logcat -b crash` 优先，未命中崩溃再补 `logcat -b main`。
- 输出摘要仅保留目标包名/进程名/PID 相关日志。
- `hasCrash=false` 时新增 `data.reason`，显式说明无崩溃原因。
- 仍保留 `allErrorLogPath` artifact，写入原始采集日志（crash/main buffer）。

3. Skill 卡片约束
- compile/deploy fallback 链调整为：
  - `deploy` 重试（最多 3 次）
  - `gradle-build` + 异步轮询
  - 再次 `deploy`
  - 仅在安装态损坏/签名冲突时使用 `reinstall`
- 明确 MCP-only 默认策略；仅 `crash-report` 不可用或结果不可用时允许 adb 兜底，且必须显式标注兜底路径。

## 回归结果

- 运行 `:main:test --tests 'com.sickworm.intellij.jugg.mcp.actions.*'` 通过。
- 新增/调整测试覆盖：
  - `TapMcpToolActionTest`（参数错误优先）
  - `RecordMcpToolActionTest`（`record-start`/`record-stop` app-ready 与参数优先级）
  - `CrashReportMcpToolActionTest`（目标过滤、buffer 优先级、无崩溃 reason）
