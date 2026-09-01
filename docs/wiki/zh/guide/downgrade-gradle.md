---
title: 降级 Gradle 编译
description: 说明无文件变化运行、主动点击降级按钮、依赖变化弹窗和源码过多确认框中，什么时候让 Jugg 改走 Gradle 编译。
status: active
tags:
  - guide
  - gradle
  - fallback
---

# 降级 Gradle 编译

Jugg 默认先尝试增量。当当前修改需要完整 Gradle 链路确认，或者你想刷新一次完整构建基线时，可以让本轮改走 Gradle 编译。它会更慢，但结果更接近原生 Android Studio Run。

## 无文件变化时运行

没有保存的新改动时再次点击 Jugg Run，Jugg 可能弹出确认框，询问是否继续用 Gradle 编译。

| 选择 | 结果 |
|---|---|
| Yes | 本轮改走 Gradle 编译，完成后安装并启动 App |
| No | 取消本轮运行 |
| Clean And Reinstall | 清理 App 数据并重装 APK |
| Export incremental APK | 导出当前已经编译过的增量 APK |

这个场景常见于两种情况：你确实想刷新基线，或者只是误点了一次 Run。误点时选 No 即可。

## 主动点击降级按钮

如果你明确要完整 Gradle 构建，可以点击 IDE 里的 `(Jugg) Fallback to Gradle Compile`。终端或 Agent 场景可以执行：

```bash
jugg gradle-build
```

适合主动降级的情况：

- 刚切分支或拉取了大量代码。
- 改了 Gradle 插件、依赖、source set、Manifest placeholder。
- 怀疑本轮增量结果不对，需要用 Gradle 对照。
- 删除了类、资源或 Manifest 节点，要确认旧内容已经消失。

Gradle 成功后，Jugg 会重新读取 APK、classpath、mapping 和资源基线。后续小改动仍然可以继续 Jugg Run。

## build 文件变化弹窗

修改 `build.gradle`、`settings.gradle` 或依赖声明后，Jugg 可能先弹出依赖变化确认框。

| 选择 | 什么时候选 |
|---|---|
| Fallback to Gradle | 不确定依赖变化影响，或改了插件、variant、source set |
| Find out changed Libraries | 只改了依赖版本，并且想让 Jugg 尝试依赖库增量 |
| Ignore build changes | 确认 build 文件变化和当前 APK 无关 |
| 关闭弹窗 | 取消本轮运行 |

不确定时选 Fallback to Gradle。这里多花一点时间，通常比带着不准的基线继续增量更省事。

## 源码变化过多弹窗

一次修改覆盖的源码文件或模块过多，或者这次修改需要继续编译的受影响文件过多时，Jugg 可能弹出确认框。增量编译这些文件通常比完整 Gradle 更慢，所以默认仍走 Gradle。

| 选择 | 结果 |
|---|---|
| Fallback to Gradle | 本轮改走完整构建。按钮立刻可点 |
| Continue Incremental Compile | 仅本轮继续增量。按钮会先倒计时 2 秒 |
| 关闭弹窗 | 取消本轮运行 |

MCP、CLI 和状态查询不会弹出这张确认框，会直接改走 Gradle。

## 相关页面

- [运行 App](./run.md)
- [导出增量 APK](./export-incremental-apk.md)
- [清理数据](./clean-data.md)
- [Gradle 回退与基线重建](../concepts/gradle-fallback-baseline.md)
- [Gradle 回退](../capabilities/compile/gradle-fallback.md)
- [编译失败](../troubleshooting/compile-failed.md)
- [改动没有生效](../troubleshooting/changes-not-applied.md)
