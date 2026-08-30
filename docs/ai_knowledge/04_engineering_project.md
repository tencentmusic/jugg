# 工程化：项目模型与 Gradle 集成

> 最后核对：2026-08-18
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页说明 Jugg 的项目快照从哪里来、如何跨 IDE / Gradle / include build 合并，以及这些信息如何服务编译、部署、依赖变化和 androidTest。

本页不展开编译阶段实现、部署状态机、androidTest 执行细节；对应见 `02_compile_core.md`、`03_deploy_complete.md`、`06_android_test.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `JuggProjectInfo` / `ModuleInfo` | `main/src/main/java/com/sickworm/intellij/jugg/project/info/JuggProjectInfo.kt` | Gradle 项目/模块快照，记录 AGP R8 classpath、source/res/manifest/classpath/dependency/applicationId/androidTest 等信息 |
| `ModuleBuildPathInfo` | `main/src/main/java/com/sickworm/intellij/jugg/project/info/JuggProjectInfo.kt` | 多 AGP 版本及自定义 Gradle build directory 的输出路径兼容推断 |
| `JuggPathManager` | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggPathManager.kt` | 项目级 Jugg 文件布局：project info、compile context、deploy history、classpath、日志、MCP fetch cache |
| `CliRunConfiguration` / `CliRunConfigurationStore` | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/CliRunConfiguration.kt` | IDEA/standalone 共享 build profile、Gradle project info 默认推断、独立配置 JSON 与当前指针原子持久化 |
| `JuggGlobalPathManager` | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggGlobalPathManager.kt` | 用户级 `~/.jugg` 文件布局：hot update、history、resource 等 |
| `RuntimeOwnerStore` | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/RuntimeOwnerStore.kt` | 独立于瞬时 lock metadata 持久化上次 IDEA/standalone Runtime owner，并在 Runtime 切换时生成 owner-change event |
| `JuggDaemon` / `StandaloneProjectRegistry` / `StandaloneProjectServices` | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/` | Java 11 standalone 进程、项目 Runtime 注册/MCP 路由、项目锁 owner 接管、历史恢复、Gradle/增量编译、共享部署与 idle 生命周期 |
| `GradleProjectInfoReaderManager` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderManager.kt` | Gradle init script 入口，读取/保存 project info、include build、dependency diff、androidTest task 注入 |
| `GradleScriptWriter` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/GradleScriptWriter.kt` | 把插件内置 `readProjectInfo.gradle.kts` 与 runtime jar 写到稳定目录，供本地、远端和 CLI 通过 `-I` 注入 |
| `GradleProjectInfoReader` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt` | 通过 Gradle 反射读取 module、variant、source set、classpath、依赖、androidTest synthetic module |
| `GradleVariantCollector` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleVariantCollector.kt` | 配置阶段通过 Android Components API 收集 variant 名称，作为 AGP 9 移除 legacy variant API 后的回退数据源 |
| `ProjectInfoSerializerInGradle` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/ProjectInfoSerializerInGradle.kt` | Gradle 脚本侧 project info JSON 序列化 |
| `JuggProjectInfoMerger` | `main/src/main/java/com/sickworm/intellij/jugg/project/info/JuggProjectInfoMerger.kt` | 合并 IDE/Gradle/include build/project info，生成编译上下文使用的模块视图 |
| `IProjectModelSource` / `GradleProjectModelSource` | `main/src/main/java/com/sickworm/intellij/jugg/project/info/ProjectModelSource.kt` | IDEA/Gradle-only project model source 边界；Gradle-only 模式合并 root 与 include-build 快照 |
| `IdeaProjectModelSource` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/context/IdeaProjectModelSource.kt` | 读取 IDEA module/JDK/source roots，并以 IDE model 为 base 合并 Gradle/include build 快照 |
| `ICompileEnvironmentSource` / `IdeaCompileEnvironmentSource` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/context/CompileEnvironmentSource.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/compiler/context/IdeaCompileEnvironmentSource.kt` | 在 Compile Context 创建或 Gradle fetch 执行时读取当前 Android SDK 与 Gradle 环境，standalone 可注入固定环境 |
| `BaseCompileContext` / `CompileContextManager` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/context/` | 共享编译上下文生命周期、full-build path 覆盖与 custom classpath 应用，不依赖 IDEA model API |
| `GradleProjectInfoLocalFetchManager` | `main/src/main/java/com/sickworm/intellij/jugg/project/dependency/GradleProjectInfoLocalFetchManager.kt` | 通过共享 `TaskRunnerManager` 调度本地 Gradle project info 读取和依赖变化通知，保留项目锁与 Host task 语义 |
| `CompileUiHandler` / `JuggCompileUiHandler` | `main/.../compiler/CompileUiHandler.kt`, `idea/.../compiler/JuggCompileUiHandler.kt` | 将依赖变化后的 incremental/rebuild/cancel 决策与 PlatformApi 解耦；IDEA 展示 dialog，standalone handler 接受确定配置，manager 只应用结果 |
| `LocalGradleCompileClient` / `RemoteGradleCompileClient` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/` | 本地/远端 Gradle 构建、APK 查找、classpath 拉取与 diff 参数拼装 |
| `GradleWrapperRepairer` | `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/GradleWrapperRepairer.kt` | 在 `JuggCompilerHelper.gradleCompile()` 真正执行 Gradle 前，针对已有 `gradle-wrapper.properties` 的工程补齐缺失 wrapper 启动文件 |
| `ComposeResourceInfo` / `ComposeResourceDirectory` | `main/src/main/java/com/sickworm/intellij/jugg/project/info/JuggProjectInfo.kt` | 保存 Compose generator classpath/package/public flag、asset 相对路径，以及 source set 到默认/自定义资源目录的对应关系 |
| 插件发行合规门禁 | `idea/build.gradle`、`third_party`、`tools/generate_third_party_compliance.rb` | 将第三方 NOTICE、许可证、公开源码 revision、修改声明、104 行清单和 SPDX SBOM 打入插件；对应源码保留在公开 Git revision，并在 `buildPlugin` 后校验产物完整性、源码 SHA-256 与源码 revision 一致性 |

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
| `build/jugg/config/run_configurations/<id>.json` | IDEA / standalone | 独立 CLI build profile；id 为稳定 UUID，重命名不改变 id |
| `build/jugg/config/current_run_configuration.json` | IDEA / standalone | 当前配置指针，只保存 schemaVersion 与 configId |
| `build/jugg/runtime.lock.owner.json` | 当前持有 Project Runtime lease 的 Runtime | 首个同 Runtime 项目任务取得 lease 时写入，最后一个引用释放后删除 |
| `build/jugg/runtime.owner.json` | `TaskRunnerManager` | 上次取得项目写所有权的 IDEA/standalone Runtime；CI 不写入；使用临时文件与原子替换；内容损坏时按无历史 owner 处理并由当前 Runtime 覆盖 |
| `build/jugg/runtime.launch.lock` | Python CLI | 同项目 standalone 自动拉起的跨进程互斥锁；锁内二次发现 Runtime，避免并发 CLI 重复创建 daemon |

IDEA Jugg Run Configuration 是共享 profile 的同步源。项目启动时会按稳定 UUID 覆盖保存当前 IDEA 配置，并将当前选中的 Jugg Configuration 写入 pointer；因此重命名不生成新 profile，远端开关、认证与 Gradle command 也不会继续沿用上次退出时的旧快照。后续选择和编辑事件继续执行同一 `save + select` 契约。

`GradleProjectInfoReaderManager` 优先读取 Gradle property `jugg.projectDir` 作为 IDE project dir；当 Gradle root 与 IDE project root 不一致时，不能直接用 `rootProject.rootDir` 推断 Jugg 文件位置。

### 3.2 `ModuleInfo` 关键字段

| 字段 | 语义 |
|---|---|
| `name` | 标准模块名，Gradle path 会转为点分格式 |
| `moduleRootDir` / `projectRootDir` | 模块根与 IDE 项目根；相对路径用于跨机器/远端同步 |
| `sourceDirs` | 模块全部有效源码根的扁平集合；KMP common roots 也必须包含在内 |
| `buildVariant` / `buildPathInfo` | 当前变体及 AGP 输出路径推断 |
| `moduleDependencies` / `libraryDependencies` / `runtimeLibraryDependencies` | 编译、运行和模块依赖 |
| `applicationId` / `namespace` | APK 归属、manifest、androidTest target 解析基础 |
| `instrumentationTargetPackage` | 非空表示 synthetic androidTest module |
| `kaptDependencies` / `kspDependencies` / `kotlinPlugins` | 注解处理和 Kotlin 编译输入 |
| `kotlinJvmTarget` / `kotlinFreeCompilerArgs` | Kotlin 编译任务的有效 JVM target 与附加编译参数 |
| `kotlinCommonSourceDirs` | 选中 Android Kotlin compilation 视为 common 的 Kotlin source roots；非 KMP 或读取失败时为空列表 |
| `kotlinFragmentSourceDirs` | 选中 Android Kotlin task 暴露的 fragment 到 source roots 映射；旧快照或不支持时为空 map |
| `kotlinFragmentRefines` | fragment refinement edge，key 为 refining fragment，value 为其直接 refined fragments |
| `kotlinDefaultFragmentName` | 无 source root 精确命中时使用的 task default fragment；旧快照或不支持时为 `null` |
| `composeResourceInfo` | 已检测的 Compose resource task metadata；同时保存 supported/unsupported 状态与原因，由增量链按 task 和 generator API 结构消费，不按 Kotlin/Compose 精确版本过滤 |

`JuggProjectInfo.agpR8Classpath` 是根项目级字段。Gradle init script 从实际 Android plugin classloader 加载 `com.android.tools.r8.D8` 并读取 code source；若该路径属于 Gradle `jars-*` / `transforms-*` instrumentation cache，则从 Android module 或 root project 的 buildscript classpath 恢复同名原始 artifact。原始 artifact 不可用时字段保持 `null`，由 dex 阶段使用 Jugg 内置 R8，避免把依赖 Gradle 私有类的 instrumentation 产物带出 Gradle classloader。Jugg 不复制 R8 分发包；IDE/CLI 只把路径注入内存中的 compile context，不写入 `FullBuildInfo` 或 `compile_context.db`。合并 composite build 快照时优先选择最终 Application module 所属 Gradle 快照的路径，避免 included build 的 AGP R8 覆盖主应用。

`kotlinJvmTarget` 与 `kotlinFreeCompilerArgs` 优先从当前变体 Kotlin 编译任务的 `compilerOptions` 读取，以兼容 Kotlin 2.x typed compiler options；旧版 Kotlin Gradle Plugin 才回退到 task 或 Android extension 的 `kotlinOptions`。Kotlin task 发现不依赖旧 Kotlin Android plugin ID，兼容 AGP 9 Built-in Kotlin，并在传统 variant task 之后尝试 KMP Android task `compileAndroidMain`。不得直接对 Android extension 调用 `getByName("kotlinOptions")`，否则属性不存在时会产生反射异常，并让增量编译错误回退到默认 JVM target 1.8。

`kotlinCommonSourceDirs` 从 `compile<Variant>Kotlin` / `compile<Variant>KotlinAndroid` task 的 `commonSourceSet` 结构读取，保留 direct common root、中间 `sharedMain` root 和 task 配置的 generated common roots。K2 task 另从 `multiplatformStructure` 读取 fragment sources、refines edge 和 default fragment。两者都不依据 source-set 名称或 `src/<name>` 路径猜测。Gradle reader 会把这些 roots 同时加入 `sourceDirs`；merge 出口保证 common roots 是 `sourceDirs` 子集，并完整保留 Gradle authoritative fragment graph，IDE 的扁平 `sourceDirs` 不覆盖这些身份。

`ComposeResourceInfo.resourceDirectories` 不是从固定 `src/<sourceSet>/composeResources` 路径猜测。`GradleProjectInfoReader` 读取 Compose 任务的 `fileSuffix` 与 `originalResourcesDir`，因此默认目录和 Gradle DSL 配置的自定义目录即使尚不存在也会进入 project info。它还保存 support status/reason、generator classpath、package name、accessor visibility 和 packaging/asset 相对路径，序列化后供文件变更识别与增量编译使用。

Compose generated source 路径由 `ModuleBuildPathInfo.composeResourceGeneratedSourcePath` 从 `generatedSourcePath` 派生，不读取或持久化 Gradle task 的 `codeDir`。增量生成完成后，`ComposeResourceCompiler` 直接将 accessor 写回该目录，供 Android Studio 索引新增资源引用。`allBuildPaths` 已包含父目录 `generatedSourcePath`，无需重复加入这个子目录。

`ModuleBuildPathInfo.buildDirRelativePath` 记录模块实际 Gradle build directory 相对 IDE 项目根的路径。Gradle init script 从 `project.layout.buildDirectory` 读取该值；IDE 侧从 Android model 的 build folder 读取，并在 Gradle/IDE project info merge 时以 Gradle 值为准。构造 `ModuleBuildPathInfo` 时必须显式提供该字段；明确传入空字符串表示兼容旧快照并继续使用 `${moduleRootDir}/build`。所有 classpath、manifest、mapping、APK/androidTest 回填与远端同步路径都从该 build directory 派生，不再假设输出位于模块目录下。

project info 的 `sourceDirs` 可以保留 build directory 下的 generated source，因为 Kotlin/KMP 编译上下文可能需要这些 root；不要在 project info merge 阶段删除。远程构建后的 compile context 会把 `buildPathInfo.projectRootDir/moduleRootDir` 映射到本地 classpath 备份目录，因此文件变更入口不能直接把 `buildPathInfo.buildDir` 当作本地输出目录。`FileChangesHandler` 使用 `ModuleInfo.projectRootDir/moduleRootDir + buildDirRelativePath` 还原本地实际 build directory，并同时登记传统 `${moduleRootDir}/build`，对 changed file 与目录递归统一剪枝；集中式 build directory 不要求位于 module root 内。

`compile_context.db/module_builds.json` writer 保持 version 2，reader 接受 version 1 和 2。version 1 缺失 `buildDirRelativePath` 时按空字符串恢复，因此仍保留 `complete_flag` 的旧用户无需重新全量构建；正常 version 2 的默认或自定义路径原样保留，损坏 version 2 缺失该字段时也按传统目录恢复。`complete_flag` 已缺失时不自动修复，仍需一次成功的 Jugg 全量构建重建上下文。

androidTest synthetic module 命名为 `${module.name}.androidTest`，`buildVariant` 固定为 `debugAndroidTest`，并通过 `instrumentationTargetPackage` 标记为测试模块。IDE project info 创建时会使用 `AsDeployerCompat#getIdeModuleInfo` 暴露的 IDE Android 模型 test package / target package 信息；Chipmunk / Narwhal feature / Otter / Panda 继承链在 library self-targeting 场景下会 fallback 到 `selectedBasicVariant.testApplicationId`、`androidProject.testNamespace` 或 `${androidProject.namespace}.test`。只有 test package 与 target package 都有效时才把 IDE `.androidTest` module 标记为 androidTest module；`uninitialized.application.id` 会视为无效。若已有 Gradle project info，IDE 侧 `.androidTest` synthetic module 还必须出现在 Gradle androidTest module 集合中；若没有 Gradle 快照，则退化为要求 source root 下存在 Java/Kotlin 源码。Gradle merge 时这些 test 字段仍以 Gradle 非空值优先，并会丢弃 IDE-only androidTest module。

