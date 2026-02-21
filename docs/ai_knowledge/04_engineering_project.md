# Jugg 项目管理与 Gradle 集成

---

## 一、项目管理概览

### 1.1 核心架构

Jugg 使用独立的 `JuggProjectInfo` 数据结构来抽象和存储项目信息，通过 Gradle 脚本或 IDE 接口获取项目配置，实现了与构建工具的解耦。

```
Project (IDE/Gradle)
    ↓
GradleProjectInfoReader / IdeModuleInfoReader
    ↓
JuggProjectInfo (项目全量信息)
    ├─ ModuleInfo (模块信息)
    ├─ LibraryDependency (库依赖)
    └─ ModuleDependency (模块依赖)
    ↓
CompileContext (编译上下文)
```

### 1.2 核心组件列表

| 组件 | 职责 |
|------|------|
| **JuggProjectInfo** | 核心数据结构，存储项目模块、依赖、路径等信息 |
| **ModuleInfo** | 单个模块的详细配置，包括源码路径、编译选项、依赖等 |
| **GradleProjectInfoReader** | 通过执行 Gradle 脚本读取项目信息 |
| **LocalGradleCompileClient** | 本地 Gradle 编译客户端，负责执行 Gradle 任务 |
| **CmdExecutor** | 命令行执行器，封装了进程调用和日志输出 |

---

## 二、JuggProjectInfo - 项目数据模型

### 2.1 JuggProjectInfo

**定义位置**: `project/data/JuggProjectInfo.kt`

包含项目中所有模块的信息：
```kotlin
data class JuggProjectInfo(
    val modules: Map<String, ModuleInfo>,
)
```

### 2.2 ModuleInfo

**定义位置**: `project/data/JuggProjectInfo.kt`

`ModuleInfo` 是最核心的数据类，包含了模块的所有构建相关信息。关键字段如下：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | 模块唯一名称 (e.g. `:app`, `:lib:core`) |
| `moduleType` | Type | 模块类型 (Application, Library, DynamicFeature...) |
| `sourceDirs` | List<File> | 源码目录 (Java/Kotlin) |
| `resourceDirs` | List<File> | 资源目录 |
| `manifestFile` | File? | AndroidManifest.xml 路径 |
| `buildVariant` | String | 构建变体 (e.g. `debug`) |
| `moduleDependencies` | List<ModuleDependency> | 模块依赖列表 |
| `libraryDependencies` | List<LibraryDependency> | 库依赖列表 (Jar/Aar) |
| `kaptDependencies` | List<LibraryDependency> | KAPT 依赖 (注解处理器) |
| `isUseViewBinding` | Boolean? | 是否启用 ViewBinding |
| `isUseDataBinding` | Boolean? | 是否启用 DataBinding |

### 2.3 ModuleBuildPathInfo

**定义位置**: `project/data/JuggProjectInfo.kt`

描述模块的构建输出路径，通过这些路径，Jugg 可以找到中间产物（如 R.jar, class 文件等）。

| 路径属性 | 说明 | 适配版本 |
|----------|------|---------|
| `javaClassPath` | Java 编译输出目录 | AGP 3.x - 8.x |
| `kotlinClassPath` | Kotlin 编译输出目录 | 默认 `tmp/kotlin-classes/$variant` |
| `rFilePath` | R.jar 路径 | 自动适配不同 AGP 版本的路径变化 |
| `mergedManifest` | 合并后的 Manifest 路径 | AGP 3.x - 8.x |

**亮点**: `rFilePath` 和 `mergedManifest` 使用了多路径探测策略，以适配不同版本的 Android Gradle Plugin (AGP)。例如 R.jar 可能位于 `compile_and_runtime_not_namespaced_r_class_jar` 或 `processDebugResources` 目录下。

---

## 三、GradleProjectInfoReader - Gradle 信息读取

### 3.1 工作原理

**定义位置**: `gradle/script/GradleProjectInfoReader.kt`

Jugg 不直接解析 `build.gradle` 文件，而是通过执行一个初始化脚本（`init.gradle`），利用 Gradle API 在配置阶段（Configuration Phase）读取项目信息。

