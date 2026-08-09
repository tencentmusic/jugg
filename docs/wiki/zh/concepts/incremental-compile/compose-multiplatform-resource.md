---
title: Compose Multiplatform 资源
description: 解释 Compose Multiplatform 资源的 accessor 生成、增量部署、运行时路径和重启边界。
status: active
tags:
  - concept
  - compile
  - compose
  - resource
---

# Compose Multiplatform 资源

Compose Multiplatform 资源同时包含运行时文件和编译期类型安全 accessor。一次资源变更要真正生效，既要更新应用读取到的资源，也要让 Kotlin 源码能够引用最新生成的声明。

## 资源文件和 accessor 必须一起更新

只复制资源文件不足以支持新增资源：源码中引用的 accessor 可能尚不存在。只生成 accessor 也不够：运行时仍可能读取旧资源或找不到新增文件。

Jugg 因此把 Compose Multiplatform 资源视为一条独立编译链路：

```text
资源新增或修改
  → 生成类型安全 accessor
  → 编译生成的 Kotlin 源码
  → 准备并部署发生变化的运行时资源
```

这条链路不经过 Android `aapt2`，也不会把无法识别的 Compose 资源静默当作 Android `res/` 处理。

## 复用 Gradle 元数据和项目官方 generator

资源目录、生成任务、包名和 generator API 会随 Compose 插件版本及项目配置变化。Jugg 从 Gradle 同步得到对应任务元数据，并调用项目当前 Compose 插件提供的资源生成能力。

这样可以保持 accessor 结构、资源命名和运行时路径与项目完整 Gradle 编译一致。若当前任务形态或 generator API 无法识别，Jugg 会明确失败，提示使用 Gradle 编译，而不是生成一个可能不兼容的替代结果。

## 生成 accessor 需要完整资源上下文

资源 accessor 的内容不仅由本轮修改的文件决定。新增同名 qualifier、修改默认值或改变资源集合，都可能影响最终生成代码。

因此，生成阶段会读取 Gradle 模型中所有已知的 Compose 资源目录，保证 accessor 看到完整资源集合；部署阶段仍只处理本轮新增或修改的资源，避免把整套资源重复推送到设备。

## 现代和 legacy 资源使用不同的运行时路径

不同 Compose 插件版本生成的资源访问方式不同：

- 现代资源链路按 assets 资源路径组织运行时文件；
- legacy 资源链路保留其 APK 根目录 classpath 资源路径。

Jugg 按 Gradle 任务元数据选择对应方式，不把两类路径混用。现代链路支持 `string`、`string-array`、`plurals`、`drawable` 和 `font` 等 accessor；legacy 链路支持范围较小。`files/` 资源可以部署，但不生成类型安全 accessor。

## 资源更新后为什么需要重启 App

Compose 资源读取和缓存可能在进程内长期存在。仅替换设备上的文件，当前进程不一定会重新加载资源。只要本轮产生了有效的 Compose Multiplatform 资源部署，Jugg 就会重启 App，让运行时重新建立资源状态。

## IDE accessor 同步是独立的辅助结果

Jugg 会 Best-effort 地把生成的 accessor 同步给 IDE，便于代码浏览、补全和跳转。该步骤不参与设备上的编译结果判定：即使 IDE 同步失败，已经成功生成、编译并部署的结果仍然有效。

如果运行结果正确，但编辑器暂时无法识别新 accessor，可以重新同步 Gradle 或执行完整 Gradle 编译刷新 IDE 模型。

## 使用边界

- 新增或修改受支持的资源可以走 Jugg 增量链路；删除资源需要 Gradle 重新计算 accessor 和资源集合。
- 自定义资源目录必须已经被 Compose Gradle 任务识别，并出现在同步后的项目模型中。
- 升级 Compose 插件、调整资源任务或切换 generator API 后，先执行 Gradle 同步和完整 Gradle 编译。
- generator 不兼容、资源任务无法识别或 accessor 编译失败时，保留真实错误并回退到 Gradle 编译。

## 相关页面

- [KMP 与 Compose Multiplatform](/zh/capabilities/compile/kmp-compose-multiplatform)
- [资源增量编译](/zh/concepts/incremental-compile/resource)
- [源码增量编译](/zh/concepts/incremental-compile/source)
- [工程信息刷新与恢复](/zh/concepts/project-info-refresh)
- [Restart](/zh/capabilities/deploy/restart)
