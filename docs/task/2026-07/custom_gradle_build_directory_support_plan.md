# 自定义 Gradle buildDirectory 适配方案

## 1. 背景

用户工程可能统一修改模块构建目录，例如：

```groovy
project.buildDir = "${rootProject.projectDir}/build/" + project.name
```

此时 `app` 模块的 APK 位于：

```text
<project>/build/app/outputs/apk/testGoogle/debug/*.apk
```

当前 Jugg 自动生成的 Run Configuration 固定使用：

```text
app/build/outputs/apk/testGoogle/debug/*.apk
```

手动修改 APK 路径只能绕过 APK 查找失败。`ModuleBuildPathInfo.buildDir` 同样固定为
`moduleRootDir/build`，后续 Java/Kotlin classpath、R.jar、Manifest、DataBinding、mapping、远端产物同步仍会使用错误路径。

## 2. 数据能否读取

### 2.1 Gradle 侧

可以读取，并应作为最终权威数据源。

在 Gradle project 完成配置后读取：

```kotlin
project.layout.buildDirectory.get().asFile
```

该值能够反映 `project.buildDir = ...` 和 `layout.buildDirectory.set(...)`。不新增对
`project.buildDir` 的读取，避免使用 Gradle 9 已废弃 API；当前生成脚本已经使用过
`layout.buildDirectory`，可沿用现有 Gradle 兼容方式。

读取时机必须保持在 project 完成配置之后。不要在脚本解析前或 IDE 打开工程时自行解析
`build.gradle`，因为 build directory 可能来自 `allprojects`、约定插件、变量或外部脚本。

### 2.2 IDE 侧

Android 模块可以读取。当前支持的 Android Studio 模型均暴露：

```kotlin
gradleAndroidModel.androidProject.buildFolder
```

本地依赖索引确认 Chipmunk、Giraffe、Hedgehog、Iguana、Meerkat、Narwhal、Panda、Otter、Quail
兼容包均存在 `buildFolder: File`。

IDE 值依赖最近一次成功 Gradle Sync，可能为空或过期，因此只用于：

- Sync 后立即生成或刷新 Jugg Run Configuration。
- Gradle project info 尚未拉取时构造 IDE module 快照。

最终增量编译上下文仍以 Gradle project info 中的 build directory 为准。

非 Android 模块不依赖 Android Studio Model，统一使用 Gradle project info。

## 3. 目标与边界

### 3.1 目标

- 支持模块默认 `module/build` 和自定义 build directory。
- 自动生成正确的 APK glob。
- 完整构建后，增量编译使用真实 classpath、R.jar、Manifest、DataBinding 和 mapping 路径。
- 本地编译、远端编译、classpath backup 和 androidTest 使用同一份路径信息。
- 新建 Jugg Run Configuration 时生成正确路径，不修改已有配置。

### 3.2 支持边界

- 本地模式支持 project root 内及其公共同步根目录内的 build directory。
- 远端模式要求 build directory 位于配置的远端同步根目录内。
- build directory 超出同步根目录时不静默回退到 `module/build`，应打印用户可见提示，要求调整同步根目录或构建目录。
- 本方案不处理用户通过 AGP Artifact API 将 APK 单独发布到任意目录的定制逻辑；Run Configuration 仍允许手工填写 APK 路径。

## 4. 核心设计

### 4.1 `ModuleBuildPathInfo` 保存真实路径关系

不要只增加一个运行时临时字段。build directory 是所有 Gradle 产物路径的根，应进入
`ModuleBuildPathInfo` 并参与序列化。

建议保存相对 project root 的路径，而不是跨机器持久化绝对路径：

```kotlin
data class ModuleBuildPathInfo(
    val projectRootDir: File,
    val moduleRootDir: File,
    val buildVariant: String,
    val buildDirRelativePath: String = File(moduleRootDir, "build")
        .relativeTo(projectRootDir)
        .path,
    ...
) {
    val buildDir: File
        get() = File(projectRootDir, buildDirRelativePath).normalize()
}
```

示例：