1. **注入逻辑**: 通过 `GradleProjectInfoReaderManager` 注入读取逻辑。
2. **反射读取**: 使用 `Reflector` 反射读取 AGP 的内部属性（因为 AGP API 经常变化且部分 API 为 internal）。
3. **依赖解析**: 解析 `Configuration` 获取所有依赖（Module 和 Library）。
4. **输出 JSON**: 将读取到的 `JuggProjectInfo` 序列化为 JSON 文件供 IDE 插件读取。

### 3.2 关键实现

**模块类型识别**:
```kotlin
val moduleType = when {
    project.plugins.hasPlugin("com.android.application") -> ModuleInfo.Type.Application
    project.plugins.hasPlugin("com.android.library") -> ModuleInfo.Type.Library
    // ...
}
```

**依赖解析 (`getDependenciesByConfig`)**:
- 自动处理 Configuration 名称（如 `debugCompileClasspath` vs `compileClasspath`）。
- 支持解析 `ExternalModuleDependency` (Maven 依赖) 和 `ProjectDependency` (模块依赖)。
- 能够区分 Jar 包和 AAR 包。

**AGP 属性反射**:
```kotlin
// 读取 android 扩展配置
val androidExt = Reflector(project.extensions.getByName("android"))
val compileSdkVersion = androidExt["compileSdkVersion"]?.valueString
val defaultConfig = androidExt["defaultConfig"]
val minSdkVersion = defaultConfig["minSdkVersion"]["apiLevel"]?.valueString
```

### 3.3 依赖缓存

为了提高读取速度，`GradleProjectInfoReader` 会利用上一次的读取结果 (`lastProjectInfo`) 作为缓存，特别是对于文件校验和（CRC32）计算。

---

## 四、Gradle 编译客户端 (Gradle Compile Client)

### 4.1 LocalGradleCompileClient

**定义位置**: `gradle/compile/LocalGradleCompileClient.kt`

负责在本地执行 Gradle 命令，用于 Full Compile 或依赖更新检查。

**核心功能**:
1. **compileAndFetchResult**: 执行编译命令（如 `./gradlew assembleDebug`），并查找输出的 APK 文件。
2. **findApk**: 智能查找生成的 APK。
   - 支持通过名称查找。
   - 自动适配不同 Gradle 版本的输出目录（`build/outputs/apk` vs `intermediates/apk`）。
   - 自动选择架构（优先 `arm64-v8a`，其次 `universal`）。
3. **fetchLibraryChanges**: 执行 Gradle 任务来对比依赖变更（用于库热修）。

### 4.2 命令执行 (CmdExecutor)

封装了底层的 Process 调用，支持：
- 环境变量注入（JAVA_HOME, ANDROID_HOME）。
- 实时日志输出（通过 `TerminalOutputListener`）。
- 进程取消。

**环境变量构建**:
```kotlin
fun buildCompileEnv(project: Project, logger: Logger): List<String> {
    // 自动获取项目配置的 JDK 路径和 Android SDK 路径
    // 过滤掉系统原有的 JAVA_HOME/ANDROID_HOME，确保使用项目配置的环境
}
```

---

## 五、设计亮点与兼容性

1. **解耦设计**: 项目结构信息 (`JuggProjectInfo`) 与获取方式解耦。既可以通过 Gradle 脚本获取，也可以通过 IDE 模型获取（`IdeModuleInfo`），为未来扩展提供了灵活性。
2. **广泛的 AGP 兼容**:
   - `ModuleBuildPathInfo` 中对各类中间产物路径（R.jar, Manifest, Class）进行了多版本适配。
   - `GradleProjectInfoReader` 使用反射读取 AGP 属性，避免了对特定 AGP 版本的二进制依赖。
3. **精准的依赖解析**: 能够深入解析 Gradle 的依赖树，区分 AAR 中的 Jar 和本地 Jar，为增量编译提供了准确的 classpath。
4. **虚拟模块**: `ModuleInfo.virtualModule` 的设计简化了空对象处理。

---

**下一步**: 
目前已完成项目管理模块的核心分析。接下来将进入 **阶段 5: IDE 插件层分析**，探索 Jugg 如何与 IntelliJ/Android Studio 界面和逻辑进行集成。
