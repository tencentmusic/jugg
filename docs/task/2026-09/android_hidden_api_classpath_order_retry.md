# ROM/系统应用 hidden API classpath 顺序失败重试

## 1. 背景

Jugg report `06f3d8fa`（插件 `3.3.8-release`，工程 NapaApps / 车机 ROM）在增量编译 `napa/NapaDLNA/src/com/mega/dlna/dmp/MainPage.kt` 时失败：

```text
unresolved supertypes: android.net.wifi.WifiManager.SoftApCallback
cannot access 'android.net.wifi.WifiManager.SoftApCallback' which is a supertype of MegaWifiManager.SoftApCallback
onStateChanged overrides nothing
```

触发代码形态为引入非公开 Android API，例如 `softApCallback?.let { callback ->`。`GitChangesRetryResolver` 只认 `unresolved reference` / `cannot find symbol`，现场日志为 `no symbol-not-found error, skip`。

根因不是 HideAPI 路径缺失，而是 **classpath 先到先得**：

- `BaseCompileContext.getModuleDependencies()` 把 SDK `android.jar` 放在 `-cp` 第 0 位。
- 同名更完整的 framework / HideAPI jar（现场为 `mega.nexus.framework.source.jar`）已在 `${variant}CompileClasspath` 中，但排在后面（现场约第 101 位）。
- 公开 SDK 的 `WifiManager` 被裁掉 `@SystemApi` / `@hide` 的 `SoftApCallback`；Kotlin/Java 都采用先命中的不完整类，后置完整类无法覆盖。

本方案只覆盖该失败模式的增量编译恢复，不改变默认 classpath 顺序，不扫描 jar，不改 D8。

## 2. 已确认事实

1. HideAPI / generated-sdk / `framework.jar` 出现在 ROM、车机系统应用的 `compileOnly` classpath 上是正常工程形态；对 Jugg 主流公开 SDK 应用很少见。
2. Jugg 已经从 Gradle `${variant}CompileClasspath` 读到了该 jar，问题只在相对 SDK `android.jar` 的顺序。
3. KGP/AGP 默认同样是 `bootClasspath`（`android.jar`）在前、`compileClasspath` 在后。后置 `android.jar` **不是** Gradle 的稳定 runtime 契约；AGP 也不支持用工程 jar 覆盖 `android.jar`（issuetracker 167750503）。
4. Report 里 `:Napa:NapaDLNA:assembleDebug` 成功发生在 `MainPage.kt` 引入 hidden API **之前**，且当时 `changed files: []`，不能用来证明 Gradle 编过这段新代码。
5. 改 javac `bootstrapClasspath` / `-Xbootclasspath/p` 不影响 Kotlin。Jugg 的 Kotlin/Java 增量编译都走 `-cp`，与 D8 使用的 `context.androidJar` 分离。
6. JDK 25 且 classpath 含 `android.jar` 时，`KotlinCompilerHostCompat` 会加 `-no-jdk`。后置 SDK `android.jar` 不改变“classpath 里是否存在 android.jar”，`-no-jdk` 路径保持不变。
7. 每次编译扫描全部 jar 做 overlap、按文件名启发式前置、或从 Gradle 读取“官方 overlay 顺序”，都没有稳定契约，且会让全量用户付代价。

## 3. 方案比较

| 方案 | 结论 |
|---|---|
| 每次编译把疑似 framework/HideAPI jar 提到 `android.jar` 前 | 否决。要认 jar 名或 zip 扫描；误伤面大，ROM 场景却很小。 |
| 永久修改 `getModuleDependencies()`，默认把 `android.jar` 放到末尾 | 否决。改变所有用户的默认解析顺序，且不是 Gradle 正常顺序。 |
| 从 Gradle 读取 bootclasspath overlay / 自定义 android.jar | 否决。没有可依赖的官方顺序 API。 |
| 失败后一次性把 SDK `android.jar` 挪到 `-cp` 末尾再编 | **采用。** 不扫描、不认 jar 名；只在已确认的诊断上付一次重试成本。 |

