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
- 公开 SDK 的 `WifiManager` 被裁掉 `@SystemApi` / `@hide` 的 `SoftApCallback`；Kotlin 解析嵌套类型时先解析外层 `WifiManager`，命中前面的公开 stub 后就找不到该成员类型，后置的完整类无法覆盖。

本方案只覆盖该失败模式的 **Kotlin** 增量编译恢复，不改变默认 classpath 顺序，不扫描 jar，不改 D8。

## 2. 已确认事实

### 2.1 现场事实

1. HideAPI / generated-sdk / `framework.jar` 出现在 ROM、车机系统应用的 `compileOnly` classpath 上是正常工程形态；对 Jugg 主流公开 SDK 应用很少见。
2. Jugg 已经从 Gradle `${variant}CompileClasspath` 读到了该 jar（`GradleProjectInfoReader` 按 `${variant}CompileClasspath` 取依赖），问题只在相对 SDK `android.jar` 的顺序。
3. KGP/AGP 默认同样是 `bootClasspath`（`android.jar`）在前、`compileClasspath` 在后。后置 `android.jar` **不是** Gradle 的稳定 runtime 契约；AGP 也不支持用工程 jar 覆盖 `android.jar`（issuetracker 167750503）。
4. **报告人已确认**：工程没有改 `JavaCompile.bootstrapClasspath`，也没有把 HideAPI jar 前置到 `android.jar` 之前。该 jar 就是以普通依赖参与编译；Gradle 侧不报错，是因为只经由二进制依赖间接引用到的 API 不需要被完整解析、缺失也不检查。
5. **报告人已确认根因与修复方向**：Jugg `-cp` 里 `android.jar` 在第 0 位、`mega.nexus.framework.source.jar` 在第 101 位，**两者都包含 `WifiManager`**，公开 `android.jar` 覆盖了 HideAPI 版本的 `SoftApCallback`；按此调整顺序后问题已修复。该 jar 不是只含 `.java` 的 sources jar。
6. Report 里 `:Napa:NapaDLNA:assembleDebug` 成功发生在 `MainPage.kt` 引入 hidden API **之前**，且当时 `changed files: []`，不能用来证明 Gradle 编过这段新代码。
7. 改 javac `bootstrapClasspath` / `-Xbootclasspath/p` 不影响 Kotlin。Jugg 的 Kotlin/Java 增量编译都走 `-cp`，与 D8 使用的 `context.androidJar` 分离。
8. JDK 25 且 classpath 含 `android.jar` 时，`KotlinCompilerHostCompat.shouldUseNoJdk` 会加 `-no-jdk`。该判定是 `dependencies.any { File(it).name == "android.jar" }`，与位置无关，后置不改变 `-no-jdk` 路径。
9. `JuggCompiler` 只在 compile context 更新（Gradle sync）时由 `JuggManager.reInitOnCompileContextUpdate()` 重建，`KotlinCompilerInvoker` 实例跨整个编辑-运行循环存活，实例字段可以承载跨轮次记忆。
10. javac 诊断文本随 JVM locale 变化。`JavaCompilerInvoker` 已把 `diagnostic.code` 拼进 `item.errors`，`JavaDiagnosticLocaleTest` 已证明 `toString()` 与 `getMessage(Locale.ENGLISH)` 都不可靠。

### 2.2 本地实验事实（2026-09-05，JDK 17 / Kotlin 2.2.10、2.3.0）

复现配方：`android.jar` 替身只含裁剪版 `p.Outer`（无嵌套 `Inner`）；`framework.jar` 含完整 `p.Outer` + `p.Outer$Inner` + `q.Sub extends p.Outer.Inner`；源码继承 `q.Sub` 并覆写 `onStateChanged()`。