### 3.3 Effective model

IDEA 使用 `IdeaProjectModelSource`，保持“IDE model + Gradle model”；standalone/无 IDE 场景使用 `GradleProjectModelSource`，直接合并 root 与 include-build Gradle 快照，不创建空壳 IDE project info。`BuildTarget.APP` 会过滤 Gradle-only androidTest module，`BuildTarget.ANDROID_TEST` 才纳入。

`CompileContextManager` 持有 source model，并在内存中应用 module custom classpath 得到 effective model。当前没有依赖跨 Runtime model identity 的生产消费者，因此不持久化额外 fingerprint/generation 状态；IDEA/standalone 在 Runtime owner 切换后恢复 Compile Context 时必须先从 Gradle snapshot 重载 source model，避免继续使用另一 Runtime 全量构建前的进程内模块与依赖数据。

`CliRunConfigurationGenerator` 同样消费 effective Gradle project info：最近成功配置优先，其次选择名为 `app` 的 application module，再按 module path/name 稳定排序；variant 取 `buildVariant`，缺失时使用 `debug`。默认 UUID 由 module path + variant 确定性生成，`Debug` / `Release` flavor variant 的 APK 目录按 `<flavor>/<buildType>` 推断。Gradle 成功后会用实际 compile options 回写当前配置，避免长期依赖默认推断。