后置 SDK `android.jar` 等价于廉价的“前置 overlay”：后面的同名类生效，前面的公开 stub 让位。不改 `getModuleDependencies()` 的默认顺序，因此未命中诊断的原有路径不变。

## 4. 推荐方案

### 4.1 行为

Kotlin / Java 源码编译失败后，若诊断命中 **Android framework 超类型被公开 SDK stub 挡住**，且当前 `-cp` 里的 SDK `android.jar` 不是最后一项：

1. 把该 `android.jar` 从原位置移除，追加到 classpath 末尾，其余项相对顺序不变。
2. 用同一批源码、同一编译器实例再编一次。
3. 重试成功：返回成功结果。
4. 重试仍失败：向用户展示 **第一次** 的错误，不展示第二次可能换掉的诊断。
5. 整次 invocation 只允许这一次自动重试，与 `KotlinCompilerInvoker` / `JavaCompilerInvoker` 现有 `isCanAutoRetry` 单次预算共享。已因 metadata、plugin option、IDE filesystem 或 recreate compiler 消耗过预算时，不再后置 `android.jar`。

### 4.2 触发条件（必须收窄）

只匹配公开 Android framework 包名 `android.`，**排除** `androidx.`。

Kotlin 同时满足任一：

- 诊断包含 `unresolved supertypes: android.`
- 诊断同时包含 `cannot access 'android.` 与 `which is a supertype`

Java 同时包含：

- `class file for android.`
- `cannot access` 或 `not found`

以下 **不得** 触发后置：

- `unresolved reference`（含 `android.view.View` 这类真缺类）
- `cannot find symbol`（交给 `GitChangesRetryResolver`）
- `unresolved supertypes: androidx.` / `cannot access 'androidx.`
- classpath 中找不到文件名为 `android.jar` 的项，或该项已经在末尾

诊断按现有编译器输出匹配英文文本即可。Jugg 已约束 javac locale；不要为假设的本地化文案加分支。

### 4.3 落点

| 职责 | 位置 | 说明 |
|---|---|---|
| 诊断匹配与 classpath 重排 | 新建 `main/.../compiler/source/AndroidJarClasspathRetry.kt` | 纯函数，Kotlin/Java 共用；不依赖 IDEA。 |
| Kotlin 重试 | `KotlinCompilerInvoker` | 在 metadata / unsupported plugin option 之后、recreate compiler 之前判断。命中后 `options.copy(isCanAutoRetry = false)` 并带上后置后的 classpath 再 `compile()`。 |
| Java 重试 | `JavaCompilerInvoker` | 同样在 recreate compiler 之前。可用已有 `Options.dependencies` 传入后置列表，避免把 `getAndroidJarPath()` 改成 public。 |
| 默认依赖顺序 | `BaseCompileContext.getModuleDependencies()` | **不改。** SDK `android.jar` 仍为第 0 项。 |
| 增量失败补文件/补依赖 | `IncrementalCompilerHelper` resolver chain | **不进。** 本问题不是漏文件或漏依赖路径。 |
| D8 | `context.androidJar` / `DexCompiler` | **不改。** |

识别要移动的 jar：在当前 invocation 实际使用的 classpath 列表中，取 **第一个文件名为 `android.jar` 的项**。这与 `getModuleDependencies()` 把 SDK `android.jar` 放在首位一致，无需开放 private `getAndroidJarPath()`。

Kotlin 当前在 invoker 内部调用 `getModuleDependencies()`。为避免实例字段残留，在 `KotlinCompilerInvoker.Options` 增加可选 `classpathOverride: List<String> = emptyList()`（或等价的后置后列表）；重试时传入后置结果，默认空列表表示仍走 `getModuleDependencies()`。不要为测试增加 `provider` / lambda seam。

重试失败展示第一次错误时：先构造本轮失败 `CompileResult`，再调用重试；成功返回重试结果，失败丢弃重试结果并返回第一次 `CompileResult`。`hasRetryCompile` / Java 的等价一次性标记仍要置位，防止失败后再 recreate。

