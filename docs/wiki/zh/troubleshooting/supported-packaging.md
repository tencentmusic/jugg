---
title: 支持哪些打包方式
description: 说明 Jugg 支持哪些 debug 打包方式，以及为什么不能用于正式 release 打包。
status: active
tags:
  - troubleshooting
  - faq
  - apk
---

# 支持哪些打包方式

Jugg 缩短的是日常 debug 循环里的完整 Gradle 构建次数。它可以把当前 debug 改动部署到设备，或导出增量 debug APK；不能替代 GitHub Action、CI 正式出包或应用市场上架时的 Gradle 打包。

## 支持 release 打包吗？

不支持用 Jugg 打正式 release 包。上架、渠道包和正式发布必须继续使用 Gradle / Android Studio 的完整打包流程。Jugg 没有为这个场景设计，也不能加快 `assembleRelease`。

| 打包方式 | 是否使用 Jugg | 用户可见结果 |
|---|---|---|
| 日常调试 | 使用 | 已安装 APK 通常不会整包更新；改动通过增量编译和部署生效 |
| 导出增量 debug APK 给测试验证 | 使用 | 在降级确认弹窗点击 `Export incremental APK`，把已编译的增量结果写入 APK 并导出 |
| 日常流水线打包 debug APK | 可以使用 | 通过 `cmd_line` 的两步命令产出 debug 增量 APK；见下方「流水线 debug 增量 APK」 |
| 上架、渠道包或正式 release 包 | 不要使用 | 继续使用 Gradle / Android Studio 完整打包 |

> [!IMPORTANT]
> 正式打包必须走 Gradle 原有流程。Jugg 不能加快 release 包的构建，也不应替换上架用的签名、混淆和完整打包步骤。

实验性的 [Release 编译](../capabilities/compile/release-compile.md) 只用于在已安装的 minify / release 包上继续日常调试，不是用来生成可上架 APK。

## 流水线 debug 增量 APK

CI 场景不走 IDE 的 [导出增量 APK](../guide/export-incremental-apk.md) 按钮，而是通过 `cmd_line` 模块的两步命令产出 debug 增量 APK：

```text
cmd=buildGradleBase
  -> 执行完整 Gradle 构建
  -> 保存 APK、classpath 和 Jugg 基线

cmd=buildIncrementalApk
  -> 基于已保存基线恢复编译上下文
  -> 编译流水线显式传入的 changedFiles
  -> 把增量结果写回 APK 输出目录
```

流水线需要自行提供 `changedFiles` 列表；Jugg 不会自动推断 CI diff。同一份基线目录只能消费一次；需要多组增量结果时，应为每组复制独立基线。

当前还没有独立的 Wiki 使用指南。参数名、校验规则和调用示例见源码目录 [`cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/`](https://github.com/tencentmusic/jugg/tree/main/cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline)。

## 相关页面

- [导出增量 APK](../guide/export-incremental-apk.md)
- [运行 App](../guide/run.md)
- [限制](../reference/limits.md)
- [Release 编译](../capabilities/compile/release-compile.md)
- [Jugg 工作原理](../concepts/how-jugg-works.md)