11. **换序确实能修，已实测。** `-cp framework.jar:android.jar` 顺序下 Kotlin 编译成功并产出 class；`-cp android.jar:framework.jar` 下失败。方案的核心前提不再是推断。
12. **Kotlin 2.2+ 的诊断里没有包名。** 实测 2.2.10 与 2.3.0 输出均为：

    ```text
    error: cannot access 'Inner' which is a supertype of 'UseK'. Check your module classpath for missing or conflicting dependencies.
    error: 'onStateChanged' overrides nothing.
    ```

    只渲染短名 `'Inner'`，**没有任何 `android.` 全限定名**，也没有 `unresolved supertypes:`。现场 report 里带 FQN 的形态属于更老的 Kotlin 渲染。因此"必须匹配到非 `androidx.` 的 `android.` FQN"这个收窄条件在新版 Kotlin 上永远拿不到，会让新工程完全不命中。
13. **javac 复现不出现场形态。** 同一组 jar 下：
    - 源码继承来自 framework jar 的 `q.Sub`（supertype 是二进制引用）→ **javac 编译成功**。javac 按 binary name 直查 `p/Outer$Inner.class`，不经过 `p.Outer` 的成员查找，stub 在前也挡不住。这同时精确解释了事实 4：Gradle 的 javac 路径本来就不会因此失败。
    - 只有源码里**直接书写** `p.Outer.Inner` 时 javac 才失败，且 code 是 `compiler.err.cant.resolve.location`——正是本方案要排除、交给 `GitChangesRetryResolver` 的那一个，与真缺类的 `cannot find symbol` **同 code，无法区分**。
    - 完全缺类时是 `compiler.err.doesnt.exist` + `compiler.err.method.does.not.override.superclass`。
    - 结论：`compiler.err.cant.access` 在这个场景根本不出现，**Java 分支不实现**。
14. 该失败是 Kotlin 解析**嵌套类型**特有的：Kotlin 按 classifier 路径先解析外层类再找成员类型，命中前置 stub 后即失败。顶层类被裁剪成员的场景报的是 `unresolved reference`，不属于本方案。

## 3. 方案比较

| 方案 | 结论 |
|---|---|
| 每次编译把疑似 framework/HideAPI jar 提到 `android.jar` 前 | 否决。要认 jar 名或 zip 扫描；误伤面大，ROM 场景却很小。 |
| 永久修改 `getModuleDependencies()`，默认把 `android.jar` 放到末尾 | 否决。改变所有用户的默认解析顺序，且不是 Gradle 正常顺序。 |
| 从 Gradle 读取 bootclasspath overlay / 自定义 android.jar | 否决。没有可依赖的官方顺序 API。 |
| 命中后用 invoker 级全局布尔记住后置 | 否决。invoker 跨整个编辑-运行循环存活（事实 9），一个布尔会让此后所有模块所有文件都用非默认顺序；framework jar 是半截 stub 时反而会把本来能编过的文件带崩。 |
| 同时实现 Java 分支 | 否决。事实 13 实测 javac 不复现该形态，唯一会失败的形态与真缺类同 code，硬接会和 `GitChangesRetryResolver` 抢地盘。 |
| 失败后一次性把 SDK `android.jar` 挪到 `-cp` 末尾再编，并按文件维度记住成功降级 | **采用。** 不扫描、不认 jar 名；只在已确认的诊断上付一次重试成本，且非默认顺序被限制在确有问题的文件上。 |

后置 SDK `android.jar` 等价于廉价的"前置 overlay"：后面的同名类生效，前面的公开 stub 让位。不改 `getModuleDependencies()` 的默认顺序，因此未命中诊断的原有路径不变。

## 4. 推荐方案

### 4.1 行为

**首次命中。** Kotlin 源码编译失败后，若某个源文件的诊断命中 **supertype 无法访问**（4.2），且当前 `-cp` 里的 SDK `android.jar` 不是最后一项：

