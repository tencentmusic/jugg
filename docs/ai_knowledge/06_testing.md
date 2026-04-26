# 单元测试指南（TDD）

> 最后核对：2026-03-17
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 选测试文件的优先级规则

在新增测试前，必须按以下优先级决策：

1. **【最高优先级】同测试对象复用已有文件**：先查第3节索引，若已有覆盖同一被测类/模块的文件，必须在该文件中追加用例，不得新建。
2. **【次优先级】优先 main 模块**：若同被测对象在 main 和 idea 都没有测试，优先在 `main` 模块新建测试，仅当 main 模块无法覆盖（如需要 IDE API、JuggCompiler 端到端流程、Git 集成）时才在 `idea` 模块新建。

---

## 2. 测试基础设施

### 2.1 前置条件

测试依赖一个真实编译过的 Android Demo APK。在 `@Before` 中调用 `clearBuild()` 会自动触发：

```kotlin
fun clearBuild() {
    AssembleAndroidProjectOnce.ensure()   // 首次调用时 assemble android_demo_project
    buildDir.clearDir()
}
```

`AssembleAndroidProjectOnce.ensure()` 保证 APK 只 assemble 一次，后续测试复用缓存。

### 2.2 关键全局变量（来自 `mock/Commons.kt`）

| 变量 | 含义 |
|------|------|
| `buildDir` | 临时编译输出目录（测试隔离用） |
| `stagingDir` | 暂存目录，用于 SourceCompiler / DexCompiler 输出 |
| `assetsAndroidDir` | `android_demo_project` 根目录（源码和 APK） |
| `assetsAndroidModifySourceDir` | 包含「修改版」源文件的目录（用于模拟增量修改） |
| `assetsLibDir` | 测试所需第三方 jar（如 kotlin-stdlib） |
| `projectInfo` | 包含 `apkFile`、`apkInfos` 等 APK 元信息 |
| `context` | `SimpleCompileContext`，封装 module / apk / tempModule |
| `mockParentDisposable` | IDE 资源释放 mock |

### 2.3 为什么用真实 testcase 类而非手写 mock 数据

`DeployDataGeneratorTest` 依赖真实 D8 编译产物中的 `MethodNode`、`ClassNode` 等结构。手工构造容易遗漏编译器生成的细节（如 Kotlin lambda 对应的 `$r8$lambda$` 静态合成方法）。

**结论：用真实 Kotlin/Java 类写 testcase，让 D8 编译后从 APK 里读取，测试才有可信度。**

---

## 3. 已有测试文件索引

### 3.1 main 模块（`main/src/test/`）

#### compiler/constref — 常量引用影响分析

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `ConstRefEngineTest.kt` | `ConstRefEngine` | 影响分析引擎核心逻辑 |
| `ConstRefIntegrationTest.kt` | ConstRef 全链路 | 编译 + 影响分析集成 |
| `JavaConstParserTest.kt` | Java 常量解析器 | Java 源码中常量定义解析 |
| `KotlinConstParserTest.kt` | Kotlin 常量解析器 | Kotlin 源码中常量定义解析 |
| `ConstDefinitionIndexTest.kt` | `ConstDefinitionIndex` | 常量定义索引构建与查询 |
| `ConstRefCacheDatabaseTest.kt` | ConstRef 缓存 DB | 缓存数据库读写 |
| `ConstRefSessionCacheTest.kt` | 会话级缓存 | 单次编译会话内缓存命中 |
| `RepoSharedFingerprintStoreTest.kt` | 指纹共享存储 | 跨会话指纹持久化 |
| `ConstRefEngineBenchmarkTest.kt` | `ConstRefEngine` | 性能基准（非回归测试） |

#### compiler/obfuscation — 混淆映射

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `ClassObfuscatorTest.kt` | `ClassObfuscator` | class 字节码混淆：类/方法/字段重命名、superclass、interface |
| `DexObfuscatorTest.kt` | `DexObfuscator` | DEX 混淆：方法调用、字段访问、缓存命中 |
| `R8MappingReaderTest.kt` | `R8MappingReader` | mapping.txt 解析（v3.2/v8.1）、类查询、方法 inline |
| `R8MappingTest.kt` | R8 规则验证 | keep/混淆/删除等规则行为（集成） |
| `DexMinifyCompilerPhase2Test.kt` | `DexMinifyCompiler` Phase2 | JuggFix class 生成 |