| 场景 | moduleRootDir | buildDirRelativePath | buildDir |
|---|---|---|---|
| 默认 app | `<root>/app` | `app/build` | `<root>/app/build` |
| 用户案例 | `<root>/app` | `build/app` | `<root>/build/app` |
| 根模块 | `<root>` | `build` | `<root>/build` |

保存相对关系后，本地、远端和 `build/jugg/classpath` 镜像只需替换 `projectRootDir`，不需要猜测远端绝对路径。

### 4.2 Gradle project info 写入真实 build directory

`GradleProjectInfoReader` 创建或更新 `ModuleBuildPathInfo` 时传入：

```kotlin
project.layout.buildDirectory.get().asFile
    .relativeTo(ideProjectDir)
    .path
```

涉及位置包括：

- 初始 module info。
- Android variant 更新。
- synthetic androidTest module；其 build directory 继承 owner module。

同步修改：

- `ProjectInfoSerializerInGradle` 的 `ModuleBuildPathInfo` converter。
- `ProjectInfoSerializerInGradle.load()` 的轻量反序列化。
- `JuggProjectInfoSerialize.VERSION`，使旧快照失效并重新拉取，避免 Gson 对新增非空字段产生空值。
- 内嵌的 `readProjectInfo.gradle.kts` 生成产物。

### 4.3 IDE module info 暴露 build directory

在 `IdeModuleInfo` 增加 `buildDir: File?`，各版本 compat 从
`gradleAndroidModel.androidProject.buildFolder` 读取。

`CompileContextManager` 构造 IDE `ModuleBuildPathInfo` 时：

1. 有 `IdeModuleInfo.buildDir`：换算为相对 project root 路径后写入。
2. 无值：兼容回退到 `<module>/build`，并打印 debug 日志。
3. Gradle project info 合并后：Gradle build directory 覆盖 IDE 值，因为 Gradle 是最终真值。

当前 `JuggProjectInfoMerger` 使用 IDE `buildPathInfo` 的逻辑需要调整，不能继续无条件让 IDE 路径覆盖 Gradle 路径。

## 5. Run Configuration 适配

### 5.1 新配置生成

三个 Run Configuration 建议实现不再拼接 `moduleRelativePath + "/build"`，统一使用：

```text
<androidProject.buildFolder relative to IDE project>/outputs/apk/<flavor>/<buildType>/*.apk
```

用户案例生成结果：

```text
build/app/outputs/apk/testGoogle/debug/*.apk
```

路径转换提取为 `SuggestRunConfiguration` 中的共享纯函数，避免 Chipmunk、Narwhal Feature、Quail
三份实现继续复制路径拼接逻辑。

### 5.2 已有配置

`JuggManager.createRunConfigurations()` 发现相同 `compileCommand` 时保持已有配置不变。自定义 build directory 的建议路径只用于新建配置，不根据路径格式推断配置所有权，也不自动迁移旧路径。

## 6. 后续产物与同步链路

### 6.1 构建产物路径

`javaClassPath`、`kotlinClassPath`、`rFilePath`、`mergedManifest`、DataBinding、mapping、usage 等继续基于
`ModuleBuildPathInfo.buildDir` 派生，无需分别增加特殊判断。

### 6.2 classpath backup

当前 `ClasspathBackupHelper` 在复制完成后重新构造 `ModuleBuildPathInfo`，会再次退回 `<module>/build`。
重建时必须保留 `buildDirRelativePath`。

不要继续让同步命令消费“相对 module root”的 `allBuildPathRelative`。建议：

- `ModuleBuildPathInfo` 暴露绝对的 `allBuildPaths`。
- `SyncLocalClasspathCommand`、`FetchClasspathCommand` 根据本次真实 `sourcePath` / 远端同步根目录计算相对路径。
- 计算失败或路径越出同步根目录时明确失败，不生成带错误 `../` 语义的 rsync include pattern。

这样默认多模块和集中式 `<root>/build/<module>` 都使用同一套计算方式。

### 6.3 远端编译

- APK 查找命令本身支持任意相对 glob，无需增加第二套查找逻辑。
- project info 必须来自远端 Gradle 执行结果，不能把本机绝对 build directory 直接发送到远端。
- remote-to-local classpath fetch 使用远端 project info 中的相对路径。
- 若 build directory 位于远端同步根目录之外，提前返回用户可见错误。