1. 把该 `android.jar` 从原位置移除，追加到 classpath 末尾，其余项相对顺序不变。
2. 用同一批源码、同一编译器实例再编一次。
3. 重试成功：返回成功结果，并把**首次编译中命中诊断、且 `hasDirectSourceDiagnostic` 为真的源文件**记入 invoker 的后置文件集合。
4. 重试仍失败：向用户展示 **第一次** 的错误，不展示第二次可能换掉的诊断，也不记录任何文件。
5. 整次 invocation 只允许这一次自动重试，与现有 `isCanAutoRetry` / `hasRetryCompile` 单次预算共享。已因 metadata、plugin option、IDE filesystem 或 recreate compiler 消耗过预算时，不再后置 `android.jar`。

**后续记忆（按文件维度）。** 后续编译在组装 `-cp` 前先判断：本批 `task.files` 中只要有任何一个文件在后置文件集合里，就直接用后置顺序编译，不再先失败一次。这样 ROM 用户改同一个 hidden API 文件的常规循环不会每轮白付一次完整失败编译，`isCanAutoRetry` 预算也留给 metadata / recreate 等其他降级。

记忆只收录 `hasDirectSourceDiagnostic` 为真的文件：`KotlinCompilerOutputParser.parseErrorMessage()` 在诊断不带 `file:line:col` 时会把同一条消息广播给整批文件，不收窄会让无关文件被记进去。supertype 诊断本身带位置信息，会进 `directErrorFiles`，收窄不影响真实命中。

另外两处边界：

- 一次 kotlinc invocation 只有一份 `-cp`，所以命中文件的同批文件会跟着用后置顺序。这不引入新风险：这批组合上一轮就是这样编过并成功的。
- 不做淘汰、不做持久化。文件不再使用 hidden API 后记忆仍在，但后置顺序对它无害；集合随 `JuggCompiler` 在下次 sync 重建而清空。

### 4.2 触发条件

命中 supertype 诊断族即可，**不要求包名信号**（大小写不敏感，任一）：

- 消息含 `which is a supertype of`（Kotlin 2.2 / 2.3 实测渲染）
- 消息含 `unresolved supertypes:`（现场 report 的旧渲染）

**不要求匹配 `android.` 全限定名。** 事实 12 实测证明 Kotlin 2.2+ 只渲染短名，包名信号根本拿不到；坚持收窄等于让新版 Kotlin 工程永不命中。收窄改由另外三个条件承担：

1. classpath 中存在文件名为 `android.jar` 且**不在末尾**的项，否则直接跳过重试。
2. 每次 invocation 单次预算，与既有 retry 共享。
3. 重试失败时保留第一次错误，不放大成功假象。

放宽包名不会显著抬高触发率：supertype-access 诊断表达的是 classpath 冲突或缺失，在正常增量编译里本就罕见，不是普通笔误会产生的错误。误命中的全部代价是一次多余编译，随后仍展示第一次错误。

以下 **不得** 触发后置：

- `unresolved reference`（顶层类缺成员、真缺类都走这条）
- `cannot find symbol`（交给 `GitChangesRetryResolver`）
- 任何不属于上述 supertype 诊断族的消息
- classpath 中找不到 `android.jar`，或该项已经在末尾

Java 侧不实现（事实 13）。若将来出现真实的 Java 报告，重新取证后再评估，届时要先解决"与 `compiler.err.cant.resolve.location` 无法区分"这个问题。

### 4.3 落点

| 职责 | 位置 | 说明 |
|---|---|---|
| 诊断匹配与 classpath 重排 | 新建 `main/.../compiler/source/kotlin/AndroidJarClasspathRetry.kt` | 纯函数 object，与同目录 `KotlinCompilerHostCompat` 同类；不依赖 IDEA。 |
| 重试与记忆 | `KotlinCompilerInvoker` | 在 metadata / unsupported plugin option 之后、recreate compiler 之前判断；后置文件集合为该 invoker 的实例字段。 |
| 默认依赖顺序 | `BaseCompileContext.getModuleDependencies()` | **不改。** SDK `android.jar` 仍为第 0 项。 |
| Java 编译 | `JavaCompilerInvoker` | **不改。** |
| 增量失败补文件/补依赖 | `IncrementalCompilerHelper` resolver chain | **不进。** 本问题不是漏文件或漏依赖路径。 |
| D8 | `context.androidJar` / `DexCompiler` | **不改。** |