#### compiler/overlay — 资源编译

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `ResourceCompileTest.kt` | 资源编译流程 | 标准资源增量编译 |
| `ResourceCompileAabResGuardTest.kt` | AAB + ResGuard | AAB 格式下 ResGuard 资源编译 |

#### compiler/source — 源码编译扩展

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `JuggAptCompilerTest.kt` | `JuggAptCompiler` | APT 注解处理编译 |
| `JavaDiagnosticLocaleTest.kt` | Java 诊断 locale | 编译错误信息 locale 一致性 |
| `R8FileMakerTest.kt` | R8 输入文件生成 | basic obfuscation、method inlining |

#### compiler — 编译入口

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `IncrementalCompilerHelperTest.kt` | `IncrementalCompilerHelper` | 增量编译辅助逻辑 |
| `SourceCompileTest.kt` | `SourceCompiler` | Java/Kotlin 源码编译基础 |
| `SourceMinifyCompileTest.kt` | 带混淆的源码编译 | 各类 keep 规则下的混淆编译 |

#### deploy/data — 部署数据与影响分析

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `DeployDataGeneratorTest.kt` | `DeployDataGenerator`（内存版） | hot reload/hotfix、effectedSource、子类传播、desugar classpath、constRef、**static lambda 误传播 bug 回归**、**generic signature 变更触发子类/调用方重编译** |
| `DeployDataGeneratorReleaseTest.kt` | `DeployDataGenerator`（release/minify） | minify 场景下方法删除/inline 的影响传播 |
| `DeployDataDatabaseSqLiteHelperTest.kt` | `DeployDataDatabaseSqLiteHelper` | SQLite 持久化：APK 写入、更新、多 APK、表大小验证 |
| `SourceFileDatabaseSqLiteHelperTest.kt` | `SourceFileDatabaseSqLiteHelper` | 源文件数据库：创建、目录更新、文件更新 |
| `ClassFileParserTest.kt` | class 文件解析器 | 接口解析、静态调用解析、jar 包处理 |

#### deploy — 部署逻辑

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `ApkInfoSerializerTest.kt` | `ApkInfoSerializer` | APK 元信息序列化/反序列化 |
| `CompileEffectAnalyzerTest.kt` | `CompileEffectAnalyzer` | 编译影响分析 |
| `DeployFileManagerDexMergeTest.kt` | `DeployFileManager` | DEX merge 触发逻辑、merge 后历史不重复计入 |
| `DeployHistoryManagerTest.kt` | `DeployHistoryManager` | 历史 DB、部署 DB、未变更文件过滤 |
| `JuggJvmtiAgentManagerTest.kt` | `JuggJvmtiAgentManager` | JVMTI agent 管理 |

#### ai/mcp/actions — MCP 工具 action

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `CompileAndDeployMcpToolActionTest.kt` | `CompileAndDeployMcpToolAction` | 截断详情/日志 artifact、超时、app not ready |
| `EvalViewMcpToolActionTest.kt` | `EvalViewMcpToolAction` | View getter 表达式执行 |
| `LayoutDumpMcpToolActionTest.kt` | `LayoutDumpMcpToolAction` | inline/file 模式、px→dp、大 payload、rootLayout、clickable 计数 |
| `LayoutVerifyMcpToolActionTest.kt` | `LayoutVerifyMcpToolAction` | schema 校验、property/spacing/overlap 检查、checksFile |
| `McpAppReadyGuardTest.kt` | `McpAppReadyGuard` | 前置检查重试、超时、后置检查 |
| `RecordMcpToolActionTest.kt` | `RecordMcpToolAction` | app not ready、sessionId 校验 |
| `RestartAppMcpToolActionTest.kt` | `RestartAppMcpToolAction` | tap_actions 顺序执行、swipe/longPress、元素查找重试、失败 step index |
| `RuntimeObserveMcpToolActionTest.kt` | `RuntimeObserveMcpToolAction` | crash summary artifact、package 缺失 |
| `TapMcpToolActionTest.kt` | `TapMcpToolAction` | coordinate/percent/element 模式、swipe、longPress、多匹配、资源 ID fallback |
| `WaitLogsMcpToolActionTest.kt` | `WaitLogsMcpToolAction` | marker 命中、crash 命中、timeout、无效正则、缺 marker、无 deploy 基线、多主进程 PID、子进程 marker、tag 过滤、marker 被其它 app 误触发（PID 拦截）、其它 app crash 不误触发、启动前系统 crash 不误触发、子进程 crash 忽略、100 行截断、缓冲区溢出 |