### 6.4 androidTest

`AndroidTestCommandDeriver` 当前识别 `/build/outputs/apk/`，改为识别更稳定的 `/outputs/apk/`：

```text
build/app/outputs/apk/testGoogle/debug/*.apk
-> build/app/outputs/apk/androidTest/testGoogle/debug/*.apk
```

`LibraryTestApkBackfillPlanner` 不再拼接 `<module>/build`，改为使用目标 module 的
`ModuleBuildPathInfo.buildDir` 生成 project-root-relative pattern。

## 7. TDD 执行清单

实现前先补以下失败用例：

| 层级 | 测试文件 | 覆盖内容 |
|---|---|---|
| L1 | `main/src/test/.../project/data/ModuleBuildPathInfoTest.kt` | 默认与集中式 build directory 的所有派生路径 |
| L1 | `main/src/test/.../gradle/compile/AndroidTestCommandDeriverTest.kt` | 自定义 build directory 的 test APK 派生 |
| L2 | `main/src/test/.../gradle/script/ReadProjectInfoGradle5CompatTest.kt` | Gradle 5 自定义 build directory 读取 |
| L2 | `main/src/test/.../gradle/script/ReadProjectInfoGradle7CompatTest.kt` | Gradle 7 自定义 build directory 读取 |
| L2 | `main/src/test/.../gradle/script/ReadProjectInfoGradle9CompatTest.kt` | Gradle 9 `layout.buildDirectory` 读取且无废弃 API |
| L2 | `idea/src/test/.../gradle/script/ProjectInfoSerializerInGradleTest.kt` | build directory 序列化往返 |
| L2 | `idea/src/test/.../manager/JuggManagerRunConfigurationSyncTest.kt` | 新建配置使用建议路径、已有配置保持不变 |
| L2 | `main/src/test/.../gradle/compile/FetchClasspathCommandTest.kt` | 默认/集中式路径的同步 include 规则 |
| L3 | `idea/src/test/.../manager/TopLevelFlowTest` | 自定义 build directory 下完整构建后再增量编译部署 |

禁止运行无 `--tests` 过滤的全量 `:main:test` 或 `:idea:test`。

## 8. 实施顺序

1. 增加失败测试并确认当前固定 `module/build` 行为失败。
2. 修改 `ModuleBuildPathInfo` 和 project info 序列化，打通 Gradle 权威数据。
3. 修改 IDE `IdeModuleInfo` 和 project info merge。
4. 修改 classpath backup、远端同步相对路径计算。
5. 修改 Run Configuration 新建路径，并保持已有配置不变。
6. 修改 androidTest 和 library Test APK 路径派生。
7. 执行定向 L1/L2 测试和至少一条 L3 Flow。
8. 同步 `docs/ai_knowledge/04_engineering_project.md`、`04_engineering_ide.md`、`06_android_test.md`。

## 9. 验收标准

使用以下工程配置：

```groovy
allprojects {
    layout.buildDirectory.set(
        rootProject.layout.projectDirectory.dir("build/${project.name}")
    )
}
```

满足：

- Sync 后自动生成 `build/app/outputs/apk/<variant>/*.apk`。
- 已有配置在 Sync 后保持不变。
- 首次完整 Gradle build 能找到并安装 APK。
- 修改 Java/Kotlin/资源后能够执行增量编译和部署。
- R.jar、Manifest、DataBinding、mapping 路径均来自 `<root>/build/<module>`。
- androidTest 能找到 app APK 与 test APK。
- 本地 classpath backup 和远端 classpath fetch 路径正确。
- 默认 `<module>/build` 工程行为无回归。

## 10. 结论

IDE 和 Gradle 都可以读取真实 build directory，但职责应分开：

- IDE `androidProject.buildFolder`：用于 Sync 后即时 UI 和 Run Configuration。
- Gradle `project.layout.buildDirectory.get().asFile`：作为 project info 与增量编译的最终真值。

只修改自动 APK 路径不足以解决问题，必须让 `ModuleBuildPathInfo`、序列化、classpath backup、远端同步和
androidTest 共用同一份真实 build directory。
