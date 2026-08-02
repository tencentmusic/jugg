# DataBindingCompileTest 修复记录 — 2026-04-20

> Scope: `idea/src/test/java/com/sickworm/intellij/jugg/compile/databinding/DataBindingCompileTest`
> Target: 4 个失败用例

---

## 失败用例列表（修复前）

| 用例 | 错误信息 |
|------|---------|
| `testMultipleNewXmlDataBinding` | `File com/example/myapplication/DataBindingInfo.kt does not exist in output, all outputs: .../DataBindingInfo.java` |
| `reproduceReportCaseH_libraryModuleDataBindingLayoutCompileShouldSuccess` | `File com/example/library1/DataBindingInfo.kt does not exist in output, all outputs: .../DataBindingInfo.java` |
| `reproduceReportCaseA_javaRenameFieldOnlyCompileSuccessButBindingImplStale` | `expected source-only incremental compile success` |
| `reproduceReportCaseC_kotlinRenameFieldOnlyCompileSuccessButBindingImplStale` | `expected source-only incremental compile success` |

---

## 问题一：DataBindingInfo.kt vs DataBindingInfo.java（影响 testMultipleNewXmlDataBinding、CaseH）✅ 已修复

### 根因

`DataBindingArgsManager.kt` 存在 typo（commit `912f0132a` 引入），`isKaAptRetryAptSuccess` 被 `isLastFallbackAptFailed` 替代：

```kotlin
// 错误
val isFallbackApt = isLastFallbackAptFailed || isLastFallbackAptFailed

// 正确
val isFallbackApt = isKaAptRetryAptSuccess || isLastFallbackAptFailed
```

`isLastFallbackAptFailed` 是 companion object 的静态变量，跨测试状态污染。

### 修复

**文件 1：** `main/.../compiler/databinding/DataBindingArgsManager.kt` — 修复 isFallbackApt typo

**文件 2：** `main/.../compiler/databinding/DataBindingGenBaseClassesCompiler.kt` — init 块新增 `isLastFallbackAptFailed = false` 重置

### 结果

✅ `testMultipleNewXmlDataBinding` — 通过
✅ `reproduceReportCaseH_libraryModuleDataBindingLayoutCompileShouldSuccess` — 通过

---

## 问题二：source-only 增量编译失败（CaseA、CaseC）✅ 已修复（2026-04-20 第二轮）

### 用例描述

CaseA/C 场景：仅重命名 Java/Kotlin 源文件的字段（不修改 XML），期望 DataBinding 不需要重跑，直接增量编译源文件成功。

测试流程（以 CaseA 为例）：
1. DataBindingGenBaseClassesCompiler + DataBindingGenMapperCompiler 完整编译
2. 用 `withPatchedFiles` 临时将 Java 源文件字段从 `name` 改为 `displayName`
3. 调用 `JuggCompiler.compile(sourceTask)`，只传入修改后的 Java 文件
4. 期望 `result.isAllSuccess = true`，且 `ActivityDataBindingJavaDemoBindingImpl.java` 仍引用旧字段

### 历史背景

- CaseA/C 由 commit `84b429f0b`（2026-02-20）首次引入，测试 projectRootDir 指向 `src/test/assets/android/MyApplicationIntellij`
- commit `75fd77fae`（2026-03-03）将 projectRootDir 改为 `../android_demo_project`
- **CaseA/C 在两个项目下均未通过过**（AGP 版本均为 7.2.2，构建产物结构相同）

---

## 本次排查：CaseA/C 真正根因（✅ 已确认并修复）

**根因与问题一相同**：`isFallbackApt = isLastFallbackAptFailed || isLastFallbackAptFailed` typo 导致跨测试状态污染。
- CaseA/C 依赖 DataBindingGenBaseClassesCompiler + DataBindingGenMapperCompiler 正确生成 BindingImpl 到 `CompileHelper.javaOutputDir`
- 由于 typo 和 `isLastFallbackAptFailed` 静态变量污染，第二轮 DataBinding mapper 编译路径不正确，导致 `bindingImplFile` 没有生成
- 问题一修复（typo 修正 + `isLastFallbackAptFailed = false` 重置）后，CaseA/C 同步通过

