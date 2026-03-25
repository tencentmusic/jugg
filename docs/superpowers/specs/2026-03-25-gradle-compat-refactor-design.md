# Gradle 多版本兼容性重构设计文档

> 日期：2026-03-25

---

## 1. 背景与问题

`readProjectInfo.gradle.kts` 是一个在用户 Android 项目构建时注入的 init script，由 `buildReadProjectInfoScript.gradle` 在编译期将多个 Kotlin 源文件拼合生成。

**根本约束**：Kotlin 1.5（Gradle 7）将 `.kts` 脚本中所有顶层类编译为脚本外部类的**非静态内部类**。这带来两类问题：

### 问题 A：Companion 成员调用问题

内部类 A 调用兄弟内部类 B 的 companion 成员时，Kotlin 1.5 后端通过 `Companion` 单例实例进行分发，而 `Companion` 本身依赖外部类实例传递。在某些路径下外部实例传递出错，导致运行时 `NoSuchMethodError`。

**受影响的 companion 成员**：`DependencyDiffResult.create`、`DependencyDiffResultSet.createEmpty`、`ProjectInfoSerializerInGradle.getJsonGenerator`、`JuggProjectInfoSerialize.serialize/deserialize`、`XmlAndroidManifestInfo.parse`。

**当前方案**：`buildReadProjectInfoScript.gradle` 在构建期通过约 200 行 Groovy 代码动态解析 Kotlin 源文件，将 companion 成员提取到顶层。副作用：逻辑复杂、维护成本高、引入 `companionKeepOwners` 例外集合、需要成员名重写规则。

### 问题 B：跨类构造问题（遗漏未修）

内部类 A 直接调用 `InnerClassB(args)` 时，Kotlin 1.5 后端在部分路径下没有正确注入外部类实例参数，导致运行时 `NoSuchMethodError: void Script$ClassName.<init>(ArgType)`。

**已确认触发场景**：`GradleApplicationInjector.tryReplace()` 通过 `doLast { ... }` lambda 构造 `InitScriptManifestXmlHelper(mergedManifest)`，导致 Gradle 7 上 `processDebugManifest` 任务失败。

**已通过的对比场景**：`GradleProjectInfoReaderManager` 直接构造 `GradleProjectInfoReader`、`GradleDependencyDiffer` 等，当前测试通过。两者触发条件的差异（lambda 捕获 vs 直接调用）尚未完全确认，本次保守处理——对所有跨类直接构造统一应用工厂函数。

---

## 2. 重构目标

1. **修复** Gradle 7 的 manifest task 失败（问题 B 遗漏）
2. **简化** `buildReadProjectInfoScript.gradle`：删除 `transformSourceForInitScript` 等动态 AST 转换逻辑，改为轻量的构造替换 + 工厂函数注入
3. **源文件零改动**（问题 B）：所有跨类构造的兼容处理完全在 build script 生成阶段完成
4. **保留实验开关**：验证 `@JvmStatic` 是否同时解决问题 B

---

## 3. 解决方案

### 3.1 问题 A：build script companion 提取（必须）

**根本原因更新**：实测发现 `companion object` 本身在不同 Gradle 版本中均不可用：
- **Gradle 9 / Kotlin 1.9+**：编译期直接拒绝，报 "Object Companion captures the script class instance"
- **Gradle 7 / Kotlin 1.5 + `@JvmStatic`**：后端崩溃，报 "wrong bytecode generated for static initializer"
- **Gradle 7 / Kotlin 1.5（无 `@JvmStatic`）**：能编译，但运行时调用 companion 成员时 `NoSuchMethodError`（原问题 A）

因此 **companion 提取是必须的**，不可省略。

**`@JvmStatic` 处理**：
- 源文件保留 `@JvmStatic`（对普通 JVM 编译有价值，无副作用）
- 生成的 `.kts` 中过滤掉 `@JvmStatic`（顶层函数不能使用此注解）

**build script 中的 companion 提取逻辑**（相比旧实现大幅简化）：

1. 对每个源文件内容，用**括号深度追踪**定位 `companion object { ... }` 块
2. 将 companion 内容提取到文件顶层区域，同时：
   - 删除 `companion object { }` 包装行
   - 过滤 `@JvmStatic` 行
   - 对已知冲突成员进行重命名（见下表）
