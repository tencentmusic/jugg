---
title: Android Studio 版本兼容
description: 解释 Jugg 在工程同步、设备选择、部署和调试阶段依赖哪些 Android Studio API，以及如何隔离版本差异并限制兼容兜底范围。
status: active
tags:
  - concept
  - compatibility
---

# Android Studio 版本兼容

Jugg 会复用 Android Studio 的工程模型、Run Configuration、设备选择、安装、Apply Changes 和 Java debugger attach 能力。这些能力来自 Android Studio 内部 API，不是面向第三方插件的稳定公开接口；升级 IDE 后，相关类型和调用方式可能变化。Jugg 将这些差异限制在版本适配边界内，避免工程信息读取、编译和部署主流程随每个 Android Studio 版本一起变化。

## Android Studio 升级为什么可能让插件提前失败

Android Studio 升级可能迁移部署运行时的包名，更换安装器或调试入口，也可能直接移除旧类型。插件如果在主流程中直接引用这些类型，JVM 会在加载相关类时解析引用，而不是等到用户真正执行部署后再判断功能是否可用。

```text
Android Studio 移除或迁移内部类型
  -> 插件加载仍引用旧类型的类
  -> JVM 无法完成类型或方法解析
  -> 项目初始化阶段出现 NoClassDefFoundError 或 NoSuchMethodError
  -> Run 尚未开始，相关服务已经无法启动
```

因此，版本兼容首先要解决的不是部署失败后的重试，而是防止某个版本专用类型在不合适的 Android Studio 中被提前加载。

## Jugg 把版本差异限制在调用边界

编译和部署主流程只使用 Jugg 自有的接口与中立数据模型。需要调用 Android Studio 能力时，再由版本适配边界选择具体实现：

```text
Jugg 编译或部署主流程
  -> Jugg 自有接口与数据模型
  -> 当前 Android Studio 对应的版本实现
  -> Android Studio 部署或调试 API
```

Android Studio 的安装会话、overlay 标识和 deployment cache 条目只在具体版本实现内部转换，不会成为主流程的数据契约。这样，新版本改变内部类型时，调整范围可以停留在版本实现内。

版本实现也不会在插件启动时统一解析所有方法签名。只有主流程实际调用某项能力时，才会接触对应的 Android Studio 类型；缺少旧类型只会影响当前调用，并为尝试其他版本实现留下空间。

## 当前依赖的 Android Studio API

不同阶段依赖的 API 及用途如下。表中只列出当前主流程会调用的关键类型，同一能力在不同 Android Studio 版本中可能位于不同包或使用不同方法签名。

| 使用阶段 | 关键 API | 用途 |
|---|---|---|
| 插件初始化 | `ApplicationInfo` | 读取当前 IDE 产品和版本号，选择优先使用的版本实现。 |
| Gradle 同步完成或工程信息刷新 | `GradleAndroidModel`、`ProjectBuildModel`、`GradleBuildModel`、`AndroidFacet`、`ModuleManager` | 读取模块目录、构建变体、SDK、Java/Kotlin 编译选项、Manifest 位置、APK 输出目录和 Android Test 包信息。 |
| 创建或同步 Jugg Run Configuration | `RunManager`、`AndroidRunConfigurationType`、`AndroidRunConfiguration` | 查找现有 Android Run Configuration，并生成对应的 Jugg 编译命令、构建变体和 APK 输出路径。 |
| 初始化 Jugg Run Configuration | `DeployableToDevice.KEY`、`DeviceAndSnapshotComboBoxAction.DEPLOYS_TO_LOCAL_DEVICE` | 告诉不同版本的 Android Studio，该 Run Configuration 可以使用 IDE 的设备选择器。这个标记发生在项目服务完成初始化之前。 |
| Run 前解析设备 | `DeployTargetContext`、`DeployTarget`、`AdbService`、ddmlib `IDevice` | 读取 IDE 当前选中的已启动设备，并通过 ADB 获取已连接设备；Jugg 不会为了读取选择结果主动启动模拟器。 |
| 安装或更新 APK | `ApkParser`、`AdbInstaller`、`ApkInstaller`、`InstallOptions`、`InstallMode`、`DeploymentPlan` | 解析 APK，创建安装会话，并在 Install 或 APK 更新阶段执行完整安装或增量安装。 |
| Apply Changes | `ApplicationDumper`、`DexComparator`、`ClassRedefiner`、`OptimisticApkSwapper`、`OverlayId` | 在增量编译完成后校验设备上的 APK，组织 class 与资源 overlay，并执行 Code Swap 或 Full Swap。 |
| Run 状态检查和恢复 | `DeploymentApplicationService`、ddmlib `Client` 与 `IDevice` | 判断设备授权状态、系统版本和目标进程是否可调试，为部署恢复和 Debug attach 提供状态。 |
| Debug Run 部署成功后 | `AndroidConnectDebugger`、`AndroidJavaDebugger`、ddmlib `Client` | 等待目标进程进入 debugger waiting 状态，再交给 Android Studio 建立 Java Debug 会话。 |

这些依赖发生在不同时间点。工程模型 API 在同步和刷新工程信息时使用；deployer API 只在本次 Run 已经进入安装或 Apply Changes 后使用；debugger API 只在 Debug Run 编译和部署都成功后使用。某一类 API 的版本差异不会触发其他阶段提前加载它的全部实现。

## 版本选择是有限的 Best-effort

Jugg 会优先选择与当前 Android Studio 精确匹配的实现。没有精确匹配时：

- 当前 IDE 高于已知最高版本，优先尝试最高版本实现。
- 当前 IDE 低于已知最低版本，退到最低版本实现。
- 调用遇到类、方法或字段缺失等 API 形态错误时，再尝试其他已知版本实现。

这种选择方式用于缩小版本差异造成的影响，不代表未验证版本一定完整兼容。当前支持的 Android Studio 版本及验证范围以[兼容性参考](../reference/compatibility.md)为准。

兜底只处理 Android Studio API 的链接或形态差异。安装失败、设备离线、部署产物无效等业务错误会直接返回原有流程，由部署恢复或 Gradle 回退处理；切换版本实现无法改变这些失败条件，也不应掩盖真实原因。

## 能力边界

本机制只处理 Android Studio 内部部署和调试 API 的版本差异：

- Android Studio 版本是否经过验证，以兼容性参考中的版本表为准。
- 设备系统限制、JVMTI 不可用或厂商系统行为由[兼容部署](./compat-deploy.md)处理。
- Gradle、AGP 和 Kotlin 插件版本是否受支持，属于构建工具兼容范围。
- Java debugger attach 是否可用还取决于当前 Android Studio 版本，具体操作和失败入口参见 [Debug](../guide/debug.md)。

## 相关页面

- [兼容性参考](../reference/compatibility.md)
- [Apply Changes 中的 class 与 overlay](./apply-changes.md)
- [兼容部署](./compat-deploy.md)
- [Debug](../guide/debug.md)
