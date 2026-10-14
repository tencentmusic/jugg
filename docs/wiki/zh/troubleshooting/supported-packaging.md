---
title: 支持哪些打包方式
description: 深入解析 Jugg 在 Android 多样化打包生态下的支持情况：Debug 增量、混淆 Minify、AabResGuard、Dynamic Feature 与正式 Release 边界。
status: active
tags:
  - troubleshooting
  - faq
  - packaging
  - apk
---

# 支持哪些打包方式

在复杂的 Android 大型工程中，团队通常会使用多种打包模式（如 Debug 快速开发包、测试渠道包、Release 混淆包、多 APK / App Bundle、系统平台签名应用等）。

Jugg 的核心定位是**大幅缩减日常开发与本地调试循环里的完整 Gradle 构建耗时**。它可以将日常微调的代码与资源以秒级速度热更到测试设备，或导出用于离线验证的增量 Debug APK；但**不能也不应替代正式上架、应用市场发布或 CI 正式签名的 Release 打包流程**。

## 1. Android 常见打包生态支持矩阵

下表系统梳理了 Jugg 对 Android 各种常见打包模式与工具链的支持情况与行为表现：

| 打包方式 / 工具链 | Jugg 支持状态 | 运行机制与用户可见结果 | 对应专题指引 |
|---|---|---|---|
| **常规 Debug APK** (`assembleDebug`) | **完全支持**（核心场景） | 建立基线后，代码与资源修改走秒级旁路增量通道，通常 1~3 秒直接生效 | [运行 App](../guide/run.md) |
| **导出增量 APK** (Incremental APK) | **完全支持** | 将已编译的增量 class 与资源增量覆写到 APK 内部，直接导出供测试离线验证 | [导出增量 APK](../guide/export-incremental-apk.md) |
| **Release 混淆包增量** (R8 / ProGuard) | **支持调试**（不可上架） | 支持基于已有 Release 包通过 `_jugg_fix` 机制走增量编译与混淆映射，方便日常排查混淆专有 Bug | [Release 增量编译](../concepts/incremental-compile/release-compile.md) |
| **资源混淆** (AabResGuard / AndResGuard) | **完全支持** | 增量编译时无缝继承并复用原包的资源 ID 与混淆映射字典 | [资源增量编译](../concepts/incremental-compile/resource.md) |
| **动态特性模块** (Dynamic Feature / AAB) | **完全支持** | 支持拆分模块 (Split APKs) 的增量识别与设备端定向部署 | [支持能力总览](../capabilities/index.md) |
| **系统应用 / 平台签名应用** (`priv-app`) | **支持**（需脚本接管） | 支持通过自定义 APK 安装脚本接管 remount/push，通过自定义签名脚本对接平台证书或服务器签名 | [兼容部署与设备支持](../guide/compat-device.md) |
| **CI / CD 流水线产出增量包** | **支持** | 提供命令行模式（`cmd_line` 两步出包），可脱离 IDE 在流水线生成增量测试 APK | 见下方说明 |
| **正式上架 Release 包 / 渠道包** | ❌ **不支持** | 必须使用 Gradle / Android Studio 完整构建与官方签名体系 | - |

## 2. 为什么不能用 Jugg 打正式 Release 上架包？

很多开发者会问：*“既然 Jugg 编译这么快，能不能直接用 Jugg 编译可上架的正式 Release 包？”*

答案是明确的：**不能**。主要原因如下：

1. **构建完整性与死代码消除 (R8/ProGuard Tree Shaking)**：Jugg 增量编译只编译改动的类与受影响范围，而正式 Release 必须由 R8/ProGuard 对全量工程代码做完整的全局拓扑分析、内联优化与未引用代码裁剪。
2. **签名契约与安全合规**：正式应用商店（Google Play、各手机厂商商店）对 APK / AAB 的 V2/V3 签名签名块、对齐（zipalign）和完整性有严格校验，增量修改覆写产物只适用于快速测试，无法替代官方的正式签名流水线。
3. **确定性构建 (Deterministic Build)**：正式发布要求相同代码产出完全一致的字节码哈希，增量累加式编译在多次迭代后不具备发布级确定性。

> [!IMPORTANT]
> 正式打包与渠道分发必须走原生 Gradle `assembleRelease` 或 `bundleRelease`。Jugg 不会修改你的原生打包脚本，切换回原生 App 运行配置即可一键恢复官方完整打包流程。

## 3. CI/CD 流水线：生成增量 Debug APK

在自动化流水线中，如果希望在云端快速产出包含局部改动的增量测试包，可通过 `cmd_line` 独立模块执行两步命令：

```text
步骤 1: cmd=buildGradleBase
  -> 执行完整 Gradle 构建，生成初始底包 APK、依赖 classpath 与 Jugg 基线

步骤 2: cmd=buildIncrementalApk
  -> 基于步骤 1 的基线恢复编译环境
  -> 增量编译流水线显式传入的 changedFiles 文件列表
  -> 将增量 Class 与资源覆写写入目标 APK 输出目录
```

> [!NOTE]
> 流水线需要自行提供 `changedFiles` 改动文件列表。同一份基线目录仅支持消费一次；若需并发产出不同分支的增量包，请为每个任务复制独立的基线目录。

---

## 下一步指引

- 📖 **[导出增量 APK 操作指南](../guide/export-incremental-apk.md)**：如何在 Android Studio 中一键导出测试验证包
- ⚙️ **[Release 混淆增量编译原理](../concepts/incremental-compile/release-compile.md)**：了解如何基于 Release 变体高效定位线上混淆 Bug
- 🛡️ **[Jugg 能力边界与回退机制](../reference/limits.md)**：查看各种场景下的 Gradle 回退规则