#### ai/mcp/layout — 布局校验子模块

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `RelationExtractorTest.kt` | `RelationExtractor` | 从 Figma JSON 提取布局关系 |
| `ElementMatcherTest.kt` | `ElementMatcher` | 布局元素模糊匹配（IoU） |
| `FigmaJsonParserTest.kt` | `FigmaJsonParser` | Figma JSON 解析 |
| `RelationVerifierTest.kt` | `RelationVerifier` | 关系校验逻辑 |

#### mcp — MCP 协议层

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `McpInvokerProtocolTest.kt` | `McpInvoker` 协议 | initialize、ping、tools/list、notifications |
| `McpInvokerErrorHandlingTest.kt` | `McpInvoker` 错误处理 | 参数缺失、工具未找到、project 未初始化 |
| `McpInvokerToolSuccessTest.kt` | `McpInvoker` 工具成功路径 | 全部工具的正常调用链路（含 `wait-logs`） |
| `McpInvokerValidationTest.kt` | `McpInvoker` 参数校验 | 未知参数拒绝、必填参数缺失（含 `wait-logs` 缺 marker） |
| `ViewHierarchyClientTest.kt` | `ViewHierarchyClient` | socket 候选解析、pid fallback、legacy socket、版本不匹配警告 |

#### project

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `BaseCompileContextChangedFileBridgeTest.kt` | `BaseCompileContextChangedFileBridge` | 文件变更事件桥接 |

---

### 3.2 idea 模块（`idea/src/test/`）

#### compile — 编译集成（需 IDE 上下文）

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `AssetCompileTest.kt` | Assets/资源编译 | 资源文件增量编译 |
| `BuildDemoApkTest.kt` | Demo APK 完整构建 | clean/build/parse/mapping，回归基准 |
| `DependencyDiffResultTest.kt` | `DependencyDiffResult` | 依赖增/删/改 diff |
| `DexCompileTest.kt` | DEX 编译（idea 层） | DEX 编译集成 |
| `DexPackageRenamerTest.kt` | `DexPackageRenamer` | DEX 包名重命名 |
| `DexTest.kt` | DEX 基础操作 | DEX 读写基础 |
| `JavaCompileTest.kt` | Java 编译（idea 层） | Java 增量编译集成 |
| `JuggCompileForDataBindingTest.kt` | DataBinding 编译 | 字段名/类名变更、多源文件、Kotlin、kapt |
| `JuggCompileTest.kt` | `JuggCompiler` 端到端 | 完整增量编译流程 |
| `KmModuleMergerTest.kt` | `KmModuleMerger` | Kotlin metadata 模块合并 |
| `KotlinCompileTest.kt` | Kotlin 编译（idea 层） | metadata 错误、KSP1/KSP2 |
| `ModuleCompileOrderUtilsTest.kt` | `ModuleCompileOrderUtils` | 模块拓扑排序 |
| `RFileFixerTest.kt` | `RFileFixer` | 大型 R.java 修复 |
| `RPackageReaderTest.kt` | `RPackageReader` | R 包名读取 |
| `StyleableFileGeneratorTest.kt` | `StyleableFileGenerator` | Styleable 文件生成 |

#### compiler/manifest — Manifest 处理

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `ManifestDifferTest.kt` | `ManifestDiffer` | manifest diff：属性增/删/改 |
| `AndroidManifestMergerTest.kt` | `AndroidManifestMerger` | manifest 合并（继承 `ManifestDifferTest`） |
| `AndroidManifestCompilerTest.kt` | `AndroidManifestCompiler` | manifest 编译：新增 Activity、更新 |
| `ApkFileModifierTest.kt` | `ApkFileModifier` | APK 内 manifest 修改 |
| `XmlParserTest.kt` | XML 解析器 | XML 打印/解析 |

#### aapt2 / apk — APK 读取

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `aapt2/ApkReaderTest.kt` | aapt2 daemon 调用 | aapt2 多次调用、package name、default activity |
| `apk/ApkReaderTest.kt` | `BuildToolsVersionComparator` | build tools 版本号比较 |