3. 对拼合后的完整脚本内容，做**限定调用重写**（`ClassName.memberName(` → `memberName(`）

**已知命名冲突**（仅此一处，其余成员名唯一无需重命名）：

| 原所在类 | companion 成员 | 提取为顶层后的名称 |
|---|---|---|
| `DependencyDiffResultSet` | `fun createEmpty()` | `fun dependencyDiffResultSetCreateEmpty()` |
| `DependencyDiffResult` | `fun createEmpty()` | `fun dependencyDiffResultCreateEmpty()` |

**限定调用重写规则**（完整列表）：

| 原调用 | 替换为 |
|---|---|
| `DependencyDiffResultSet.createEmpty(` | `dependencyDiffResultSetCreateEmpty(` |
| `DependencyDiffResult.createEmpty(` | `dependencyDiffResultCreateEmpty(` |
| `DependencyDiffResult.create(` | `create(` |
| `ProjectInfoSerializerInGradle.getJsonGenerator(` | `getJsonGenerator(` |
| `JuggProjectInfoSerialize.serialize(` | `serialize(` |
| `JuggProjectInfoSerialize.deserialize(` | `deserialize(` |
| `XmlAndroidManifestInfo.parse(` | `parse(` |
| `ModuleInfo.virtualModule` | `virtualModule` |
| `ModuleInfo.DEFAULT_BUILD_VARIANT` | `DEFAULT_BUILD_VARIANT` |
| `SigningConfig.EMPTY` | `EMPTY` |

### 3.2 问题 B：build script 扫描替换构造调用 + 注入工厂函数定义

在 `buildReadProjectInfoScript.gradle` 的生成阶段处理，**源文件零改动**。

**处理流程**（顺序不可颠倒）：

1. **替换**：对收集到的所有源文件内容，将指定类的构造调用 `ClassName(` 替换为 `className(`。使用 `(?<!class )ClassName\(` 正则，确保 `class ClassName(` 声明行不被误替换。
2. **注入**：完成替换后，在生成脚本末尾追加工厂函数定义。注入必须在替换之后，否则工厂函数定义本身也会被替换（导致无限递归）。

**需要处理的类**（替换 + 注入工厂函数定义）：

| 类 | 工厂函数签名 |
|---|---|
| `InitScriptManifestXmlHelper` | `fun initScriptManifestXmlHelper(manifestFile: File) = InitScriptManifestXmlHelper(manifestFile)` |
| `GradleProjectInfoReader` | `fun gradleProjectInfoReader(rootProject: Project, lastProjectInfo: JuggProjectInfoSerialize?) = GradleProjectInfoReader(rootProject, lastProjectInfo)` |
| `GradleDependencyDiffer` | `fun gradleDependencyDiffer(rootProject: Project, projectInfo: JuggProjectInfo) = GradleDependencyDiffer(rootProject, projectInfo)` |
| `ProjectInfoSerializerInGradle` | `fun projectInfoSerializerInGradle(dataFile: File) = ProjectInfoSerializerInGradle(dataFile)` |
| `JuggPathManager` | `fun juggPathManager(projectDir: File) = JuggPathManager(projectDir)` |
| `ModuleBuildPathInfo` | `fun moduleBuildPathInfo(projectRootDir: File, moduleRootDir: File, buildVariant: String) = ModuleBuildPathInfo(projectRootDir, moduleRootDir, buildVariant)` |
| `LibraryDependency` | `fun libraryDependency(name: String, file: File) = LibraryDependency(name, file)` 及 4 参数重载 `fun libraryDependency(name: String, file: File, lastModifiedTime: Long, crc32: Long) = LibraryDependency(name, file, lastModifiedTime, crc32)` |

命名规则：类名首字母小写（`ClassName` → `className`）。

> **覆盖范围**：build script 替换作用于所有收录的源文件内容，因此 `GradleDependencyDiffer.copyAllChangedFilesToDir` 中 diff 模式的直接构造也自动被覆盖，无需单独处理。

### 3.3 实验开关

实验开关定义在 `buildReadProjectInfoScript.gradle` 内：

