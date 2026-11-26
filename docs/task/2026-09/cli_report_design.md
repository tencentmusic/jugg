# CLI Report 两阶段确认方案

## 背景

`jugg` CLI 需要新增问题上报能力，并同时支持 IDEA 与 standalone Runtime。用户必须在诊断包已经生成、能够看到最终待上传内容后，再决定是否上传。

## 用户行为

用户执行 `jugg report` 后，Jugg 先收集、脱敏并生成最终 ZIP，然后展示：

- 本地 ZIP 路径与总大小
- 固定 HTTPS 上传地址
- ZIP 中每个条目的路径和大小

确认提示使用 `[Y/n]`，用户直接回车、输入 `y` 或 `yes` 时上传。其他输入、EOF 或中断均不上传，生成的诊断包保留在本地并沿用现有诊断临时文件清理策略。文件清单按 IDEA 规则将 Jugg logs 排在最前，其余保持生成顺序；CLI 不显示敏感等级和脱敏状态。`--console=json` 暂时使用相同交互。

## 实现方案

采用两个 MCP 工具完成单个 CLI 命令：

1. `report-prepare` 收集白名单诊断信息，完成脱敏，生成 manifest 和最终 ZIP，并返回 `reportId`、ZIP 路径、大小、SHA-256、上传地址和条目清单。
2. CLI 展示上述精确内容并请求确认。
3. `report-upload` 接收 `reportId` 与 SHA-256，重新从项目 diagnostics 目录加载 ZIP，校验路径、manifest、条目和 SHA-256 后上传。

上传成功 message 与 IDE 使用相同文案，最终结果只返回 `reportId`，不暴露 entries、本地临时路径或 file artifact。

IDEA 与 standalone 注册相同的两个 MCP action，复用现有 `IssueReportBundleBuilder` 和 `IssueReportUploader`。CLI 不提供跳过确认的 `--yes`，也不在第一版提供逐项选择或自定义上传地址。

## 变更范围

- `main/.../diagnostics`：增加安全读取已生成诊断包的边界，补充 ZIP SHA-256。
- `main/.../ai/mcp/actions`：新增 prepare/upload actions 并注册工具。
- `cmd_line/.../standalone/StandaloneProjectRegistry.kt`：开放 standalone report capabilities。
- `docs/skills/jugg-android-dev-loop/scripts`：新增 `report` 子命令、确认交互和帮助，并递增 CLI/skill 版本。
- `docs/ai_knowledge`：同步 CLI、MCP 工具清单和代码地图。

## 验证策略

- L1：诊断包读取与校验 owner，覆盖 manifest/ZIP 一致、SHA-256 篡改、非法 reportId 和目录边界。
- CLI：覆盖确认、拒绝、EOF 和 JSON/非交互边界，确认拒绝时不得调用 upload。
- MCP：覆盖 prepare 结构化结果、upload 校验失败和 capability 注册。
- 定向执行相关 main 测试、Python CLI 测试、Python 3.7 兼容检查与 `:idea:compileKotlin`。

## 明确不包含

- `--yes` 或其他跳过确认方式
- 自定义上传地址
- CLI 逐项选择诊断条目
- 离线上报队列和自动重试
- 修改现有 IDEA 图形化 Report Issue 流程
