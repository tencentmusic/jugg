# GitHub Issue 读取脚本方案

## 目标

用户提供 GitHub Issue URL 时，通过本地只读 Python 脚本获取 Issue 基本信息、正文、评论和 Jugg Report ID，不接入 MCP。

## 变更范围

- 新增 `tools/fetch_github_issue.py`：解析 Issue URL，调用 GitHub REST API，支持 Markdown 和 JSON 输出；Token 仅从 `GITHUB_TOKEN` 环境变量读取。
- 新增 `tools/test_fetch_github_issue.py`：覆盖 URL 解析、Issue/PR 区分、Report ID 提取和输出结构。
- 更新 `tools/README.md`：补充命令行用法、Token 配置和安全说明。
- 更新 `AGENTS.md`：约定检测到 GitHub Issue URL 时优先调用脚本，并在脚本失败时保留浏览器回退路径。
- 更新 `.agents/skills/issue-handler/SKILL.md`：允许 Issue Handler 通过仓库脚本读取 Issue，同时保持禁止直接使用 MCP、CLI 或 API 的边界。

## 取舍

- 使用 Python 标准库，不增加第三方依赖。
- 公开 Issue 不强制要求 Token；私有仓库和 GitHub API 限流场景通过 `GITHUB_TOKEN` 提供认证。
- 未认证 API 返回 403/429 限流时，回退到公开 Issue 页面中的结构化数据。
- 脚本只读取 Issue，不执行评论、标签、状态或代码修改。
- 评论默认读取全部分页内容，避免只获取 Issue 正文导致上下文缺失。

## 验证

- 先运行定向单元测试。
- 再使用 Issue #37 做真实 API 读取验证，检查标题、正文、评论和 `c82381c3` Report ID。
- 使用 Python 兼容性检查确认脚本保持 Python 3.7+ 语法。

## 排除项

- 不接入 MCP。
- 不在仓库保存 GitHub Token。
- 不自动下载 Jugg Report；本次只识别 Report ID，后续仍由 Issue Handler 决定是否拉取诊断材料。
