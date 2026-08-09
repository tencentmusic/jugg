# GitHub Issue Handler 自动化方案

## 目标

让 `github-webhook-receiver` 作为 GitHub App 后端，在收到 `sickworm/jugg` 的 `issues.opened` 后调用仓库级 `$issue-handler` 完成分诊和代码定位，再由 receiver 校验并通过 GitHub App API 写入标签和评论。

## 边界

- GitHub App 负责安装范围、Webhook 来源和短期 API 身份。
- receiver 负责签名、仓库白名单、GitHub API 读取、Delivery 去重、任务持久化、Codex 监管、输出校验和写回。
- `$issue-handler` 只读取 receiver 提供的不可信 Issue JSON 与本地项目知识库，输出分类、标签建议和回复草稿。
- 面向报告者的回复跟随 `reply_language`；维护者备注正文固定使用简体中文，receiver 的公开折叠标题固定为“仓库维护者备注”。
- 本阶段不修改代码、不关闭 Issue、不创建分支或 PR。

## 变更

- `.agents/skills/issue-handler/SKILL.md` 定义只读分析与结构化输出，不依赖任何 GitHub 工具。
- skill 的 `agents/openai.yaml` 不声明 GitHub MCP 依赖。
- receiver 侧实现属于独立工程，不修改 Jugg 产品代码。

## 安全与幂等

- GitHub 内容统一视为不可信数据。
- Agent 本地 sandbox 为 read-only。
- receiver 按 Delivery ID 去重，并在评论写入前检查由 receiver 生成的隐藏标记。
- GitHub App 只安装到目标仓库，初始权限为 Contents 读取、Metadata 读取和 Issues 读写。
- App 私钥和短期 token 不进入 Codex 环境或 prompt。

## 验证

- 使用 skill validator 检查目录和 frontmatter。
- 使用 headless Codex 的只读运行验证 skill 可发现和结构化输出。
- GitHub App 配置后，用测试 Issue 验证 API 读取、标签、评论与重复投递幂等性。
