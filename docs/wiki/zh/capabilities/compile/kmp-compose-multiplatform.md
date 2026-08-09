---
title: KMP 与 Compose Multiplatform
description: 说明 Jugg 对 KMP expect/actual 源码和 Compose Multiplatform 资源的增量支持范围与回退边界。
status: active
tags:
  - capability
  - compile
  - kmp
  - compose
---

# KMP 与 Compose Multiplatform

Jugg 支持 Android 目标中的 KMP `expect` / `actual` 源码增量编译，也支持新增或修改 Compose Multiplatform 资源。两类能力都依赖 Gradle 同步提供的项目模型：Jugg 复用已有编译关系和资源生成配置，不根据目录名称猜测项目结构。

普通 Android 模块中的 Compose UI 源码属于 [Kotlin Compose](/zh/capabilities/compile/kotlin-compose)，不需要启用 KMP 或 Compose Multiplatform 资源能力。

## 支持范围

### KMP 源码

| 场景 | 支持情况 | 说明 |
| --- | --- | --- |
| 修改 common 与 Android source set 中的 `expect` / `actual` 源码 | 支持 | 按 Gradle 提供的 Android 目标编译模型，将必要的公共源码与平台源码放入同一轮编译 |
| 使用 `sharedMain` 等中间 source set | 支持 | 需要 Gradle 模型暴露对应 fragment 及其依赖关系 |
| Kotlin 1.9 与 K2 项目 | 支持 | 按项目实际编译模型处理缓存和 fragment 差异 |
| 互补源码信息缺失或关系不明确 | Best-effort | 保留当前已确认的源码输入，不根据文件名或目录名补猜 `expect` / `actual` 关系 |
| 普通 Android 模块中恰好存在 `commonMain` 目录 | 不自动按 KMP 处理 | 是否属于 KMP 由 Gradle 编译模型决定 |
| 删除 KMP 源文件 | 需要 Gradle | 增量链路不处理删除后的完整输出清理 |

### Compose Multiplatform 资源

| 场景 | 支持情况 | 说明 |
| --- | --- | --- |
| 新增或修改 `string`、`drawable`、`font` 资源 | 支持 | 生成并编译类型安全 accessor，同时准备运行时资源 |
| 新增或修改 `string-array`、`plurals` | 现代资源链路支持 | 旧版资源链路不支持这两类 accessor |
| 新增或修改 `files/` 资源 | 支持部署 | 不生成类型安全 accessor |
| 使用自定义 Compose 资源目录 | 支持 | 目录必须出现在 Gradle 同步得到的资源任务元数据中 |
| 将生成的 accessor 同步到 IDE | Best-effort | 同步失败只影响 IDE 浏览和索引，不会把已完成的编译结果判定为失败 |
| 删除 Compose Multiplatform 资源 | 需要 Gradle | 删除可能改变 accessor 集合和资源清单，需要完整任务重新计算 |
| 当前 Compose 插件任务或 generator API 无法识别 | 明确失败 | 不会静默改按 Android `res/` 资源处理 |

## 触发与结果

KMP 源码变更沿用源码编译链路：

```text
识别 Android 目标的 Kotlin 编译模型
  → 补齐本轮必需的公共、平台和中间 source set 源码
  → 编译 Kotlin 输出
  → 转换并部署增量 DEX
```

Compose Multiplatform 资源由独立资源链路处理：

```text
读取 Gradle 资源任务元数据
  → 使用项目 Compose 插件提供的 generator 生成 accessor
  → 编译 accessor，并准备发生变化的运行时资源
  → 部署资源；存在有效资源变更时重启 App
```

Compose Multiplatform 资源不经过 Android `aapt2`。Jugg 会为 accessor 生成读取完整的已知资源目录，但部署范围仍限制在本轮新增或修改的资源。

## 使用边界

- 新增或调整 source set、Android target、Compose 插件版本、资源目录或 Kotlin 编译参数后，先执行 Gradle 同步和至少一次完整 Gradle 编译。
- KMP 互补关系缺失时，Jugg 只使用能够从当前模型确认的输入；若仍出现 `expect` / `actual` 或符号解析错误，使用 Gradle 编译刷新基线。
- Compose Multiplatform accessor 的 IDE 同步属于辅助结果。编译和部署成功但编辑器暂时无法跳转时，可重新同步 Gradle，不必把它视为本轮部署失败。
- 删除 KMP 源码或 Compose Multiplatform 资源时，直接使用 Gradle 编译，避免残留输出或 accessor。

## 相关页面

- [KMP 源码增量编译](/zh/concepts/incremental-compile/kmp-source)
- [Compose Multiplatform 资源](/zh/concepts/incremental-compile/compose-multiplatform-resource)
- [源码增量编译](/zh/concepts/incremental-compile/source)
- [工程信息刷新与恢复](/zh/concepts/project-info-refresh)
- [Gradle 编译回退](/zh/capabilities/compile/gradle-fallback)
