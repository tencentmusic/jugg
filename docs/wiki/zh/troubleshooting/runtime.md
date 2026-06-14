---
title: 运行时问题排查
description: 排查增量部署成功后代码不生效、需要主动重启、资源 native crash、DataBinding/ViewBinding crash 等问题。
status: active
tags:
  - troubleshooting
  - runtime
---

# 运行时问题排查

运行时问题通常表现为“编译和部署都成功，但 App 行为不符合预期”。先区分是不需要重启的热重载、需要重启的初始化逻辑，还是 Apply Changes / 资源 / 注解相关限制。

## 代码没有生效

参考文档中明确提到的场景：

| 场景 | 原因 | 建议处理 |
|---|---|---|
| 增加或修改注解后不生效 | 当前增量链路不重新生成注解代码 | 再次运行并降级为 Gradle 编译 |
| 修改 static / companion / Kotlin 顶层声明后不生效 | HOT_RELOAD 不会重写已经初始化过的 static 值 | 手动重启 App，或点击 Jugg 的重启按钮 |
| 修改 App 启动逻辑或 `object` 初始化逻辑后不生效 | 已经执行过的一次性初始化逻辑不会因为 Activity recreate 再执行 | 手动重启 App |
| 修改 style 后偶现不生效 | 参考文档记录为偶现场景 | 再次运行并降级为 Gradle 编译 |

如果不属于这些场景，并且 Gradle 编译后能生效，请保留 `compile_latest.log` 和复现步骤。

## 什么时候需要重启 App

正常情况下，Jugg 热重载部署会触发 Activity recreate，能覆盖大多数 UI 和普通代码改动。

需要主动重启的典型场景：

- 修改 App 启动流程。
- 修改只在进程启动时执行一次的初始化逻辑。
- 修改 static / companion object / Kotlin 顶层声明，并且本轮部署结果是 HOT_RELOAD。

Jugg 的重启按钮可以直接重启 App，适合这种“部署成功但需要重新执行初始化”的场景。

## 部署后运行时 crash

参考文档中明确提到的 crash 场景：

| 现象 | 第一判断 | 建议处理 |
|---|---|---|
| Oppo / 一加 / 红米等 Android 11 设备，部署资源后概率出现 `AssetManager` native crash | Apply Changes 兼容性问题 | 开启兼容模式，使用热修复部署 |
| 修改 DataBinding/ViewBinding XML 后，编译成功但运行时 crash | 当前增量链路不会重新生成注解代码 | 主动降级 Gradle 编译 |

如果错误包含：

```text
Detect JVMTI compat issue, need to fallback to compat deploy.
```

Jugg 已经检测到 JVMTI 兼容性问题，并会尝试兼容部署。

## 兼容模式

兼容模式不再使用 Apply Changes 热重载部署，而是使用经典热修复部署。

参考文档中明确写到的适用场景：

- Android 8 / 9 / 10 不支持增量部署时，Jugg 会自动打开，无需手动设置。
- 小米 HyperOS 特定包偶发无法使用 Apply Changes 时，Jugg 可自动处理。
- 部分 Android 11 设备部署资源后出现 `AssetManager` native crash 时，可手动打开。
- 工程自身实现了资源或类加载 hook，并与 Apply Changes 冲突导致找不到资源或卡顿时，可手动打开。

手动开启兼容模式需要在连接设备的情况下对该设备设置；设置会持久化，并且跨工程有效。

## 相关页面

- [Hot Reload](../capabilities/deploy/hot-reload.md)
- [Restart](../capabilities/deploy/restart.md)
- [JVMTI Runtime](../capabilities/deploy/jvmti-runtime.md)
- [DataBinding/ViewBinding](../capabilities/compile/databinding-viewbinding.md)
