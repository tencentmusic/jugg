# 工程化：项目模型与 Gradle 集成

> 最后核对：2026-06-08
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页说明 Jugg 的项目快照从哪里来、如何跨 IDE / Gradle / include build 合并，以及这些信息如何服务编译、部署、依赖变化和 androidTest。

本页不展开编译阶段实现、部署状态机、androidTest 执行细节；对应见 `02_compile_core.md`、`03_deploy_complete.md`、`06_android_test.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `JuggProjectInfo` / `ModuleInfo` | `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt` | Gradle 模块快照，记录 source/res/manifest/classpath/dependency/applicationId/androidTest 等信息 |
| `ModuleBuildPathInfo` | `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt` | 多 AGP 版本 build output 路径兼容推断 |
| `JuggPathManager` | `main/src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt` | 项目级 Jugg 文件布局：project info、compile context、deploy history、classpath、日志、MCP fetch cache |
| `JuggGlobalPathManager` | `main/src/main/java/com/sickworm/intellij/jugg/project/JuggGlobalPathManager.kt` | 用户级 `~/.jugg` 文件布局：hot update、deploy cache、resource 等 |
| `GradleProjectInfoReaderManager` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderManager.kt` | Gradle init script 入口，读取/保存 project info、include build、dependency diff、androidTest task 注入 |
| `GradleProjectInfoReader` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt` | 通过 Gradle 反射读取 module、variant、source set、classpath、依赖、androidTest synthetic module |
| `ProjectInfoSerializerInGradle` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/ProjectInfoSerializerInGradle.kt` | Gradle 脚本侧 project info JSON 序列化 |
| `JuggProjectInfoMerger` | `main/src/main/java/com/sickworm/intellij/jugg/project/merger/JuggProjectInfoMerger.kt` | 合并 IDE/Gradle/include build/project info，生成编译上下文使用的模块视图 |
| `GradleProjectInfoLocalFetchManager` | `idea/src/main/java/com/sickworm/intellij/jugg/project/dependency/GradleProjectInfoLocalFetchManager.kt` | IDE 侧调度本地 project info 读取和依赖变化检测 |
| `LocalGradleCompileClient` / `RemoteGradleCompileClient` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/` | 本地/远端 Gradle 构建、APK 查找、classpath 拉取与 diff 参数拼装 |

---

## 3. 核心数据模型

### 3.1 路径与持久化

| 文件/目录 | 来源 | 用途 |
|---|---|---|
| `build/jugg/database/project_infos.db/project_infos.json` | IDE 侧 | IDE project info 快照 |
| `build/jugg/database/project_infos.db/gradle_project_infos.json` | Gradle init script | Gradle 反射读取的模块/依赖/variant 快照 |
| `build/jugg/database/project_infos.db/gradle_include_builds.txt` | Gradle init script | include build project info 文件列表 |
| `build/jugg/database/project_infos.db/is_dirty` | project info 管理 | 标记需要更新 project info |
| `build/jugg/classpath/` | Gradle/full build fetch | 本地 classpath、APK、library backup、embedded APK |
| `~/.jugg/library_test_build_records` | androidTest history | 记录 self-targeting library Test APK 构建历史 |

`GradleProjectInfoReaderManager` 优先读取 Gradle property `jugg.projectDir` 作为 IDE project dir；当 Gradle root 与 IDE project root 不一致时，不能直接用 `rootProject.rootDir` 推断 Jugg 文件位置。

### 3.2 `ModuleInfo` 关键字段

| 字段 | 语义 |
|---|---|
| `name` | 标准模块名，Gradle path 会转为点分格式 |
| `moduleRootDir` / `projectRootDir` | 模块根与 IDE 项目根；相对路径用于跨机器/远端同步 |
| `buildVariant` / `buildPathInfo` | 当前变体及 AGP 输出路径推断 |
| `moduleDependencies` / `libraryDependencies` / `runtimeLibraryDependencies` | 编译、运行和模块依赖 |
| `applicationId` / `namespace` | APK 归属、manifest、androidTest target 解析基础 |
| `instrumentationTargetPackage` | 非空表示 synthetic androidTest module |
| `kaptDependencies` / `kspDependencies` / `kotlinPlugins` | 注解处理和 Kotlin 编译输入 |

androidTest synthetic module 命名为 `${module.name}.androidTest`，`buildVariant` 固定为 `debugAndroidTest`，并通过 `instrumentationTargetPackage` 标记为测试模块。IDE project info 创建时会使用 `AsDeployerCompat#getIdeModuleInfo` 暴露的 IDE Android 模型 test package / target package 信息；Chipmunk / Narwhal feature / Otter / Panda 继承链在 library self-targeting 场景下会 fallback 到 `selectedBasicVariant.testApplicationId`、`androidProject.testNamespace` 或 `${androidProject.namespace}.test`。只有 test package 与 target package 都有效时才把 IDE `.androidTest` module 标记为 androidTest module；`uninitialized.application.id` 会视为无效。若已有 Gradle project info，IDE 侧 `.androidTest` synthetic module 还必须出现在 Gradle androidTest module 集合中；若没有 Gradle 快照，则退化为要求 source root 下存在 Java/Kotlin 源码。Gradle merge 时这些 test 字段仍以 Gradle 非空值优先，并会丢弃 IDE-only androidTest module。