识别要移动的 jar：在当前 invocation 实际使用的 classpath 列表中，取 **第一个文件名为 `android.jar` 的项**。这与 `getModuleDependencies()` 把 SDK `android.jar` 放在首位一致，无需开放 private `getAndroidJarPath()`。

**传参用布尔开关，不要传 classpath 快照。** `KotlinCompilerInvoker.Options` 增加 `isPostponeSdkAndroidJar: Boolean = false`，在现有 `dependencies` 组装（含 `isolatedBaseline` 重映射）之后再做后置。原因：Kotlin 的 `-cp` 不是纯快照，invoker 会把 `kotlinClassPath` 重映射成每轮新建、编完即 `deleteRecursively()` 的 `isolatedBaseline` 目录；把上一轮的成品列表冻结传回去，等于把一个被删掉又重建的路径写死进去。布尔开关能保持 `getModuleDependencies()` 是唯一来源，代码更少，也让按文件记忆天然复用同一条组装路径。

重试失败展示第一次错误时：先构造本轮失败 `CompileResult`，再调用重试；成功返回重试结果，失败丢弃重试结果并 **立即 return** 第一次 `CompileResult`，不得继续往下走 `merger.save()` 与产物收集。`hasRetryCompile` 仍要置位，防止失败后再 recreate。

### 4.4 日志

用户可见，用 `warn`：与相邻的 metadata / plugin option / recreate 重试保持一致，这些都是非预期失败后的降级，不是关键流程播报。

```kotlin
logger.warn("Kotlin compile failed because SDK android.jar shadows a later classpath type, " +
        "retry once with android.jar last.")
```

重试失败保持第一次错误时打 `debug`，说明第二次仍失败、向用户保留首次诊断。命中按文件记忆、首次即用后置顺序编译时也打 `debug`，让 `compile_latest.log` 能解释"这轮 `-cp` 顺序为什么和默认不同"。续行相对调用缩进 8 个空格。

## 5. 非目标

- 不实现 Java 分支。
- 不扫描 classpath jar 的 overlap。
- 不按 `framework` / `hide` / `nexus` 等文件名启发式前置。
- 不永久后置或删除 SDK `android.jar`，不改 `getModuleDependencies()` 默认顺序。
- 不把该逻辑放进 `GitChangesRetryResolver` / `IncrementalCompileRetryResolver`。
- 不改 D8、resource、deploy。
- 后置文件集合不做淘汰、不做持久化、不跨 sync 保留。
- 不保证半截 stub（后置 jar 仍缺成员）能编过；若第二次换成新错误，按 4.1 仍展示第一次错误。
- 不处理 `-no-jdk` 与假 `java.lang.*` 同名类；该组合在 ROM overlay 场景概率极低，不做预防性设计。

## 6. 测试与验证

测试价值：诊断判定与"把第一个 `android.jar` 移到末尾"是独立、稳定、可被真实破坏的契约；通过门禁，落 L1，behavior owner 为新建 helper。Invoker 接线与按文件记忆是薄状态，不为其增加仅服务 mock 的 seam。现有 `BaseCompileContextModuleDependenciesTest` 覆盖的是 included-build R.jar 顺序，不是 overlay，不要塞进该类。

