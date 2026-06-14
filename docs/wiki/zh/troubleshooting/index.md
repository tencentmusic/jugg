---
title: 问题排查
description: 从用户可见现象出发，定位 Jugg 编译、部署、运行时、日志、Debug、Android Test、MCP/CLI 和 UI 工具问题。
status: active
tags:
  - troubleshooting
---

# 问题排查

遇到 Jugg 问题时，先保留现场，再按现象进入对应页面。不要一开始就删除整个 `build/`，否则会丢失日志、部署历史和增量状态。

## 快速入口

| 现象 | 优先阅读 |
|---|---|
| 增量编译失败、走 Gradle、提示 `No file changes` | [编译问题排查](./compile.md) |
| 安装失败、部署失败、设备状态恢复失败 | [部署问题排查](./deploy.md) |
| 编译成功但代码不生效、部署后 crash | [运行时问题排查](./runtime.md) |
| 不知道看哪个日志、需要提交问题现场 | [日志](./logs.md) |
| Android Test 无法解析测试 APK、测试运行失败 | [Android Test 问题排查](./android-test.md) |
| Jugg Debug attach 失败、旧版 Android Studio 不支持 | [Debug 问题排查](./debug.md) |
| MCP 工具、CLI 参数或命令失败 | [MCP 与 CLI 问题排查](./mcp-cli.md) |
| `tap`、`view-inspect`、`layout-verify`、截图等 UI 工具失败 | [UI 工具问题排查](./ui-tools.md) |
| 编译或部署耗时变长 | [性能问题排查](./performance.md) |

## 先保留哪些信息

优先保存项目下的 Jugg 日志目录：

```text
build/jugg/log/
```

如果问题和增量部署状态有关，也保存：

```text
build/jugg/database/
```

提交问题时，尽量说明：

- 本轮是增量编译、Gradle 编译、Debug、Android Test 还是 MCP/CLI 触发。
- 本轮修改了哪些文件类型，例如代码、资源、Manifest、build 文件、依赖库。
- 设备型号、Android 版本、是否多设备。
- 用户可见的完整错误文案。

## 常用处理按钮

Jugg 工具栏里的几个操作适合在排查时使用：

| 操作 | 适用场景 |
|---|---|
| 重启 App | 修改启动逻辑、`object` 初始化逻辑、static/companion/Kotlin 顶层声明后，部署成功但需要重新执行初始化 |
| 直接降级 | 明确希望本轮用 Gradle 完成编译和安装 |
| Clean And Reinstall | 希望清理数据并重装 APK，同时恢复 Jugg 增量部署记录 |
| 导出增量 APK | 需要把当前设备上的增量改动打入 APK 并交付给其他人 |
| 取消降级 | 误触发 Gradle 回退时停止本轮降级编译，下次运行仍优先尝试增量 |
| 兼容模式 | Apply Changes 兼容性异常、资源部署后 native crash、资源/类加载 hook 与 Apply Changes 冲突时使用 |

> [!WARNING]
> 如果直接在手机系统设置里“清除数据”，设备上的增量部署记录也会丢失，App 会回到 base APK 的代码状态。需要恢复时，优先使用 Jugg 的 `Clean And Reinstall`。

## 相关页面

- [运行 App](../guide/run.md)
- [部署结果说明](../guide/deploy.md)
- [限制](../reference/limits.md)
