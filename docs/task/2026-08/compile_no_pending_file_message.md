# jugg compile 无文件变更提示

## 背景

`jugg deploy` 在没有待部署文件时会返回明确的 `No pending file changes` 成功提示，但 `jugg compile` 当前只返回 `Compiled files (total: 0)`，不能直接表达本轮没有文件需要编译。

## 目标行为

当 MCP `compile` 成功且本轮 `compiledFiles` 为空时，返回：

```text
compile executed successfully. No pending file changes.
```

存在编译文件时继续返回原有文件摘要。`compile` 仍然只编译不部署，不展示最后一次成功部署的时间或文件列表。

无论任务在首次调用内完成，还是超过 soft timeout 后通过 `get-compile-status` 轮询完成，`compile` 和 `deploy` 的最终消息都应保持一致。无文件时分别保留 compile 的 no-pending 提示，以及 deploy 的 no-pending 与最近一次变化部署摘要。

## 改动范围

- `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/CompileAndDeployMcpToolAction.kt`
  - 增加 compile 无文件时的专属成功提示。
  - 保持 deploy 无文件提示及部署历史摘要不变。
- `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/CompileJobManager.kt`
  - 保存调用方提供的成功消息，使异步轮询终态与首次调用内完成时一致。
- `main/src/test/java/com/sickworm/intellij/jugg/ai/mcp/actions/CompileAndDeployMcpToolActionTest.kt`
  - 更新零文件 compile 的可观察结果断言。
  - 增加 compile 和 deploy 异步轮询终态消息回归。
  - 复用现有有文件 compile 和无文件 deploy 回归。
- `docs/ai_knowledge/08_mcp_tools_list.md`
  - 说明 MCP `compile` 的无文件成功语义。
- `docs/ai_knowledge/08_cli_tools_list.md`
  - 说明 `jugg compile` 的无文件终态提示。
- `docs/wiki/zh/capabilities/tools/run-context-and-no-change.md`
  - 将无变化结果说明扩展到 compile。

## 验证策略

- 失败证据：现有测试期望 `Compiled files (total: 0)`，修改为新契约后应先失败。
- 测试价值：该消息是 MCP/CLI 用户可观察的稳定行为，使用现有 action 测试 owner 保护。
- 测试层级：`CompileAndDeployMcpToolActionTest`，L1 action 行为测试。
- 最终验证：定向执行同步与异步相关测试、执行 `:main:compileKotlin`、检查文档与 diff，并确认未修改 IDE Run/Deploy 文案。

## 非目标

- 不修改 CLI Python 输出逻辑。
- 不修改 IDE Run/Deploy 气泡提示。
- 不为 compile 增加部署历史信息。