---

## 4. 核心调用链路

### 4.1 Gradle project info 读取

```text
IDE / Gradle compile 触发 project info 更新
  -> GradleProjectInfoLocalFetchManager 或 Gradle compile client 拼装 init script 参数
  -> readProjectInfo.gradle.kts
     内嵌 GradleProjectInfoReaderManager / GradleProjectInfoReader 逻辑
  -> GradleProjectInfoReaderManager.readAndSave()
     读取环境、AGP R8 code source、上次 project info、当前 Gradle project info
  -> GradleProjectInfoReader.getProjectInfo()
     遍历 subprojects，读取 Android / Java module、variant、source set、classpath、依赖
     从选中 Android Kotlin task 的 commonSourceSet 读取 Kotlin common roots
     从 K2 multiplatformStructure 读取 fragment roots、refines edge 和 default fragment
     校验 Compose resource 任务并读取 generator/resource directory metadata
  -> 写入 gradle_project_infos.json
     include build 额外写入 gradle_include_builds.txt
```

`main/src/main/java/.../gradle/script` 下的类同时作为生成 init script 的源码模板；排查时要同时关注生产 wrapper 和 `main/src/main/resources/gradle/readProjectInfo.gradle.kts` 中的内嵌结果。

使用 Gradle init script 的核心目的不是替代 IDE model，而是在不修改工程 `build.gradle` 的前提下取得构建侧事实。IDE model 更适合提供 module/source 结构和当前 IDE 选择；真实 task、variant、classpath、依赖、注解处理器参数、Compose 任务元数据和自定义 build directory 则以 Gradle 执行环境更可靠。`JuggProjectInfoMerger` 因此合并两路数据，而不是假设任一路永远完整。`-I readProjectInfo.gradle.kts` 属于一次构建调用的外挂输入，不要求业务工程接入 Jugg Gradle plugin，也不会把读取逻辑写进用户脚本。