#### deploy

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `DeployTargetManagerTest.kt` | `DeployTargetManager` | 部署目标管理 |
| `InstallerDownloadTest.kt` | installer 下载 | installer 下载流程 |

#### git — Git 集成

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `GitManagerTest.kt` | `GitManager` | init/diff/last commit/filter changed files |
| `GitManagerWorktreeTest.kt` | `GitManager`（worktree） | worktree 场景下 git 操作 |
| `FileMatcherTest.kt` | `FileMatcher` | gitignore 风格文件匹配 |

#### gradle — Gradle 客户端

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `IsNormalGradleCommandTest.kt` | Gradle 命令判断 | 正常/非正常 Gradle 命令识别 |
| `LocalGradleCompileClientTest.kt` | `LocalGradleCompileClient` | compile/cancel/library changes |
| `RemoteGradleCompileClientTest.kt` | `RemoteGradleCompileClient` | 继承 Local 用例 + 远端 classpath |
| `ProjectInfoSerializerInGradleTest.kt` | `ProjectInfoSerializer` | Gradle 内项目信息序列化 |

#### ide/logic

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `ClientSetupDocExporterTest.kt` | 客户端设置文档导出 | 导出文档内容 |
| `JuggSkillInstallerTest.kt` | `JuggSkillInstaller` | skill 安装 |
| `PluginVersionComparatorTest.kt` | `PluginVersionComparator` | 插件版本号比较 |

#### manager — 顶层流程

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `JuggCompilerTest.kt` | `JuggCompiler` | Java/Kotlin 类增/改签名/改方法/改变量的完整增量编译 |
| `CompileConsistencyTest.kt` | 编译一致性 | 多轮编译结果一致性 |
| `TopLevelFlowTest.kt` | 顶层 install+deploy 流程 | install、deploy、kt activity deploy |
| `TopLevelFlowWithGitTest.kt` | 顶层流程（git 场景） | 含 git diff 的 deploy 流程 |

#### mcp — MCP 服务端

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `McpLocalServerTest.kt` | `McpLocalServer` | HTTP server 启停、tools/list、协议校验、非法 JSON |

#### project / server

| 文件 | 被测对象 | 覆盖场景 |
|------|----------|----------|
| `FileChangesHandlerTest.kt` | `FileChangesHandler` | source/build/custom build rules 文件变更 |
| `JuggProjectInfoLibraryMergerTest.kt` | `JuggProjectInfoLibraryMerger` | 依赖库合并：增/删/改/重复/多 jar 部分缺失 |
| `JuggServerTest.kt` | `JuggServer` | server report 接口 |

---

## 4. 添加 testcase 类的规范

### 4.1 目录约定

```
android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/
└── <feature>/          # 按 bug 或场景命名，snake_case
    ├── TargetClass.kt  # 被测行为的核心类（open/abstract/interface）
    ├── SubClass.kt     # 子类（测试继承传播）
    └── InvokerClass.kt # 调用者（测试 method_refs 查询）
```

已有 testcase 目录一览：

| 目录 | 场景 |
|------|------|
| `subclass/` | 虚方法变更触发子类重编译 |
| `newabstractmethod/` | 新增抽象方法触发实现类重编译 |
| `newinterfacemethod/` | 新增 interface 方法触发实现类重编译 |
| `lambdaparent/` | Kotlin lambda 引起的静态方法误传播 bug |
| `genericcascade/` | 类级 generic signature 变化触发子类重编译 |
| `genericcaller/` | 具体类绑定的泛型实参变化触发 direct member caller 重编译 |
| `ktdefaultparam/` | Kotlin 默认参数导致调用方重编译 |
| `kttopleveloptionalfunction/` | Kotlin top-level 默认参数函数 |
| `defaultinterface/` | Java 8 default interface 的 desugar classpath |
| `constref/` | 常量引用影响分析 |
| `minify/` | R8 混淆/删除/inline 场景 |

### 4.2 类设计原则

- 每个 testcase 目录只覆盖**一个场景**，类越少越好。
- 命名体现角色：`XxxParent` / `XxxChild` / `XxxInvoker` / `XxxImpl`。
- 类本身不需要业务逻辑，`println` 占位即可。
- 修改后需重新 assemble demo 项目（删除 `skip_assemble` flag 或手动执行）。

---

## 5. 编写 DeployDataGeneratorTest 的模式