| 层级 | Owner | 场景 | 预期 |
|---|---|---|---|
| L1 | `AndroidJarClasspathRetryTest` | `cannot access 'Inner' which is a supertype of 'UseK'. Check your module classpath...`（Kotlin 2.2/2.3 实测文本） | 命中 |
| L1 | 同上 | `cannot access 'android.net.wifi.WifiManager.SoftApCallback' which is a supertype of ...`（report 旧渲染） | 命中 |
| L1 | 同上 | `unresolved supertypes: android.net.wifi.WifiManager.SoftApCallback`（report 旧渲染） | 命中 |
| L1 | 同上 | `unresolved reference: android.view.View` | 不命中 |
| L1 | 同上 | `cannot find symbol` | 不命中 |
| L1 | 同上 | 第一个 `android.jar` 移到末尾，其余相对顺序不变 | 列表等于去掉该项再 append |
| L1 | 同上 | 已在末尾或列表中无 `android.jar` | 返回原列表，调用方应跳过重试 |
| L2 | `SourceCompileTest.kotlinAndJavaCompile` | 普通增量编译（无 overlay 诊断） | 仍成功；默认 classpath 不被本功能改写 |
| 失败证据 | report `06f3d8fa` + §2.2 本地实验 | 首次诊断、`android.jar` 位于首位、换序后编译成功 | 已存在，实现前不要求再跑 ROM 工程 |

不新增 L3。不在 `android_demo_project` 里提交 ROM HideAPI fixture。

实现顺序：先写 `AndroidJarClasspathRetryTest` 失败用例，再写 helper，再改 invoker，最后跑定向测试。

验证命令：

```bash
./gradlew :main:test \
  --tests "com.sickworm.intellij.jugg.compiler.source.kotlin.AndroidJarClasspathRetryTest" \
  --tests "com.sickworm.intellij.jugg.compiler.SourceCompileTest.kotlinAndJavaCompile" \
  :idea:compileKotlin
```

## 7. 文档同步（实现时）

本方案落地后同步，不在写方案时提前改知识库：

- `docs/ai_knowledge/02_compile_source.md`：在 Kotlin 失败降级处记录"命中 supertype 无法访问诊断时，一次性后置 SDK `android.jar`；成功后按源文件记住该降级，同批文件跟随；失败保留首次错误；不改默认 `getModuleDependencies()`；记忆随 compile context 重建清空"。同时记录 §2.2 的边界：该失败是 Kotlin 解析嵌套类型特有，javac 走 binary name 直查不受影响。
- `docs/ai_knowledge/02_compile_core.md` §7.2：明确该重试在 invoker 内，**不是** `IIncrementalCompileRetryResolver` chain。
- `docs/ai_knowledge/09_plugin_runtime_debug.md` 排查表：ROM hidden API / `which is a supertype of` → `AndroidJarClasspathRetry` 与 invoker `-cp` 顺序；并说明日志里出现后置说明时 `-cp` 顺序与默认不同。
- 不改 `docs/wiki`：对普通应用开发者不可见，且不是产品配置项。

## 8. 实施步骤

### 8.1 Helper 与测试

- 创建 `main/src/test/java/com/sickworm/intellij/jugg/compiler/source/kotlin/AndroidJarClasspathRetryTest.kt`
- 创建 `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/AndroidJarClasspathRetry.kt`

建议 API（名称可微调，语义不可变）：

```kotlin
object AndroidJarClasspathRetry {
    fun isShadowedSupertypeDiagnostic(errorMessages: Iterable<String>): Boolean
    fun postponeSdkAndroidJar(classpath: List<String>): List<String>
}
```

诊断判定按**单个源文件**的错误列表调用，invoker 用同一个调用同时决定"是否重试"和"记住哪些文件"。classpath 是否需要后置由 `postponeSdkAndroidJar(classpath) != classpath` 判断，不合并成一个 `shouldPostpone...`。不要在 invoker 里复制字符串匹配。

### 8.2 KotlinCompilerInvoker