### 4.4 日志

用户可见，用 `info`：

```kotlin
logger.info("Kotlin compile failed because SDK android.jar shadows a later android type, " +
        "retry once with android.jar last.")
```

Java 使用对称文案。重试失败保持第一次错误时打 `debug`，说明第二次仍失败、向用户保留首次诊断。续行相对调用缩进 8 个空格。

## 5. 非目标

- 不扫描 classpath jar 的 overlap。
- 不按 `framework` / `hide` / `nexus` 等文件名启发式前置。
- 不永久后置或删除 SDK `android.jar`。
- 不把该逻辑放进 `GitChangesRetryResolver` / `IncrementalCompileRetryResolver`。
- 不改 D8、resource、deploy。
- 不为公开 SDK 应用的普通 `android.*` 缺类提供“成功”假象：真缺类几乎必然第二次仍失败，且用户看到的仍是第一次错误。
- 不保证半截 stub（后置 jar 仍缺成员）能编过；若第二次换成新错误，按 4.1 仍展示第一次错误。
- 不处理 `-no-jdk` 与假 `java.lang.*` 同名类；该组合在 ROM overlay 场景概率极低，不做预防性设计。

## 6. 测试与验证

测试价值：诊断字符串和“把第一个 `android.jar` 移到末尾”是独立、稳定、可被真实破坏的契约；通过门禁，落 L1，behavior owner 为新建 helper。Invoker 接线是薄调用，不为其增加仅服务 mock 的 seam。现有 `BaseCompileContextModuleDependenciesTest` 覆盖的是 included-build R.jar 顺序，不是 overlay，不要塞进该类。

| 层级 | Owner | 场景 | 预期 |
|---|---|---|---|
| L1 | `AndroidJarClasspathRetryTest` | Kotlin `unresolved supertypes: android.net.wifi...` | 命中 |
| L1 | 同上 | Kotlin `cannot access 'android.` + `which is a supertype` | 命中 |
| L1 | 同上 | `unresolved supertypes: androidx.` | 不命中 |
| L1 | 同上 | `unresolved reference: android.view.View` | 不命中 |
| L1 | 同上 | `cannot find symbol` | 不命中 |
| L1 | 同上 | Java `cannot access` + `class file for android.net.wifi.WifiManager$SoftApCallback not found` | 命中 |
| L1 | 同上 | 第一个 `android.jar` 移到末尾，其余相对顺序不变 | 列表等于去掉该项再 append |
| L1 | 同上 | 已在末尾或列表中无 `android.jar` | 返回原列表，调用方应跳过重试 |
| L2 | `SourceCompileTest.kotlinAndJavaCompile` | 普通增量编译（无 overlay 诊断） | 仍成功；默认 classpath 不被本功能改写 |
| 失败证据 | report `06f3d8fa` 日志 | 首次 Kotlin 诊断与 `android.jar` 位于 `-cp` 首位 | 已存在，实现前不要求再跑 ROM 工程 |

不新增 L3。不在 `android_demo_project` 里提交 ROM HideAPI fixture。

实现顺序：先写 `AndroidJarClasspathRetryTest` 失败用例，再写 helper，再改两个 invoker，最后跑定向测试。

验证命令：

```bash
./gradlew :main:test \
  --tests "com.sickworm.intellij.jugg.compiler.source.AndroidJarClasspathRetryTest" \
  --tests "com.sickworm.intellij.jugg.compiler.SourceCompileTest.kotlinAndJavaCompile" \
  :idea:compileKotlin
```

## 7. 文档同步（实现时）

本方案落地后同步，不在写方案时提前改知识库：