### 5.1 从 APK 中提取单个类的 ParsedDex

```kotlin
private fun getParsedDex(className: String): ParsedDex {
    val classSigName = className.classSigName   // "Lcom/..." 格式
    return ParsedDex(
        parsedApk.classes.filter { it.key == classSigName }.map {
            ClassDeployItem(
                DeployItem(it.key, CompileOutput.Type.Dex, 0, byteArrayOf(), DeployItem.FLAG_CLASS),
                listOf(it.value),
            )
        },
        parsedApk.methodRefs.filter { it.value.contains(classSigName) }.mapValues { listOf(classSigName) },
        parsedApk.fieldRefs.filter { it.value.contains(classSigName) }.mapValues { listOf(classSigName) },
        parsedApk.subclassRefs.filter { it.value.contains(classSigName) }.mapValues { listOf(classSigName) },
    )
}
```

### 5.2 模拟方法变更（增/删/改）

```kotlin
// 删除某个方法（模拟方法消失）
val modifiedMethods = classNode.methods.filter { it.name != "targetMethod" }
val modifiedParsedDex = parsedDex.updateMethods(modifiedMethods)

// 新增一个方法（模拟新增抽象方法）
val addedMethods = classNode.methods + MethodNode(
    classNode.className,
    DexConstants.ACC_PUBLIC or DexConstants.ACC_ABSTRACT,
    "newMethod",
    "()V",
)

// 修改方法 access flag（模拟可见性变化）
val modifiedMethods = classNode.methods.map {
    if (it.name == "haha") MethodNode(it.owner, DexConstants.ACC_PRIVATE, it.name, it.desc)
    else it
}
```

### 5.3 断言受影响的 source 文件

```kotlin
val data = generator.buildDeployData(modifiedParsedDex, emptyList())

// 精确匹配（注意排序）
assertEquals(listOf("SubClass1.java", "SubClass2.java").sorted(), data.effectedSourceFileNames.sorted())

// 负向测试（不应触发重编译）
assertFalse(data.effectedSourceFileNames.contains("UnrelatedClass.kt"))

// 正向测试（应触发重编译）
assertTrue(data.effectedSourceFileNames.contains("InvokerClass.kt"))
```

`effectedSourceFileNames` 扩展属性：

```kotlin
private val JuggDeployData.effectedSourceFileNames
    get() = effectedClassNodes.map { it.sourceFileName }.distinct()
```

---

## 6. 运行测试

```bash
# 运行单个 test class
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest"

# 运行 main 全部测试
./gradlew :main:test

# 运行 idea 全部测试
./gradlew :idea:test

# 首次运行前需确保 ANDROID_HOME 已设置
export ANDROID_HOME=/path/to/android/sdk
```

首次运行会触发 `android_demo_project` 的 Gradle assemble，耗时较长（几分钟），之后走缓存。

---

## 7. 跳过 Assemble 加速本地迭代

若已有编译产物不想重新 assemble，可创建 flag 文件：

```bash
mkdir -p ~/.jugg/test_flag
touch ~/.jugg/test_flag/enabled
touch ~/.jugg/test_flag/skip_assemble
```

删除 `skip_assemble` 文件即可恢复自动 assemble（新增 testcase 类后必须删除）。

---

## 8. 常见陷阱

| 问题 | 原因 | 解决 |
|------|------|------|
| `getParsedDex` 返回空 classDeployItems | 类名写错，或 demo project 未重新 assemble | 检查类名拼写；删除 skip_assemble 重跑 |
| `staticLambdaMethods.isNotEmpty()` 断言失败 | D8 未生成 lambda 静态方法，可能 Kotlin 版本差异 | 检查 demo project 编译产物 |
| 测试通过但 SQLite 版本仍有问题 | `DeployDataGeneratorTest` 走的是内存版 `IncrementalDeployDataDatabase`，SQLite 路径需看 `DeployDataDatabaseSqLiteHelperTest` | 两个实现都需要覆盖 |
| `AssembleAndroidProjectOnce` 编译错误 | 依赖了 `idea` 模块中的类（如 `TestModeManager`） | `main` 的测试代码只能依赖 `main` 模块 |
| 在 main 模块无法编写测试 | 被测逻辑依赖 IntelliJ Platform API（如 `Project`、`Module`） | 在 idea 模块对应文件中追加用例 |