1. `Options` 增加 `isPostponeSdkAndroidJar: Boolean = false`；新增实例字段 `postponedAndroidJarFiles: MutableSet<String>`（源文件绝对路径）。
2. 组装 `-cp` 时：在现有 `isolatedBaseline` 重映射之后，若 `options.isPostponeSdkAndroidJar || task.files.any { it.file.absolutePath in postponedAndroidJarFiles }` 则后置；`-no-jdk` 继续看最终 dependencies（事实 8，与位置无关）。走记忆路径时打 `debug`。
3. 在现有 plugin-option 重试之后、recreate compiler 之前：取 `compileResults` 中每个失败文件的错误列表调用 helper，收集 `hasDirectSourceDiagnostic` 且命中的文件；若集合非空、`options.isCanAutoRetry && !hasRetryCompile`、且 `postponeSdkAndroidJar()` 会改变列表，则置 `hasRetryCompile = true`，打 warn 日志，递归 `compile(..., options.copy(isCanAutoRetry = false, isPostponeSdkAndroidJar = true))`。
4. 递归成功：把命中文件集合并入 `postponedAndroidJarFiles`，返回递归结果。
5. 递归失败：立即 return 递归前已构造的第一次 `CompileResult`，不记录文件。
6. 不要把后置逻辑放到 `handleMetadataError` 或 plugin 禁用分支里。

### 8.3 收尾

- 对照 diff 检查本次新增日志续行缩进。
- 跑第 6 节命令。
- 按第 7 节更新 `ai_knowledge`。
- 提交与本功能相关的代码和知识库，不夹带 `android_demo_project` 或其他未提交改动。

建议提交前缀 `[bugfix]`，标题描述 ROM/系统应用增量编译 hidden API 时被 SDK `android.jar` 挡住的可观察失败。

## 9. 完成标准

- [ ] 命中 4.2 诊断时只重试一次，且 SDK `android.jar` 位于重试 `-cp` 末尾。
- [ ] 重试成功后，再次编译同一文件直接使用后置顺序，不再先失败一次；未命中过的文件仍走默认顺序。
- [ ] 未命中诊断、普通 `unresolved reference` / `cannot find symbol` 时不后置，默认 `getModuleDependencies()` 顺序不变。
- [ ] 重试失败时用户看到第一次错误；不写入记忆；D8 仍使用原来的 `androidJar`。
- [ ] Java 编译路径与 `JavaCompilerInvoker` 无改动。
- [ ] 不进入 incremental resolver chain，不扫描 jar。
- [ ] L1 测试与 `SourceCompileTest.kotlinAndJavaCompile` 通过；知识库已同步。

## 10. 残余风险

- 触发条件放宽到整个 supertype 诊断族后，非 `android.jar` 遮蔽引起的 supertype 失败也会多编一次。这类诊断在正常增量编译里罕见，且失败后仍展示第一次错误，代价可控。
- 后置 classpath 上没有更完整的同名类时，第二次必然失败；用户仍看到第一次错误。现场 case 与 §2.2 实验均已排除该风险。
- 后置 jar 若是半截 stub，第二次可能变成新的缺成员错误，按产品选择不展示第二次。按文件记忆把这种非默认顺序限制在确有问题的文件上，不扩散到整个工程。
- 后置后的 `-cp` 顺序与 Gradle 不同，理论上同名类可能解析到 framework jar 而非 `android.jar`，产出与 Gradle 不同的字节码。这些都是 `compileOnly` 的 provided 类型，运行时由设备真实 framework 提供，framework jar 比公开 stub 更接近运行时形态，因此该偏差方向上不劣于默认顺序。
- 记忆只活到下一次 Gradle sync；sync 后第一次编译仍会白付一次失败重试。这是刻意选择，避免引入持久化。
- DataBinding kapt / Compose 等路径每次都 `new` 一个 invoker 且 `isCanAutoRetry = false`，命中时不会走本降级，也不共享记忆。属 Best-effort 收口，不为此改这些调用方。
- 该能力主要服务 ROM/系统应用，公开 SDK 应用几乎不会命中。
