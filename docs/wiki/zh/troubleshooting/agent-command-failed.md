---
title: Agent 或命令执行失败
description: 处理 Jugg CLI、MCP、UI 自动化、布局检查、截图和录屏的参数与运行环境错误。
status: active
tags:
  - troubleshooting
  - mcp
  - cli
  - ui-tools
---

# Agent 或命令执行失败

CLI、MCP 和 UI 自动化通常会返回可以直接修正的参数或环境错误。先按错误文案调整输入；设备、App 或工程未准备好时，先恢复运行环境再重试。

## Q：MCP 提示缺少 `projectDir` 或项目未初始化怎么办？

1. 传入当前打开工程的绝对路径作为 `projectDir`。
2. 确认该目录就是已经由 Jugg 初始化的工程根目录。
3. Android Studio 刚打开或刚完成 Sync 时，等待 Jugg 初始化完成再调用。

不要传父目录、模块目录或另一个 worktree 的路径。

## Q：工具名不存在、参数未知或类型错误怎么办？

1. 调用 `tools/list` 获取当前版本实际注册的工具和 schema。
2. 使用 schema 中的工具名和字段名。
3. 删除未声明的参数。
4. 按 schema 修正必填字段和参数类型。

不要从旧文档猜测参数，工具 schema 是当前行为的直接依据。

## Q：查询编译状态时找不到 jobId 怎么办？

1. 保存编译触发工具返回的 `jobId`。
2. 查询状态时使用同一个 `projectDir` 和 `jobId`。
3. `waitTimeoutMs` 使用工具 schema 允许的范围。
4. job 已经过期或 Android Studio 重启后，重新触发一次编译任务。

## Q：CLI 提示缺少 Java 或 Android SDK 环境怎么办？

为当前终端设置有效的 `JAVA_HOME` 和 `ANDROID_HOME`，并确认对应目录存在。修改环境变量后重新打开终端，再运行 Jugg CLI。

Gradle base 构建失败时，先直接执行相同 Gradle 命令，修复工程或环境错误。

## Q：incremental CLI 拒绝 `changedFiles` 怎么办？

1. 先完成 base 构建，生成可用的 Jugg 基线。
2. `changedFiles` 只传当前源码工程内真实存在的可编译文件。
3. build 文件变化不要走 incremental，改用 Gradle base 构建。
4. 确认 `outputApkDir` 存在并可写。
5. 自定义编译器文件必须存在且可以读取。

## Q：UI 工具提示设备或 App 没准备好怎么办？

1. 连接并解锁设备。
2. 启动目标 App，并保持在前台。
3. App 已崩溃或 ANR 时，先修复运行问题或重启 App。
4. 确认当前 Run Configuration 能解析目标 package name。

## Q：找不到 UI 元素或匹配到多个元素怎么办？

- 找不到元素：先减少 selector 条件，使用更宽的 `text`、`resourceId` 或 `contentDesc` 定位。
- 匹配多个元素：增加 selector 条件，直到目标唯一。
- `swipe`：使用坐标或屏幕百分比，不使用元素模式。
- App 页面刚切换：等待页面稳定后重新获取布局，再执行操作。

## Q：`layout-verify` 参数错误怎么办？

1. `checks` 必须是非空列表。
2. 每个 check 都需要 `type`。
3. property 检查需要 `property`。
4. relation 检查需要可解析的 `target2`。
5. 使用已有 dump 文件时，确认 `dumpFile` 路径存在。

## Q：截图或录屏失败怎么办？

1. 确认设备在线并可交互。
2. 开始录屏前已有 active session 时，先停止旧 session。
3. 停止录屏时使用开始录屏返回的 `sessionId`。
4. 开始和停止必须使用同一台设备。
5. 拉取文件失败时，保持设备在线后重试一次。

## 相关页面

- [CLI 指南](../guide/cli.md)
- [MCP 指南](../guide/mcp.md)
- [UI 检查指南](../guide/ui-inspection.md)
- [CLI 命令参考](../reference/cli-commands.md)
- [MCP 工具参考](../reference/mcp-tools.md)