`readProjectInfo.gradle.kts` 在 `gradle.taskGraph.whenReady` 后分流执行：dry-run 仍立即调用 `readAndSave()`，避免没有真实 task execution 时丢失 project info；非 dry-run 会把读取挂到 task graph 最后一个 task 的 `doLast`，让依赖快照尽量在 execution phase 读取，减少 Gradle 9/AGP 高版本的 configuration-time resolve warning。

Android variant 读取保留 `applicationVariants`、`libraryVariants` 和 `featureVariants` 作为旧 AGP 的首选入口；仅当 legacy API 未返回 variant 时，才使用配置阶段从 `androidComponents.onVariants` 收集的名称。收集结果按 Gradle project path 存在 root project extra properties 中，不保留 AGP variant 实例；project info 的 `buildVariant` 推导和 AndroidTest assemble task 注入复用同一份回退数据。该注册同时覆盖 application、library 和 dynamic-feature plugin，反射注册失败时保持旧路径继续执行，不中断 Gradle 配置。

Composite build 使用条件分流：普通项目继续只通过现有 `taskGraph.whenReady` 回调读取；只有根构建发现 `gradle.includedBuilds` 非空时，才把各 included build 的轻量 `:juggReadProjectInfo` task 注入当前请求任务依赖。included build 因此会进入自己的 task graph，并把 `gradle_project_infos.json` 写入自身工程目录，随后由根构建复制到主工程数据库目录。`jugg.projectDir` 仍用于统一计算相对路径，不能用于覆盖 included build 的快照输出目录。

如果 included build 的读取或文件生成仍然失败，汇总时不能中断根项目快照写入；对应旧副本存在时继续保留并写入列表，从未成功生成过副本时才跳过。下一次成功读取会覆盖旧副本。

Application runtime 注入在 Android application plugin 加载后立即注册 `androidComponents.onVariants`。支持 `runtimeConfiguration` 的 AGP 会把 `jugg-runtime.jar` 直接加入具体 variant；旧版或反射失败时回退到通用 `runtimeOnly`，附加路径失败不会中断 Gradle 配置。

Compose metadata 读取是严格结构门禁：未应用 `org.jetbrains.compose` 时 `composeResourceInfo=null`；legacy 管线要求单一 `GenerateResClassTask` 及其必要属性，现代管线要求 converter/accessor/collector task 集合及属性彼此一致。一旦检测到插件，即使 task metadata 或 generator API 结构不支持，也会保存 `Unsupported`、用户可见原因和能够读取的 configured roots，资源变更因此不会静默消失。读取只保存任务配置，不执行 Compose task，也不按 Kotlin/Compose 精确版本推断能力。

### 4.2 Sync 后合并为编译上下文

```text
JuggManager.onSyncEvent()
  -> updateProjectInfo()
  -> CompileContextManager.updateCompileContext()
  -> IdeaProjectModelSource
     创建 IDE project info，并基于完整 IDE Android 模型 androidTest artifact 信息标记 IDE androidTest module
  -> JuggProjectInfoMerger
     合并 IDE project info、Gradle project info、include build project info
     同名 Application 路径冲突时，仅在主 Gradle 快照存在真实 R.jar 时采用该 Application
  -> CompileContextManager
     应用 custom classpath，更新 effective model 与 Compile Context
  -> JuggManager.rebindCompileContext()
     DeployFileManager / JuggCompiler / FileChangesHandler / FileChangeManager(GitFileChangesDetector) / CustomCompilerManager 重新绑定新上下文
```

项目快照更新不是单纯替换 JSON。它会影响 classpath、module-to-APK 归属、文件变更过滤、自定义编译器、依赖变化确认和部署历史恢复。

全量构建完成后，如果 IDE 没有可靠返回 Sync Success，Jugg 会补偿读取一次 IDE project info。该分支仅使用 IDE 数据补充 module/source 结构，library dependency 始终以同一次全量构建生成的 Gradle project info 为准，不受 IDE JSON mtime 更新影响；正常 IDE Sync 仍沿用现有的 mtime 新旧判断。