- `docs/ai_knowledge/02_compile_source.md`：在 Kotlin/Java 失败降级处记录“命中 android framework 超类型诊断时，一次性后置 SDK `android.jar`；失败保留首次错误；不改默认 `getModuleDependencies()`”。
- `docs/ai_knowledge/02_compile_core.md` §7.2：明确该重试在 invoker 内，**不是** `IIncrementalCompileRetryResolver` chain。
- `docs/ai_knowledge/09_plugin_runtime_debug.md` 排查表：ROM hidden API / `unresolved supertypes: android.` → `AndroidJarClasspathRetry` 与 invoker `-cp` 顺序。
- 不改 `docs/wiki`：对普通应用开发者不可见，且不是产品配置项。

## 8. 实施步骤

### 8.1 Helper 与测试

- 创建 `main/src/test/java/com/sickworm/intellij/jugg/compiler/source/AndroidJarClasspathRetryTest.kt`
- 创建 `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/AndroidJarClasspathRetry.kt`

建议 API（名称可微调，语义不可变）：

```kotlin
object AndroidJarClasspathRetry {
    fun shouldPostponeSdkAndroidJar(errorMessages: Iterable<String>, classpath: List<String>): Boolean
    fun postponeSdkAndroidJar(classpath: List<String>): List<String>
}
```

`shouldPostponeSdkAndroidJar` 内部先看 `postponeSdkAndroidJar(classpath) != classpath`，再看诊断。不要在 invoker 里复制字符串匹配。

### 8.2 KotlinCompilerInvoker

1. `Options` 增加默认空的 classpath 覆盖字段。
2. 组装 `-cp` 时：覆盖非空用覆盖，否则仍 `getModuleDependencies()`；JDK 25 `-no-jdk` 继续看最终 dependencies。
3. 在现有 plugin-option 重试之后、recreate compiler 之前：若 `options.isCanAutoRetry && !hasRetryCompile && shouldPostpone...`，置 `hasRetryCompile = true`，打 info 日志，用后置列表递归 `compile(..., options.copy(isCanAutoRetry = false, classpathOverride = postponed))`。
4. 递归成功则返回；失败返回递归前已构造的第一次 `CompileResult`。
5. 不要把后置逻辑放到 `handleMetadataError` 或 plugin 禁用分支里。

### 8.3 JavaCompilerInvoker

1. 失败分支在 recreate 之前做同样判断。
2. 重试：`options.copy(isCanAutoRetry = false, dependencies = postponeSdkAndroidJar(dependencies))`。
3. 成功返回重试结果，失败返回第一次结果，且不再 recreate。

`JavaCompiler` 继续不传 `dependencies`；首次编译仍走 `getModuleDependencies()`。

### 8.4 收尾

- 对照 diff 检查本次新增日志续行缩进。
- 跑第 6 节命令。
- 按第 7 节更新 `ai_knowledge`。
- 提交与本功能相关的代码和知识库，不夹带 `android_demo_project` 或其他未提交改动。

建议提交前缀 `[bugfix]`，标题描述 ROM/系统应用增量编译 hidden API 时被 SDK `android.jar` 挡住的可观察失败。

## 9. 完成标准

- [ ] 命中 4.2 诊断时，Kotlin 与 Java 都只重试一次，且 SDK `android.jar` 位于重试 `-cp` 末尾。
- [ ] 未命中诊断、`androidx`、普通 `unresolved reference` 时不后置，默认 `getModuleDependencies()` 顺序不变。
- [ ] 重试失败时用户看到第一次错误；D8 仍使用原来的 `androidJar`。
- [ ] 不进入 incremental resolver chain，不扫描 jar。
- [ ] L1 测试与 `SourceCompileTest.kotlinAndJavaCompile` 通过；知识库已同步。

## 10. 残余风险

- 后置 classpath 上没有更完整的同名 `android.*` 类时，第二次必然失败；用户仍看到第一次超类型错误，这是可接受的。
- 后置 jar 若是半截 stub，第二次可能变成新的缺成员错误，按产品选择不展示第二次。
- 该能力主要服务 ROM/系统应用，公开 SDK 应用几乎不会命中；误命中时多一次编译，随后仍失败并保留原错误。
