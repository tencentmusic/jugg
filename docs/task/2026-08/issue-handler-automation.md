# GitHub Issue Handler 自动化方案

## 目标

让 `github-webhook-receiver` 作为 GitHub App 后端，在收到 `tencentmusic/jugg` 的 `issues.opened` 后调用仓库级 `$issue-handler` 完成分诊和代码定位，再由 receiver 校验并通过 GitHub App API 写入标签和评论。当前实验只隔离结构化输出时机，不通过 skill 强制额外调查流程。

## 边界

- GitHub App 负责安装范围、Webhook 来源和短期 API 身份。
- receiver 负责签名、仓库白名单、GitHub API 读取、Delivery 去重、任务持久化、Codex 两阶段监管、输出校验和写回。
- `$issue-handler` 只读取 receiver 提供的不可信 Issue JSON、报告日志与本地项目知识库，根据当前 turn 的要求输出调查结论或最终决策。
- 调查和结构化输出由同一个 Codex session 的两个独立 turn 完成；第一阶段不加载 JSON Schema，第二阶段恢复 session 后才加载最终 Schema。
- 面向报告者的回复跟随 `reply_language`；维护者备注正文固定使用简体中文，receiver 的公开折叠标题固定为“仓库维护者备注”。
- 本阶段不修改代码、不关闭 Issue、不创建分支或 PR。

## 变更

- `.agents/skills/issue-handler/SKILL.md` 保持接近正常对话的开放式调查方式，只保留安全边界、报告拉取和输出形式服从调用方等基础规则。
- skill 的 `agents/openai.yaml` 不声明 GitHub MCP 依赖。
- receiver 侧实现属于独立工程，不修改 Jugg 产品代码。

## 两阶段调用

### 第一阶段：调查

- 使用 `codex exec --json` 启动新 session，不传 `--output-schema`。
- prompt 与正常对话保持一致，只提交 Issue 分析请求和 Issue 数据，不告诉 Agent 后续还有结构化阶段，也不添加输出限制。
- 从 `thread.started` 事件保存 session ID。
- receiver 保存第一阶段完整 JSONL，便于与本地有头会话比较工具调用和调查深度。

### 第二阶段：结构化输出

- 使用 `codex exec resume <session-id> --json --output-schema <schema>` 恢复第一阶段 session。
- prompt 只要求把上一轮调查结论转换为符合 Schema 的最终结果。
- receiver 仅消费第二阶段的最终结构化对象并执行写回。

这样实验中的主要变量是 `output schema` 是否在调查开始前进入上下文。如果两阶段模式能够产出与正常对话一致的根因，说明提前要求结构化决策很可能导致 Agent 过早收敛；如果仍然失败，再比较无头运行的 prompt、工具权限、上下文和推理预算。

## 安全与幂等

- GitHub 内容统一视为不可信数据。
- Agent 本地 sandbox 为 read-only。
- receiver 按 Delivery ID 去重，并在评论写入前检查由 receiver 生成的隐藏标记。
- GitHub App 只安装到目标仓库，初始权限为 Contents 读取、Metadata 读取和 Issues 读写。
- App 私钥和短期 token 不进入 Codex 环境或 prompt。

## 验证

- 使用 skill validator 检查目录和 frontmatter。
- 使用 headless Codex 的只读运行验证 skill 可发现。
- 验证第一阶段未传 `--output-schema`，最终消息为完整自然语言调查结论，并成功提取 session ID。
- 验证第二阶段恢复同一 session，传入 `--output-schema` 后只输出一个符合 Schema 的最终对象。
- 对同一 Issue 分别保存第一阶段和第二阶段 JSONL，并与本地有头会话比较根因、工具轨迹和证据引用。
- GitHub App 配置后，用测试 Issue 验证 API 读取、标签、评论与重复投递幂等性。