### 4.3 Standalone daemon 项目注册

```text
jugg CLI 发现目标项目没有 Runtime
  -> 启动 jugg-standalone --project-dir <projectDir>
  -> JuggDaemon
     创建 StandaloneProjectRegistry
     为项目调用 StandaloneJuggRuntimeAssembler
  -> StandaloneProjectRuntime
     在项目锁内记录 runtime.owner.json / owner-change event
     创建项目级 McpToolInvoker
  -> McpLocalServer
     version / list-projects 走全局 Runtime 元数据
     init / compile / deploy / gradle-build / status 按 projectDir 路由到项目 Runtime
```

`jugg stop` 不进入上述 MCP 路由。CLI 直接同步调用稳定 standalone launcher 的 `--stop-project <projectDir>` 控制模式；bootstrap 在加载 active Runtime JAR 前，通过 `ProcessHandle` 按同一 Jugg 根目录和 canonical project path 匹配 `StandaloneBootstrap` 进程。平台支持正常终止时先请求正常退出，5 秒后仍存活则强制终止；不支持的平台直接强制终止。该路径可处理端口尚未 ready 的启动卡顿，同时不会停止 IDEA Runtime、其他工程 daemon，也不会删除项目持久化状态。

Step 11 已由 `StandaloneProjectServices` 组装 Gradle-only model、Compile Context、历史恢复、WatchService/Git reconcile、共享 `JuggCompilerHelper` 与 `JuggDeployerHelper`。WatchService 优先使用 JDK `FILE_TREE` 对工程根注册单个原生递归 WatchKey，不支持时回退逐目录注册；两条路径都忽略 `.git`、`.gradle`、`.idea` 和 `build` 子树事件。启动期若因文件句柄等原因失败，会关闭已创建的 watcher、注册 Git listener 并立即执行一次 Git refresh，后续 `status`、`compile`、`deploy` 也会继续通过 Git refresh 恢复变更。进程级 capability 为 `version`、`list-projects`、`init`、`compile`、`deploy`、`gradle-build`、`get-compile-status`、`status`；`gradle-build` 建立 baseline，`deploy` 负责安装或增量部署。当前 profile 为 remote 时，standalone 在 Gradle full build/fallback 中复用 IDEA 的 `RemoteGradleCompileClient`、SSH/iFT 连接、文件同步和产物拉取链路；`rsync` 与 iFT 仍是 standalone 所在主机需要提供的外部工具。增量编译、设备操作与远程构建前的 project info Gradle dry-run 仍在该本机执行。standalone 不提供认证弹窗，必须在配置中预先提供 SSH 凭据或完成 iFT 认证；否则 compile job 返回 failed，清理终端输出与取消监听器，并给出非交互认证提示。Runtime 构造期的 config/history/context 恢复、显式 `init` 和每次完整 compile/deploy 链都持有同一项目写锁；standalone 项目写事务同时计入 daemon activity。空闲 `status` 仅在非阻塞取得项目锁后执行 owner 恢复、Git refresh 和一致性快照；同 Runtime 正在编译或项目锁正由其他写事务持有时，立即返回当前真实只读快照，不刷新或写入文件状态。运行期间检测到 IDEA/standalone owner 变化后，当前 Runtime 会在下一次成功取得项目写锁的业务链或 status snapshot 开始前重新加载 Compile Context、history、APK 与 Git 文件状态，并使 deployment memory cache 失效。

Project Runtime Lock 只表示 IDEA、standalone、CI 等不同 Runtime 对项目的持有权，不负责同一 Runtime 的任务互斥。同一 `TaskRunnerManager` 首次进入项目事务时取得 NIO 文件锁，后续跨线程项目任务共享该 lease 并增加引用计数，最后一个任务结束后才释放。`RuntimeTaskCoordinator` 负责同 Runtime 互斥：独立项目事务使用不同逻辑 owner 并串行；事务内通过 `runTaskSafe`、`runBackgroundSafe` 或 `runAsyncSafe` 提交的子任务自动继承父 owner，跨线程加入同一事务而不等待父任务。另一个 Runtime 始终要等全部本 Runtime lease 引用结束。仅在发生等待时，等待方以 debug 记录 `Runtime lock contention`（等待 Runtime、owner Runtime/PID/command/jobId）及取得后的等待时长；无竞争不打印锁日志。

Global Resource Lock 只保护 `~/.jugg` 下共享资源的最小读改写提交，不再是 TaskRunner 的任务类型。`TaskRunnerManager` 不暴露 `isGlobalWrite` 或任意 global callback；settings、hot update、CLI/skills、runtime resource 和全局 history 等具体资源 owner 在内部取得 `~/.jugg/locks/global.lock`。Global Lock action 必须同步完成且只执行有界的本地资源读改写，不得启动异步任务、回调 IDEA/业务逻辑、申请业务 monitor，或等待网络、外部进程、线程与 Future。锁顺序固定为 Project Runtime Lock → Global Resource Lock；当前线程持有 G 时，阻塞式和 try Project Lock 获取都会立即失败，因此 G 保持锁等待图叶子。Hot update 使用“两阶段准备 + 短提交”：首次短锁快照可复用缓存与 metadata 基线，锁外完成下载和校验，最终短锁复核基线并原子发布；下载期间其他全局资源 owner 可以正常提交，较慢的旧更新不能覆盖已提交的新更新。Windows CLI 安装只在 G 内发布 `~/.jugg/bin` 文件，释放后才以 5 秒硬超时执行 `reg.exe` 更新用户 PATH，子进程不退出不会继续占用 G。

