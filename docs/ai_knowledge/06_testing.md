# 单元测试指南（TDD）

> 最后核对：2026-05-04
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 选测试文件的优先级规则

新增测试前，必须按以下优先级决策：

1. **【最高优先级】复用已有文件**：先按第3节查找已有测试。若已有覆盖同一被测类/模块的文件，必须追加用例，不得新建。
2. **【次优先级】优先 main 模块**：若同被测对象在 main 和 idea 都没有测试，优先在 `main` 模块新建测试。
3. **【仅在必要时】使用 idea 模块**：只有被测逻辑依赖 IDE API、RunConfig、JuggCompiler 端到端流程、Git 集成等 main 无法覆盖的能力时，才在 `idea` 模块新增或追加测试。

> app androidTest / instrumentation 支持的实现链路与定向测试入口见 [`06_android_test.md`](06_android_test.md)。

---

## 2. 测试基础设施

### 2.1 前置条件

测试依赖一个真实编译过的 Android Demo APK。在 `@Before` 中调用 `clearBuild()` 会自动触发：

```kotlin
fun clearBuild() {
    AssembleAndroidProjectOnce.ensure()
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

`DeployDataGeneratorTest` 依赖真实 D8 编译产物中的 `MethodNode`、`ClassNode` 等结构。手工构造容易遗漏编译器生成的细节，例如 Kotlin lambda 对应的 `$r8$lambda$` 静态合成方法。

结论：用真实 Kotlin/Java 类写 testcase，让 D8 编译后从 APK 里读取，测试才有可信度。

---

## 3. 查找已有测试

新增测试前先用被测类名、包名或能力关键词查现有 `*Test.kt`。能追加到同一被测对象的测试文件时，不新建文件。

```bash
# Search by production class or action name
rg -n "DeployDataGenerator|CompileAndDeployMcpToolAction" main/src/test idea/src/test

# Search candidate test files by feature keyword
rg --files main/src/test idea/src/test | rg "DeployData|McpToolAction|Compiler"

# Search existing demo testcase scenarios
rg --files android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase
```

优先路径：

| 场景 | 优先测试目录 | 说明 |
|------|--------------|------|
| 纯 main 逻辑、数据结构、协议解析 | `main/src/test/` | 默认优先，避免引入 IDE 测试开销 |
| 依赖 IntelliJ Platform API、RunConfig、IDE 生命周期 | `idea/src/test/` | main 无法覆盖时再放 idea |
| `DeployDataGenerator` 影响分析 | `main/src/test/.../deploy/data/` | 通常复用 `DeployDataGeneratorTest.kt` 或 SQLite 专测 |
| MCP action / layout 子模块 | `main/src/test/.../ai/mcp/` | action 与协议层分别找已有测试 |
| 完整编译/部署集成 | `idea/src/test/.../manager/` 或 `idea/src/test/.../compile/` | 仅在单元层无法证明行为时使用 |

---

## 4. 添加 testcase 类的规范

### 4.1 目录约定

```text
android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/
└── <feature>/          # Use snake_case scenario name
    ├── TargetClass.kt  # Core class under test
    ├── SubClass.kt     # Subclass for cascade cases
    └── InvokerClass.kt # Caller for method_refs cases
```

已有目录按场景命名。新增前先用第3节命令确认是否已有相同场景，避免为同一 bug 或传播规则拆出多个 testcase 目录。

### 4.2 类设计原则

- 每个 testcase 目录只覆盖**一个场景**，类越少越好。
- 命名体现角色：`XxxParent` / `XxxChild` / `XxxInvoker` / `XxxImpl`。
- 类本身不需要业务逻辑，`println` 占位即可。
- 修改后需重新 assemble demo 项目：删除 `skip_assemble` flag 或手动执行。

---

## 5. 编写 DeployDataGeneratorTest 的模式

### 5.1 从 APK 中提取单个类的 ParsedDex

```kotlin
private fun getParsedDex(className: String): ParsedDex {
    val classSigName = className.classSigName
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
val modifiedMethods = classNode.methods.filter { it.name != "targetMethod" }
val modifiedParsedDex = parsedDex.updateMethods(modifiedMethods)

val addedMethods = classNode.methods + MethodNode(
    classNode.className,
    DexConstants.ACC_PUBLIC or DexConstants.ACC_ABSTRACT,
    "newMethod",
    "()V",
)

val methodsWithChangedAccess = classNode.methods.map {
    if (it.name == "targetMethod") MethodNode(it.owner, DexConstants.ACC_PRIVATE, it.name, it.desc)
    else it
}
```

### 5.3 断言受影响的 source 文件

```kotlin
val data = generator.buildDeployData(modifiedParsedDex, emptyList())

assertEquals(listOf("SubClass1.java", "SubClass2.java").sorted(), data.effectedSourceFileNames.sorted())
assertFalse(data.effectedSourceFileNames.contains("UnrelatedClass.kt"))
assertTrue(data.effectedSourceFileNames.contains("InvokerClass.kt"))
```

`effectedSourceFileNames` 扩展属性：

```kotlin
private val JuggDeployData.effectedSourceFileNames
    get() = effectedClassNodes.map { it.sourceFileName }.distinct()
```

---

## 6. 运行测试

禁止不加 `--tests` 过滤运行完整测试套件。开发完成后运行本次改动对应的定向测试；需要编译兜底时，可运行 `:idea:compileKotlin`。

```bash
# Run one test class
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest"

# Run one test method
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.JuggCompilerTest.testName"

# Compile verification without running full tests
./gradlew :idea:compileKotlin

# Ensure ANDROID_HOME is set before first run
export ANDROID_HOME=/path/to/android/sdk
```

首次运行会触发 `android_demo_project` 的 Gradle assemble，耗时较长（几分钟），之后走缓存。

设备相关测试或 androidTest 定向入口见 [`06_android_test.md` 第6节](06_android_test.md#6-测试入口)。

---

## 7. 跳过 Assemble 加速本地迭代

若已有编译产物不想重新 assemble，可创建 flag 文件：

```bash
mkdir -p ~/.jugg/test_flag
touch ~/.jugg/test_flag/enabled
touch ~/.jugg/test_flag/skip_assemble
```

删除 `skip_assemble` 文件即可恢复自动 assemble。新增 testcase 类后必须删除。

---

## 8. 常见陷阱

| 问题 | 原因 | 解决 |
|------|------|------|
| `getParsedDex` 返回空 classDeployItems | 类名写错，或 demo project 未重新 assemble | 检查类名拼写；删除 skip_assemble 重跑 |
| `staticLambdaMethods.isNotEmpty()` 断言失败 | D8 未生成 lambda 静态方法，可能 Kotlin 版本差异 | 检查 demo project 编译产物 |
| 测试通过但 SQLite 版本仍有问题 | `DeployDataGeneratorTest` 走的是内存版 `IncrementalDeployDataDatabase` | SQLite 路径需覆盖 `DeployDataDatabaseSqLiteHelperTest` |
| `AssembleAndroidProjectOnce` 编译错误 | 依赖了 `idea` 模块中的类 | `main` 的测试代码只能依赖 `main` 模块 |
| 在 main 模块无法编写测试 | 被测逻辑依赖 IntelliJ Platform API | 在 idea 模块对应文件中追加用例 |