**验证**：2026-04-20 会话二运行确认：
- `reproduceReportCaseA_javaRenameFieldOnlyCompileSuccessButBindingImplStale` → BUILD SUCCESSFUL
- `reproduceReportCaseC_kotlinRenameFieldOnlyCompileSuccessButBindingImplStale` → BUILD SUCCESSFUL
- 完整 `DataBindingCompileTest` → BUILD SUCCESSFUL (2m 15s)

### 已排除的假说

#### ❌ 假说一：nonsense_id R 类 classpath 顺序问题

**初始判断：** `javac/debug/classes/R$layout.class` 中字段全为 `nonsense_id_XXXXX`，排在 R.jar 前面，导致 `R.layout.activity_data_binding_java_demo` 找不到。

**已证伪：**
- 当前 `javac/debug/classes` 下**不存在任何 R*.class 文件**（`find ... -name "R*.class"` 无结果）
- 之前看到 nonsense_id 的文件是历史遗留产物，当前正常构建下 AGP 7.2.2 不再将 R 类输出到 `javac/debug/classes`
- 据此构造的 `javaCompileFailsWhenNonsenseIdRClassPrecedesRJar` 测试 `result.isAllSuccess=true`（编译成功），与预期相反

**遗留待清理的错误代码：**
- 新增文件：`android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/RLayoutReference.java` — **需删除**
- 新增 test case：`JavaCompileTest.javaCompileFailsWhenNonsenseIdRClassPrecedesRJar` 和 `javaCompileSucceedsWhenRJarPrecedesNonsenseIdRClass` — **需删除**

---

### 当前排查状态

#### 已确认的事实

1. **CaseA/C 错误信息**：`expected source-only incremental compile success`，在 `assertTrue(result.isAllSuccess, ...)` 处失败
2. **编译路径**：`JuggCompiler` → `SourceCompiler.doModuleCompile` → `prepareSourceCompile` → `compileLanguageStages` → `JavaCompiler/KotlinCompiler`
3. **DataBinding 跳过**：`SourceDataBindingProcessor.processDataBindingMapper` 检查 `task.files.any { it.file == trigger }`，CaseA/C 的 source-only task 无 trigger 文件，DataBinding mapper 直接跳过，不是失败点
4. **classpath 来源**：`JavaCompilerInvoker` 中 `Options.dependencies` 为空时走 `context.getModuleDependencies(module, task)`，`SimpleCompileContext.getModuleDependencies` 组合了：
   - `classpathDependencies`（`moduleInfo.buildPathInfo.allClassPath`，含 `kotlinClassPath`、`javaClassPathNew`、`rFilePath` 等）
   - `finalRFiles`（R.jar）
   - `task.files[0].dependencyPaths`

5. **`allClassPath` 定义**（`JuggProjectInfo.kt` 第 199 行）：
   ```kotlin
   val allClassPath get() = customClasspathFiles + listOf(
       kotlinClassPath, javaClassPathNew, javaClassPathOld, rFilePath,
       kotlinClassPathForJavaLibrary, javaClassPathForJavaLibrary, libraryRFilePathInLowAgp
   )
   ```
   注意：`javaClassPathNew`（`intermediates/javac/debug/classes`）在 `rFilePath`（R.jar）前面。

6. **`javaClassPathNew` 在前的设计意图**：Jugg 新增 resource id 时会写新 R.class 到 `javaClassPath`，需要优先于 R.jar 生效。

7. **近期相关 commit**：
   - `a6e7c883b`（2026-03-23）：Gradle 9.2.1 兼容，只改 `camelCompat`，不影响 CaseA/C
   - `605e95cf9`（2026-04-02）：新增 `rFilePathDirAgp9`，AGP 7.2.2 下该目录不存在，fallback 正常，不影响 CaseA/C

8. **`DataBindingJavaDemoActivity.java` 引用 `R.layout.activity_data_binding_java_demo`**，该字段在 R.jar 中存在，在 `javac/debug/classes` 中不存在（当前构建下 R 类不在此目录）

#### 尚未确认的点