固定顺序是“Runtime logical owner → Project Runtime lease”。协调器按 owner 引用计数，同 owner 重入只增加引用，不产生等待边；父任务等待子任务时，子任务因此不会反向等待父任务。无父 owner 的非阻塞 Host task 保留改造前的并发语义；其他独立项目事务继续串行，deployment cache 等既有调用方无需增加局部锁。`status` 通过协调器和 Project Runtime lease 的非阻塞 `try` 检查空闲状态，失败立即返回只读快照。调用方只声明任务属性，不需要判断自己是顶层任务还是事务内子任务。

Runtime dispose 不强制修改 owner 或 Project Runtime lease 引用计数，所有锁仍由取得它们的任务在 `finally` 中自然释放。持锁任务依赖异步 completion 时，completion owner 必须在 `close()` 中结束等待并返回取消结果；调用方收到取消后立即退出事务。`GradleProjectInfoLocalFetchManager` 因此会在关闭时完成当前 remote-init completion，`JuggManager` 不再继续 classpath 初始化，避免 IDEA 工程关闭后已排队的 Host task 被平台丢弃而让项目 lease 永久保留。

`IExecutionLockManager` 仅声明项目锁与 owner 读取，不提供默认方法实现。锁等待/非阻塞取得、项目状态快照和 unsupported 能力必须由具体 Runtime 或测试实现显式声明，避免新增 Host 静默继承错误的并发或能力语义。

daemon 的 idle deadline 只由外部 MCP 请求刷新；WatchService、后台轮询和 update check 不刷新。达到 4 小时时，若存在 compile/deploy/Gradle job、项目写事务或更新下载，则每 1 分钟复查，条件解除后停止 MCP Server 并 dispose 全部项目 Runtime。用户也可通过项目级 `jugg stop` 提前结束 standalone daemon；支持正常终止的平台会先触发 JVM shutdown hook，超时后再强制终止，Windows 等不支持正常终止的平台直接强制终止。

### 4.4 androidTest 相关读取

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
| `CopyGeneratedSourceHelper` | 远端 Gradle 编译完成后，将 classpath backup 中的 generated/custom sync 产物回写本地模块 build 目录 |
| `SshCommand` | 拼装远端 Gradle 参数，包括 `jugg.diffMode`、`jugg.incDeployTimes`、`jugg.libraryTestTasks` |
| `ApkLookupPlanner` / `FindOutputCommand` | 根据 required/optional APK 规则定位输出产物 |
| `GradleDependencyDiffer` | diff mode 下输出依赖变化结果，供 dependency manager 判断是否需要用户确认 |

`RemoteGradleCompileClient.executeRemoteCommand()` 复用现有 SSH 认证、代理、环境变量、PTY 与取消能力，在 `remoteProjectPath` 下通过独立子 shell 和唯一完成标记执行一条非交互命令。该入口的单次 SSH connect 最长等待 30 秒，不做文件同步、APK/classpath 拉取或 deploy 编排，也不使用 Gradle 命令的 90 秒无输出超时。用户命令和终端输出只进入独立 Run Content；持久日志只记录命令类型、连接与退出结果，禁止记录命令正文。

依赖变化采用显式确认契约。检测到 build file 变化后，Jugg 先展示文件 diff，由用户选择读取依赖变化、忽略本轮 build file 变化、回退 Gradle 或取消；只有用户确认后才把依赖库产物转换为 `ChangedFile` 进入增量编译。原因是 build script 可以改变任意构建行为，仅凭依赖列表无法证明 APK 其他部分没有变化，自动猜测会把无法判定的风险伪装成成功。

Gradle diff 同时保留两个比较基线：`diffResult` 对比上一次构建依赖，用于展示本轮新增、删除和升级；`diffResultWithFull` 对比最近一次完整 Gradle 基线，用于确定真正需要编译、替换或回滚的 library 文件。library dex 可能在 APK 中合并为单个产物，不能只按上一轮增量结果推断旧 jar。用户选择“忽略”只表示接受当前 build file 对开发链路无影响，不代表 Jugg 已验证脚本等价；出现异常时仍应完整 Gradle 刷新基线。

APK 查找规则以 Run Configuration 的 output pattern 为入口；自动生成的 pattern 使用 IDE Android model 暴露的实际 build folder。androidTest pattern 从 `/outputs/apk/` 片段派生，因此同时支持 `app/build/...` 与项目根集中式 `build/app/...`。远端 classpath 同步使用相对项目根的 build output 路径，保证自定义 build directory 能回写到本地相同位置。

远端 classpath 的 rsync 过滤规则会按 build directory 类型生成：使用 `${moduleRoot}/build` 的普通模块按 variant 复用 `build/...` 规则，避免大型多模块工程为每个模块重复展开相同参数；自定义 build directory、`customClasspath` 与 `customSyncFilePath` 继续使用项目根相对的精确路径，保证集中式输出和项目配置不会被通配规则覆盖。