---

## 4. 核心调用链路

### 4.1 Gradle project info 读取

```text
IDE / Gradle compile 触发 project info 更新
  -> GradleProjectInfoLocalFetchManager 或 Gradle compile client 拼装 init script 参数
  -> readProjectInfo.gradle.kts
     内嵌 GradleProjectInfoReaderManager / GradleProjectInfoReader 逻辑
  -> GradleProjectInfoReaderManager.readAndSave()
     读取环境、上次 project info、当前 Gradle project info
  -> GradleProjectInfoReader.getProjectInfo()
     遍历 subprojects，读取 Android / Java module、variant、source set、classpath、依赖
  -> 写入 gradle_project_infos.json
     include build 额外写入 gradle_include_builds.txt
```

`main/src/main/java/.../gradle/script` 下的类同时作为生成 init script 的源码模板；排查时要同时关注生产 wrapper 和 `main/src/main/resources/gradle/readProjectInfo.gradle.kts` 中的内嵌结果。

`readProjectInfo.gradle.kts` 在 `gradle.taskGraph.whenReady` 后分流执行：dry-run 仍立即调用 `readAndSave()`，避免没有真实 task execution 时丢失 project info；非 dry-run 会把读取挂到 task graph 最后一个 task 的 `doLast`，让依赖快照尽量在 execution phase 读取，减少 Gradle 9/AGP 高版本的 configuration-time resolve warning。

### 4.2 Sync 后合并为编译上下文

```text
JuggManager.onSyncEvent()
  -> updateProjectInfo()
  -> CompileContextManager.updateCompileContext()
  -> CompileContextManager.doGetAllModulesByModuleManager()
     创建 IDE project info，并基于完整 IDE Android 模型 androidTest artifact 信息标记 IDE androidTest module
  -> JuggProjectInfoMerger
     合并 IDE project info、Gradle project info、include build project info、custom config
  -> reInitOnCompileContextUpdate()
     DeployFileManager / JuggCompiler / FileChangesHandler / GitFileChangesDetector / CustomCompilerManager 重新绑定新上下文
```

项目快照更新不是单纯替换 JSON。它会影响 classpath、module-to-APK 归属、文件变更过滤、自定义编译器、依赖变化确认和部署历史恢复。

### 4.3 androidTest 相关读取

```text
GradleProjectInfoReader.getProjectInfo(includeAndroidTestSourceSet)
  -> 仅当 -Pjugg.buildTarget=ANDROID_TEST 时 includeAndroidTestSourceSet=true
  -> 对 Application / Library / DynamicFeature 尝试读取 androidTest sourceSet
  -> buildAndroidTestModuleInfo()
     有 androidTest source 才生成 synthetic ModuleInfo
  -> localFetch 从 [IDeployHistoryManager.getFullBuildInfo] 取 buildTarget；diff 从当前 compile options 取 buildTarget，并写入 -Pjugg.buildTarget
  -> GradleProjectInfoReaderManager.injectAndroidTestTaskIfNeeded()
     buildTarget=ANDROID_TEST 时把 application androidTest task 和 library test tasks 注入任务依赖
```

Library androidTest 的 `instrumentationTargetPackage` 当前取 synthetic test app id；排查 target package 时优先以实际 Test APK manifest 为准，再回看 synthetic module 构造。

---

## 5. Gradle 编译客户端边界