**CaseA/C 实际运行时的真正错误信息**：目前只知道 `result.isAllSuccess = false`，但没有看到具体的编译错误内容。需要在下次会话中：
1. 在测试中打印 `result.failedFiles.flatMap { it.getFailure().errorMessages }` 的完整内容
2. 或在 `JuggCompiler.compile(sourceTask)` 后加日志，看到底是哪个文件、哪行、什么错误

**可能的根因方向**（待验证）：
- 是否是某个依赖类找不到（如 `ActivityDataBindingJavaDemoBinding`）
- 是否是某个 Kotlin 编译步骤失败
- 是否是 JuggApt 相关步骤引入了额外编译

---

## 修复后的测试状态

| 用例 | 状态 | 备注 |
|------|------|------|
| `testDataBinding` | ✅ PASS | |
| `reproduceReportCaseH_libraryModuleDataBindingLayoutCompileShouldSuccess` | ✅ FIXED | typo 修复 |
| `reproduceReportCaseI_libraryModuleKotlinSourceWithDataBindingShouldCompileSuccess` | ✅ PASS | |
| `testXmlIncludeNodeViewBinding` | ✅ PASS | |
| `testNewNodeViewBinding` | ✅ PASS | |
| `testMultipleNewXmlViewBinding` | ✅ PASS | |
| `testMultipleNewXmlDataBinding` | ✅ FIXED | typo 修复 |
| `reproduceReportCaseE_javaRenameClassWithoutXmlTypeShouldCompileFailed` | ✅ PASS | |
| `reproduceReportCaseF_kotlinRenameClassWithoutXmlTypeShouldCompileFailed` | ✅ PASS | |
| `reproduceReportCaseG_javaAndKotlinRenameClassWithXmlTypeStillCompileFailed` | ✅ PASS | |
| `reproduceReportCaseA_javaRenameFieldOnlyCompileSuccessButBindingImplStale` | ✅ FIXED | typo 修复后同步通过 |
| `reproduceReportCaseC_kotlinRenameFieldOnlyCompileSuccessButBindingImplStale` | ✅ FIXED | typo 修复后同步通过 |

---

## 代码变更摘要（全部已完成）

```
main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/
  DataBindingArgsManager.kt         — 修复 isFallbackApt typo
  DataBindingGenBaseClassesCompiler.kt — init 块重置 isLastFallbackAptFailed

idea/src/test/java/com/sickworm/intellij/jugg/compile/databinding/
  DataBindingCompileTest.kt          — CaseA/C 失败时打印详细错误信息（debug 辅助）

idea/src/test/java/com/sickworm/intellij/jugg/compile/
  JavaCompileTest.kt                 — 删除 javaCompileFailsWhenNonsenseIdRClassPrecedesRJar
                                       和 javaCompileSucceedsWhenRJarPrecedesNonsenseIdRClass

android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/
  RLayoutReference.java              — 已删除（基于错误假设创建）
```

## 最终测试状态（全部通过）

| 用例 | 状态 |
|------|------|
| `testDataBinding` | ✅ PASS |
| `reproduceReportCaseH_libraryModuleDataBindingLayoutCompileShouldSuccess` | ✅ PASS |
| `reproduceReportCaseI_libraryModuleKotlinSourceWithDataBindingShouldCompileSuccess` | ✅ PASS |
| `testXmlIncludeNodeViewBinding` | ✅ PASS |
| `testNewNodeViewBinding` | ✅ PASS |
| `testMultipleNewXmlViewBinding` | ✅ PASS |
| `testMultipleNewXmlDataBinding` | ✅ PASS |
| `reproduceReportCaseE_javaRenameClassWithoutXmlTypeShouldCompileFailed` | ✅ PASS |
| `reproduceReportCaseF_kotlinRenameClassWithoutXmlTypeShouldCompileFailed` | ✅ PASS |
| `reproduceReportCaseG_javaAndKotlinRenameClassWithXmlTypeStillCompileFailed` | ✅ PASS |
| `reproduceReportCaseA_javaRenameFieldOnlyCompileSuccessButBindingImplStale` | ✅ PASS |
| `reproduceReportCaseC_kotlinRenameFieldOnlyCompileSuccessButBindingImplStale` | ✅ PASS |