`JuggCompilerHelper.gradleCompile()` 会在进入 Gradle 客户端前调用 `GradleWrapperRepairer`。该逻辑只处理 `compileCommand` 中使用 `gradlew` / `gradlew.bat` 的场景：若对应目录存在 `gradle/wrapper/gradle-wrapper.properties`，则从 Jugg 内置资源补齐缺失的 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`，并为 `gradlew` 设置可执行权限；若 properties 不存在或命令不是 wrapper 入口，则不修改工程。补齐只创建缺失文件，不覆盖已有文件。Windows 本机执行远程编译时，还会在同步前将实际使用的 Unix `gradlew` 中 CRLF 转换为 LF；转换只在内容变化时写回，不处理 `gradlew.bat`、其他脚本或 Gradle 配置文件，纯本地编译与仅拉取远端结果均保持原文件不变。

本地 Gradle 命令的 `JAVA_HOME` 在 IDEA 中优先使用 linked-project 配置的 Gradle JVM，并通过 IDE JDK 解析器转换为实际路径；未配置或解析失败时，依次回退模块 Java SDK 和系统 `JAVA_HOME`。standalone 优先保留 launcher/shell 显式传入的 `JAVA_HOME`，仅缺失时回退 daemon 的 `java.home`，因此 daemon 自身使用 Java 11 不会强制项目 Gradle 也使用 Java 11。远程编译前的本地 project info dry-run 也使用同一套环境，避免 Android 模块 SDK 不是 Java SDK 时丢失 IDE Gradle JDK。

本地 project info 读取属于后台维护任务，其 Gradle stdout/stderr 统一记录为 `debug`，不得打印用户可见的 `warn`；读取结果仍通过同步状态和返回值参与后续上下文更新。

Gradle project info 因序列化版本不兼容或读取失败被删除后，本地重建任务保持非阻塞，避免占用同时覆盖增量与全量 Gradle 编译的全局初始化状态。增量编译可用性由“Gradle project info 可用且缺失快照重建已完成”共同决定；即使 JSON 已由 Gradle 提前写出，也必须等刷新任务完成 compile context 更新后才能恢复增量编译。已明确需要全量 Gradle 编译的路径直接执行；仍准备增量编译时轮询等待重建结束，重建失败再回退全量 Gradle 编译，不得继续仅使用 IDE project info 部署。

远程编译切换 compile command 时，即使本地已有 Gradle project info，也必须使用当前命令启动一次本地 project info dry-run。该刷新与远程构建并行，并通过 `shouldWaitForRemoteInit` 设置远程初始化等待标记；远程 full build 完成后，初始化增量编译先读取并清除该标记，只有标记存在时才等待本地刷新结束，避免 IDE Sync、依赖恢复等普通后台刷新额外阻塞远程链路。等待完成后读取 project info 和拉取 classpath，避免继续按旧 variant 拼装同步路径。如果远程 full build 成功后 classpath 拉取失败，本轮不初始化增量编译，同时保留已有 compile context 与 deploy history。

APK 拉取全部成功后，`LocalGradleCompileClient` / `RemoteGradleCompileClient` 会按本轮找到的 APK 文件清理 `build/jugg/classpath/apk/`，删除不属于本轮拉取结果的旧文件；查找或拉取失败时不清理旧缓存。

---

## 6. 隐形约束

- `ModuleInfo` 新增字段时必须同步 `JuggProjectInfoSerialize`、`JuggProjectInfoMerger`、`ProjectInfoSerializerInGradle`、`CmdLineContextManager`、`LibrariesBackupHelper`；否则 Gradle/IDE/CLI 任一侧会丢字段。
- `JuggProjectInfo.agpR8Classpath` 只保存可脱离 Gradle classloader 使用的直接引用路径，不把 R8 文件复制到 classpath backup，也不进入 `FullBuildInfo` 或 compile context 磁盘格式；Gradle instrumentation code source 找不到原始 buildscript artifact 或旧 project info 缺失该字段时按 `null` 兼容，并由 dex 阶段回退到 Jugg 内置 R8。
- `JuggProjectInfo.agpR8Classpath` 类型允许为 `null`，但构造参数没有默认值；所有构造点必须明确传递现有路径或显式传入 `null`。仅转换 modules 的流程必须使用 `projectInfo.copy(modules = ...)`，禁止重新构造根快照导致项目级字段丢失。
- `composeResourceInfo` 已按上述链路同步并在 merge 时优先保留 Gradle 值；`main/src/main/resources/gradle/readProjectInfo.gradle.kts` 也必须与 `gradle/script` 生成源一致。
- Project info 只记录选中 Android Kotlin task 为本轮增量编译暴露的 fragment graph，不构建项目全部 target 的完整 Kotlin source-set 依赖图，也不记录 deletion 图或 generated source cache。
- `ModuleBuildPathInfo` 是 AGP 路径兼容层；不要在编译器里散落硬编码 `build/intermediates/...` 路径。
- `ModuleBuildPathInfo.buildDirRelativePath` 必须在 Gradle JSON、IDE project info、compile context merge、classpath backup 和 deploy history 序列化中完整保留；修改字段结构时先判断旧值能否确定性迁移，不能仅通过提升 compile context 版本迫使用户重新全量构建。
- 远端 classpath 过滤规则不能随普通模块数量线性增长；普通 `${moduleRoot}/build` 输出按 variant 去重，自定义 build directory 与配置路径保持精确。
- `ModuleBuildPathInfo.allBuildPathRelative` 包含当前 variant 的 `intermediates/data_binding_artifact`，确保远端 Gradle 编译生成的 DataBinding setter store 等产物会同步回本地。
- `ModuleBuildPathInfo.rFilePath` 只在既有 application R.jar 匹配候选内选择；当 AGP 升级或远端同步导致多个候选 `R.jar` 并存时，按 `lastModifiedTime` 选择最新产物，mtime 相同时保留候选匹配顺序，低版本 library R.jar 仍走独立兼容分支；`BaseCompileContext` 会在多候选时打印 debug 日志。
- `ModuleBuildPathInfo.javaClassPath` 在 `intermediates/javac/<variant>/classes` 与 `compile<Variant>JavaWithJavac/classes` 并存时同样按 `lastModifiedTime` 选择最新目录；`allClassPath` 只挂载解析后的单一 Java 输出目录，避免 AGP 升级后旧目录 shadow 新 class。
- `readProjectInfo.gradle.kts` 读取依赖时使用上次 project info 做 CRC 缓存，但不能只依赖缓存，因为 transitive dependency 信息可能不完整。
- `:idea:prepareSandbox` 必须把仓库 `third_party`（排除 `sources` payload）、根目录 `THIRD_PARTY_NOTICES.md`、生成的 `SOURCE.md` 和源码校验值放入 `jugg/third_party`；对应源码保留在 `SOURCE.md` 指向的公开不可变 Git revision。`:idea:buildPlugin` 结束后由 `verifyThirdPartyCompliance` 校验 104 行组件清单、固定许可证选择、许可证/源码定位/修改声明、仓库源码 SHA-256、CI 源码 Git 状态、插件内无源码 payload、合规数据压缩后不超过 256 KiB，以及 104 个 package 的 SPDX 2.3 SBOM。第三方资产缺失或不匹配必须让发行构建失败，不能降级为 warning。
- include build 会把各自的 `gradle_project_infos.json` 复制成 `include_build_N_gradle_project_infos.json`，主工程只通过列表文件引用。
- include build project info task 仅在 composite build 根构建中注入；无 included build 的项目不注册额外 task，也不改变原有读取时机。
- include build 本轮快照缺失时保留同索引的上一次有效副本；只有旧副本也不存在时才从列表跳过，避免一次读取失败清空可用元数据。
- IDE 可能把不同 Gradle build 中的同名模块都简化为相同 simple name。若同名模块分别指向不同相对路径，只有 Gradle 侧模块明确为 `Application`、来自主 Gradle 快照且存在真实 R.jar 时，才完整保留该 Gradle Application；多个 R.jar 候选继续由 `ModuleBuildPathInfo.rFilePath` 按修改时间选择最新产物。条件不满足时仍走原有字段合并，普通 Library 不受影响。冲突日志会记录 IDE/Gradle 模块路径、是否属于主快照、候选 R.jar 和最终选择。
- IDE project info 完全缺失某个 Gradle module 时，merge 会在该 Gradle-only module 已进入最终模块集合后补回指向它的 Gradle 依赖。补入前从目标模块检查是否能够沿当前依赖图到达 owner；若会形成环则打印 warning 并放弃该依赖。IDE 已识别模块之间的依赖仍保持原有选择策略，不执行无条件并集。
- diff mode 只输出依赖差异并清理临时 project info；非 diff mode 才写正式 `gradle_project_infos.json`。
- 依赖 diff 的用户确认是正确性边界，不要为了“自动化”直接把 `CHANGED_NOT_SYNCED` 改成 `INCREMENTAL_COMPILE`。diff 失败或用户拒绝时应保持 Gradle rebuild 语义；diff 无依赖变化时也只有用户明确选择忽略 build file 变化后才能继续增量。
- androidTest task 注入发生在 Gradle task graph finalization 之前；如果请求任务名和真实 task path 对不上，注入会静默打印 “no requested task found” 并跳过。
- CLI run configuration 文件包含远端编译密码，写入使用临时文件 + 原子替换，并在 POSIX 平台限制为当前用户读写；日志和诊断不得输出密码或原始远程 Gradle command。远程环境变量的实际传递不受限制，但日志只允许完整显示 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`GRADLE_USER_HOME`；`PATH` 仅记录已配置，其他变量只记录名称和数量。SSH shell 在发送带环境变量的 command 前必须成功关闭 PTY echo；无法确认关闭时终止远程编译，避免 command 回显泄露。

---

## 7. 排查入口

| 现象 | 优先入口 |
|---|---|
| Gradle root 与 IDE project root 不一致 | `GradleProjectInfoReaderManager` 的 `jugg.projectDir` 处理 |
| 模块 source/res/manifest 路径异常 | `GradleProjectInfoReader.getModuleInfo()` 与 `ModuleBuildPathInfo` |
| AGP 升级后找不到 R.jar / manifest / data binding 输出 | `ModuleBuildPathInfo` 对应属性 |
| project info JSON 缺字段 | `ModuleInfo` 字段同步清单、`ProjectInfoSerializerInGradle`、`JuggProjectInfoMerger` |
| AGP 升级后增量 D8 断言/不兼容 | `JuggProjectInfo.agpR8Classpath`、`GradleProjectInfoReaderManager.findAgpR8Classpath()`、`DexFileMaker` |
| include build 模块缺失 | `gradle_include_builds.txt` 与 `JuggProjectInfoMerger` |
| 同名 app 合并后 R.jar 指向外部工程 | `JuggProjectInfoMerger` 的主 Gradle Application + R.jar 存在性保护日志 |
| 自定义 build directory 后找不到 APK 输出 | `ModuleBuildPathInfo.buildDirRelativePath`、`AsDeployerCompat.getSuggestRunConfigurations()`、`LocalGradleCompileClient`、`FindOutputCommand` |
| 依赖变化未感知或确认结果不符合 Runtime | `GradleDependencyDiffer`、`DependencyChangeManagerByGradle` / `DependencyChangeManagerBySync`、对应 `CompileUiHandler` 实现 |
| library androidTest target package 异常 | 实际 Test APK manifest、`buildAndroidTestModuleInfo()`、`LibraryTestApkBuildHistory` |
| Compose 默认/自定义资源目录未识别 | `GradleProjectInfoReader.getComposeResourceInfo()`、`readComposeResourceDirectories()` 与序列化后的 `composeResourceInfo` |
| Compose resource API 不受支持 | task 类型集合与必要属性、task class 的 code source、generator class/method/constructor 结构及 `unsupportedReason` |

---

## 8. 关联文档

- 编译核心：`02_compile_core.md`
- androidTest：`06_android_test.md`
- IDE 编排：`04_engineering_ide.md`
- 兼容层：`04_engineering_compat.md`
- 运行时排查：`09_plugin_runtime_debug.md`