| 类 | 边界 |
|---|---|
| `LocalGradleCompileClient` | 本机执行 Gradle、收集 app/test APK、读取 library Test APK build history、fetch classpath |
| `RemoteGradleCompileClient` | 远端执行 Gradle、拉回产物/日志/diff，并处理远端项目路径差异 |
| `SshCommand` | 拼装远端 Gradle 参数，包括 `jugg.diffMode`、`jugg.incDeployTimes`、`jugg.libraryTestTasks` |
| `ApkLookupPlanner` / `FindOutputCommand` | 根据 required/optional APK 规则定位输出产物 |
| `GradleDependencyDiffer` | diff mode 下输出依赖变化结果，供 dependency manager 判断是否需要用户确认 |

APK 拉取全部成功后，`LocalGradleCompileClient` / `RemoteGradleCompileClient` 会按本轮找到的 APK 文件清理 `build/jugg/classpath/apk/`，删除不属于本轮拉取结果的旧文件；查找或拉取失败时不清理旧缓存。

---

## 6. 隐形约束

- `ModuleInfo` 新增字段时必须同步 `JuggProjectInfoSerialize`、`JuggProjectInfoMerger`、`ProjectInfoSerializerInGradle`、`CmdLineContextManager`、`LibrariesBackupHelper`；否则 Gradle/IDE/CLI 任一侧会丢字段。
- `ModuleBuildPathInfo` 是 AGP 路径兼容层；不要在编译器里散落硬编码 `build/intermediates/...` 路径。
- `ModuleBuildPathInfo.rFilePath` 只在既有 application R.jar 匹配候选内选择；当 AGP 升级或远端同步导致多个候选 `R.jar` 并存时，按 `lastModifiedTime` 选择最新产物，mtime 相同时保留候选匹配顺序，低版本 library R.jar 仍走独立兼容分支；`BaseCompileContext` 会在多候选时打印 debug 日志。
- `ModuleBuildPathInfo.javaClassPath` 在 `intermediates/javac/<variant>/classes` 与 `compile<Variant>JavaWithJavac/classes` 并存时同样按 `lastModifiedTime` 选择最新目录；`allClassPath` 只挂载解析后的单一 Java 输出目录，避免 AGP 升级后旧目录 shadow 新 class。
- `readProjectInfo.gradle.kts` 读取依赖时使用上次 project info 做 CRC 缓存，但不能只依赖缓存，因为 transitive dependency 信息可能不完整。
- include build 会把各自的 `gradle_project_infos.json` 复制成 `include_build_N_gradle_project_infos.json`，主工程只通过列表文件引用。
- diff mode 只输出依赖差异并清理临时 project info；非 diff mode 才写正式 `gradle_project_infos.json`。
- androidTest task 注入发生在 Gradle task graph finalization 之前；如果请求任务名和真实 task path 对不上，注入会静默打印 “no requested task found” 并跳过。

---

## 7. 排查入口

| 现象 | 优先入口 |
|---|---|
| Gradle root 与 IDE project root 不一致 | `GradleProjectInfoReaderManager` 的 `jugg.projectDir` 处理 |
| 模块 source/res/manifest 路径异常 | `GradleProjectInfoReader.getModuleInfo()` 与 `ModuleBuildPathInfo` |
| AGP 升级后找不到 R.jar / manifest / data binding 输出 | `ModuleBuildPathInfo` 对应属性 |
| project info JSON 缺字段 | `ModuleInfo` 字段同步清单、`ProjectInfoSerializerInGradle`、`JuggProjectInfoMerger` |
| include build 模块缺失 | `gradle_include_builds.txt` 与 `JuggProjectInfoMerger` |
| 找不到 APK 输出 | `LocalGradleCompileClient`、`ApkLookupPlanner`、`FindOutputCommand` |
| 依赖变化未感知 | `GradleDependencyDiffer`、`DependencyChangeManagerByGradle` / `DependencyChangeManagerBySync` |
| library androidTest target package 异常 | 实际 Test APK manifest、`buildAndroidTestModuleInfo()`、`LibraryTestApkBuildHistory` |

---

## 8. 关联文档

- 编译核心：`02_compile_core.md`
- androidTest：`06_android_test.md`
- IDE 编排：`04_engineering_ide.md`
- 兼容层：`04_engineering_compat.md`
- 运行时排查：`09_plugin_runtime_debug.md`
