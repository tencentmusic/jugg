# Jugg 项目概述

> 文档版本: 1.0
> 更新时间: 2025-01-20
> 项目版本: 2.6.13

---

## 一、项目简介

**Jugg** 是一个基于 Android Studio / IntelliJ IDEA 的 Android 增量部署插件。它的核心目标是：**跳过 Gradle 构建，以极快的速度将代码和资源更新到正在运行的 App 中**。

### 核心价值

| 传统方式 | Jugg 方式 |
|---------|----------|
| 修改一行代码 → Gradle 构建 30s~5min | 修改一行代码 → Jugg 编译 1~5s |
| 构建时间与工程体量正相关 | 构建时间与工程体量无关 |
| 每次修改都需要重启 App | 大部分情况无需重启 App |

---

## 二、核心特性

### 2.1 极速编译

- **跳过 Gradle**: 直接调用 Java/Kotlin 编译器，绕过 Gradle 构建系统
- **增量编译**: 只编译变更的文件，不重新编译整个项目
- **单文件编译**: 1-5 秒完成单个文件的编译和部署

### 2.2 热重载 (Hot Reload)

- **JVMTI 技术**: 使用 Android Runtime 的 JVMTI (Java Virtual Machine Tool Interface) 接口
- **无需重启**: 代码更新后直接生效，无需重启 App
- **保持状态**: App 的运行状态得以保留

### 2.3 资源增量更新

- **AAPT2 增量链接**: 自研原生库实现资源增量编译
- **Overlay 机制**: 使用 Android 的资源覆盖机制实现资源热更新
- **支持多种资源**: Layout、Drawable、Values、Assets、Native Libraries

### 2.4 零侵入

- **不修改项目代码**: 无需在项目中添加任何依赖或代码
- **配置即用**: 安装插件、配置运行配置后即可使用
- **可随时停用**: 增量部署失败时自动降级到 Gradle 编译

### 2.5 广泛兼容

- **Android 版本**: 支持 Android 8.0 (API 26) ~ Android 15 (API 35)
- **Android Studio 版本**: 支持 Bumblebee ~ Otter (Narwhal Feature Drop)
- **Kotlin 版本**: 支持 Kotlin 1.4 ~ 2.2
- **Gradle 版本**: 支持 Gradle 6.x ~ 8.x

---

## 三、技术栈

### 3.1 开发语言与框架

| 技术 | 版本 | 用途 |
|------|------|------|
| Kotlin | 1.9.23 | 主要开发语言 |
| Java | 11 | 部分模块和兼容代码 |
| IntelliJ Platform SDK | 223.x | IDE 插件开发 |
| Android Gradle Plugin | 7.2.2 | Gradle 集成参考 |

### 3.2 核心依赖

| 依赖 | 用途 |
|------|------|
| kotlin-compiler-embeddable | 独立 Kotlin 编译 |
| kotlinx-metadata-jvm | Kotlin 元数据处理 |
| databinding-compiler | DataBinding 支持 |
| JGit | Git 集成 |
| OkHttp | 网络通信 |
| SQLite | 部署数据持久化 |
| JSch | SSH 远程编译 |

### 3.3 原生组件

| 组件 | 用途 |
|------|------|
| aapt2-inclink | AAPT2 增量资源链接 (C++) |
| jvmti_agent | JVMTI Agent (Java + JNI) |

---

## 四、支持的文件类型

| 文件类型 | 支持程度 | 备注 |
|---------|---------|------|
| Java 源文件 (.java) | ✅ 完全支持 | |
| Kotlin 源文件 (.kt) | ✅ 完全支持 | 包括 Compose |
| 资源文件 (res/) | ✅ 完全支持 | Layout, Drawable, Values 等 |
| Assets 文件 | ✅ 完全支持 | |
| Native 库 (.so) | ✅ 完全支持 | |
| AndroidManifest.xml | ✅ 支持 | 部分修改需要重启 |
| build.gradle | ⚠️ 部分支持 | 依赖变更需要确认 |
| 注解处理 (APT/KAPT) | ❌ 不支持 | 需要降级到 Gradle |
| 字节码插桩 | ❌ 不支持 | 需要降级到 Gradle |

---

## 五、工作模式

### 5.1 增量编译模式 (默认)

```
文件变更 → Jugg 编译 → 推送到设备 → JVMTI 热重载
```

- 速度最快，1-5 秒完成
- 适用于日常开发的大部分场景

### 5.2 兼容部署模式

```
文件变更 → Jugg 编译 → 推送到设备 → App 重启
```

- 适用于 JVMTI 无法热重载的场景
- 如：修改了类的结构（新增字段、修改方法签名）

### 5.3 Gradle 降级模式

```
文件变更 → Gradle 构建 → 安装 APK → App 重启
```

- 自动降级触发条件：
  - 修改了 build.gradle
  - 文件变更过多
  - 编译错误无法恢复
  - 用户手动触发

---

## 六、相关链接

- [演示视频](https://www.bilibili.com/video/BV1W3411C7PU/)
- [更新日志](../../change_log/change_log_cn.html)
- [架构设计](./01_architecture.md)
