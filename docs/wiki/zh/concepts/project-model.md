---
title: 工程上下文获取
description: 说明 Jugg 如何从 IDE、Gradle 和 include build 获取工程上下文，并合并成增量编译使用的项目快照。
status: active
tags:
  - concept
  - project
  - context
---

# 工程上下文获取

Jugg 的增量编译不能只看文件后缀。一个源码文件要用哪个 classpath、写到哪个输出目录、部署到哪个 APK，都来自工程上下文。

Jugg 会同时读取 IDE 侧和 Gradle 侧的信息，再合并成一份 `JuggProjectInfo`。这份快照被编译、部署、依赖变化检测和 Android Test 复用。

## 上下文来源

| 来源 | 写入位置 | 主要内容 |
|---|---|---|
| IDE project info | `build/jugg/database/project_infos.db/project_infos.json` | 模块、source/res/assets/manifest 路径、IDE Android 模型、当前 variant。 |
| Gradle project info | `build/jugg/database/project_infos.db/gradle_project_infos.json` | Gradle 反射读取的依赖、classpath、variant、Kotlin/Java 参数、AndroidTest source set。 |
| include build | `build/jugg/database/project_infos.db/gradle_include_builds.txt` | include build 的 project info 文件列表。 |
| classpath 目录 | `build/jugg/classpath/` | 本地 classpath、APK、library backup、embedded APK。 |

Gradle 读取由 `readProjectInfo.gradle.kts` 执行。脚本里的逻辑来自 `GradleProjectInfoReaderManager` 和 `GradleProjectInfoReader`。这些类同时存在于源码和生成后的 init script 中，排查时不能只看一边。

## Gradle 根目录和 IDE 工程根目录

`GradleProjectInfoReaderManager` 创建 `JuggPathManager` 时优先使用 Gradle property `jugg.projectDir`。如果这个参数存在，就用它作为 IDE project dir；否则才用 `rootProject.rootDir`。

这个分支影响所有 `build/jugg` 文件位置。Gradle root 与 IDE project root 不一致时，直接用 `rootProject.rootDir` 会把 project info 写到错误目录。

## 合并规则

`JuggProjectInfoMerger` 以 IDE project info 为基础，再合并 Gradle project info 和 include build project info。

```text
IDE sync 或 Gradle fetch 完成
  -> 读取 IDE project info
  -> 读取 Gradle project info 和 include build project info
  -> 对齐 Gradle module name 与 IDE module name
  -> 合并 source/res/assets、classpath、依赖、variant、AndroidTest 字段
  -> 输出 merged JuggProjectInfo
```

合并时有几条硬规则：

- IDE project info 为空时，不生成 merged info。
- 关闭 Gradle project info 读取时，直接使用 IDE project info。
- Gradle project info 为空时，退回 IDE project info。
- IDE project info 比 Gradle project info 更新时，`isNeedUpdateLibraryDependency=false`。
- Gradle-only / IDE-only module 是否保留，由 `ModulePathMergePolicy` 和当前 `BuildTarget` 判断。

模块名也会被修正。Gradle 侧有时读到和 IDE 不一致的 module name，`ModulePathMergePolicy` 会把 Gradle module name 对齐到 IDE module name，并同步更新 module dependency 里的名字。

## `ModuleInfo` 消费字段

合并后的 `ModuleInfo` 会进入编译上下文。下面这些字段会直接影响增量行为：

| 字段 | 下游用途 |
|---|---|
| `sourceDirs` / `resourceDirs` / `assetsDirs` | 文件变化过滤和编译分组。 |
| `manifestFile` | Manifest 增量编译和 update APK。 |
| `buildVariant` / `buildPathInfo` | class、R.jar、DataBinding、Manifest 等中间产物路径。 |
| `moduleDependencies` / `libraryDependencies` | Java/Kotlin/D8 classpath。 |
| `runtimeLibraryDependencies` | 运行时依赖和部署相关判断。 |
| `applicationId` / `namespace` | APK 归属、Manifest、Android Test target 解析。 |
| `kaptDependencies` / `kspDependencies` / `kotlinPlugins` | 注解处理和 Kotlin 编译插件参数。 |
| `instrumentationTargetPackage` | 非空时表示 synthetic androidTest module。 |

`ModuleBuildPathInfo` 是 AGP 输出路径的兼容层。编译器不直接散落硬编码的 `build/intermediates/...` 路径，而是通过它读取当前 variant 下的 class、R、Manifest、DataBinding 等目录。

## Android Test 上下文

Android Test 需要额外的 synthetic module。Gradle project info 读取时，只有 `-Pjugg.buildTarget=ANDROID_TEST` 才会包含 androidTest source set。

```text
buildTarget=ANDROID_TEST
  -> GradleProjectInfoReader 读取 androidTest sourceSet
  -> buildAndroidTestModuleInfo()
  -> 生成 ${module.name}.androidTest
  -> instrumentationTargetPackage 标记为测试模块
```

IDE 侧也会根据 Android 模型创建 `.androidTest` module。合并时，Gradle 非空字段优先；已有 Gradle 快照时，IDE-only androidTest module 必须出现在 Gradle androidTest module 集合中，否则会被丢弃。

## 上下文更新后的重绑

工程上下文更新后，不只是替换 JSON。`CompileContextManager.updateCompileContext()` 之后，Jugg 会重新绑定一批运行对象：

```text
merged JuggProjectInfo
  -> CompileContextManager.updateCompileContext()
  -> reInitOnCompileContextUpdate()
  -> DeployFileManager / JuggCompiler / FileChangesHandler / GitFileChangesDetector / CustomCompilerManager 重新绑定上下文
```

这一步会影响 classpath、module-to-APK 归属、文件变化过滤、自定义编译器和部署历史恢复。

## 相关页面

- [编译调度流程](./compile-pipeline.md)
- [增量编译](./incremental-compile/)
- [Android Test 流程](./android-test-flow.md)
- [回退与限制](./fallback-and-limits.md)