```groovy
// Experiment: set to false to verify whether @JvmStatic alone fixes inner-class construction.
// Expected result: false -> Gradle 7 manifest task test fails, confirming factory functions are needed.
// Remove after experiment is concluded.
boolean useFactoryFunctions = true
```

- `true`（默认）：执行替换并注入工厂函数定义
- `false`：跳过替换和注入，生成脚本保留原始 `ClassName(` 调用

实验结束后删除此开关及相关条件分支，替换和注入逻辑保持无条件执行。

### 3.4 `buildReadProjectInfoScript.gradle` 瘦身

删除以下全部内容：
- `transformSourceForInitScript` 函数（旧版 companion 提取——含复杂成员名重写、prelude/postlude 逻辑）
- `applyRewriteRules` 函数
- `mergeRewriteRules`、`flatMemberName`、`registerRewriteMember`、`countChar`、`dropCompanionIndent` 辅助闭包
- `forceQualifiedCompanionMembers`、`companionKeepOwners`、`preludeCompanionOwners` 配置集合
- `rewriteRules` 相关变量和调用
- `preludeContents` 相关逻辑

**保留并新增**：
- 文件收集（`willCollectFiles`）
- `sortWeight` 排序（去掉 prelude 权重，简化）
- import 去重与拼接
- trailing-comma 正则清理
- 文件写出
- **新增：简化版 companion 提取**（括号深度追踪 + `@JvmStatic` 过滤 + 已知冲突成员重命名）
- **新增：限定调用重写**（`ClassName.memberName(` → `memberName(`）
- **新增：构造替换 + 工厂函数注入**（问题 B，见 3.2）

生成逻辑：逐文件去掉 `package`/`import`/`@file:Suppress`，提取 companion 内容到顶层，原样拼接 class body，最后做调用重写和构造替换。

---

## 4. 测试策略

现有测试已完整覆盖，无需新增：

| 测试 | 验证点 |
|---|---|
| `ReadProjectInfoGradle5CompatTest` | Gradle 5 基础运行 |
| `ReadProjectInfoGradle7CompatTest.generatedScript_shouldRunOnGradle733AndCollectFileDependencies` | Gradle 7 配置阶段 |
| `ReadProjectInfoGradle7CompatTest.generatedScript_shouldRunOnAndroidAppWithInjectApplicationEnabled` | Gradle 7 + inject + Reflector |
| `ReadProjectInfoGradle7CompatTest.generatedScript_shouldRunManifestTaskOnAndroidAppWithInjectApplicationEnabled` | **Gradle 7 + manifest task**（当前失败，重构后必须通过） |
| `ReadProjectInfoGradle9CompatTest` | Gradle 9 运行 + 依赖收集 |
| `ReadProjectInfoScriptContentTest` | 生成脚本内容结构验证 |

实验阶段额外验证：将 `useFactoryFunctions = false` + `@JvmStatic` 保留，跑 Gradle 7 测试，观察是否通过。

---

## 5. 实现顺序

1. ~~在 companion 成员上加 `@JvmStatic`~~（已完成，源文件保留，生成时过滤）
2. 在 `buildReadProjectInfoScript.gradle` 中添加 `useFactoryFunctions` 开关
3. 在 build script 中实现**简化版 companion 提取**（括号深度追踪）
4. 在 build script 中实现**限定调用重写**（`ClassName.memberName(` 替换）
5. 在 build script 中实现**构造替换**（`ClassName(` → `className(`，问题 B）
6. 在 build script 中实现**工厂函数注入**（追加定义到脚本末尾）
7. 删除旧 `transformSourceForInitScript` 等逻辑
8. 运行全部测试，确认 Gradle 5/7/9 全部通过
9. 实验：`useFactoryFunctions = false`，跑 Gradle 7 测试，记录结论
10. 删除实验开关，替换和注入逻辑无条件保留

---

## 6. 不在本次范围内

- `Reflector` 的 `newInstanceRaw`/`newInstanceRawP` 命名（历史原因，不影响正确性）
- `LibraryDependency` secondary constructor（已在 ec01929b 中修复，不回退）
- `JuggProjectInfoSerialize` secondary constructor（同上）
- Gradle 5 测试目前标记为 SKIPPED，不在本次修复范围
