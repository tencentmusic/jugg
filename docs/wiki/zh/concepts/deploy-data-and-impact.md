---
title: 部署数据与影响分析
description: 说明 Jugg 如何根据新旧 class 结构和 APK 解析数据库判断热重载、热修复和扩散编译。
status: active
tags:
  - concept
  - deploy
  - impact
---

# 部署数据与影响分析

Jugg 编译出增量产物后，会先把它们整理成 `JuggDeployData`。这一步决定两件事：哪些产物下发给设备，哪些源码还要继续编译。

`JuggDeployData` 是 staging 数据，不是部署历史。它交给后续部署链路使用；只有整轮部署成功后，`DeployFileManager.commit()` 才会把结果写回历史。

## 部署数据解决什么问题

部署数据把编译产物分到几组：

| 字段 | 来源 | 含义 |
|---|---|
| `newClasses` | 旧数据库里不存在的新 class | 新增 class。 |
| `hotReloadModifiedClasses` | 新旧结构对比后可 hot reload 的 class | 交给 Apply Changes / JVMTI 路径。 |
| `hotFixModifiedClasses` | 多 dex、library dex 或结构变化 class | 需要 hot fix 或重启生效。 |
| `effectedClassNodes` | method/field/subclass/generic/minify/inline 分析结果 | 需要继续重编译或做字节码补偿。 |
| `overlays` | res / assets 编译产物 | 下发到 overlay。 |
| `updateApkFiles` | Manifest、`resources.arsc`、native lib | 写回 APK 并重新安装。 |
| `constRefEffectedSourcePaths` | 常量引用分析结果 | 单独加入下一轮源码编译。 |

`JuggDeployData.deployType` 由这些字段反推。`isInstall` 走 install；`isCompatDeploy` 走兼容热修；`hotFixModifiedClasses` 或 `isPushOverlayOnly` 非空会让本轮需要重启 App；剩余增量数据走 hot reload。

## 影响分析

只部署被修改 class 会漏掉调用方。删除方法时，被修改类本身可以编译通过，旧调用方还留在 APK 或已部署历史里，运行时会继续调用旧签名。

Jugg 的普通影响分析来自 APK 解析数据库和增量部署数据库：

```text
changed dex
  -> ClassNodeComparator 比较新旧 class
  -> DeployDataDatabase 查询 method_refs / field_refs / subclass_refs
  -> 生成 EffectedClassNode(SOURCE)
  -> CompileEffectAnalyzer 找回源码文件
```

会触发源码重编译的信号包括：

- 方法删除、签名变化、`private` 与非 `private` 切换，或其他有效 access flag 变化。
- 字段删除。
- 抽象父类或接口新增 abstract 方法。
- 类级 generic signature 变化。

虚方法传播会查子类。static 方法只查直接引用，不启动子类遍历。

## release/minify 影响

release/minify 场景会附加两类结果：

| 类型 | 来源 | 下游 |
|---|---|---|
| `MINIFY_MEMBER_REMOVED` | 增量 dex 引用了 APK 中被 minify 移除的类或成员 | `DexMinifyCompiler` 补偿。 |
| `INLINE_IMPL_CHANGE` | mapping 里记录了被改方法曾经 inline 到调用方 | `DexMinifyCompiler` 补偿。 |

merge 时，如果同一个 class 已经是 `SOURCE`，保留 `SOURCE`。源码重编译优先级高于 inline/minify 补偿。

## 与资源和 APK 更新的关系

资源和 assets 进入 `overlays`。首次 overlay 部署时，`DeployDataGenerator` 会通过 `addFullRes()` 补齐资源，避免设备端 overlay 缺文件。

Manifest 变化进入 `updateApkFiles`。如果更新列表里有 `AndroidManifest.xml`，Jugg 会把同轮的 `resources.arsc` 一起加入。native lib 也进入 `updateApkFiles`。

`updateApkFiles` 非空时，后续部署链路会先写回 APK、重签名，再恢复部署状态并安装更新后的 APK。

## const-ref 单独处理

常量引用结果不放进 `effectedClassNodes`。`DeployDataGenerator` 调用 `ConstRefEffectProvider` 后，把结果写入 `constRefEffectedSourcePaths`。`CompileEffectAnalyzer` 最后把这部分源码和普通 `EffectedClassNode(SOURCE)` 合并。

## 相关页面

- [编译调度流程](./compile-pipeline.md)
- [部署策略](./deploy-strategy.md)
- [增量编译](./incremental-compile/)
- [重编译 / 扩散编译](./incremental-compile/recompile-propagation.md)
- [常量引用分析](./incremental-compile/const-ref.md)
