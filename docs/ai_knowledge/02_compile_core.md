# 编译系统：核心架构

> 最后核对：2026-08-31
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只回答增量编译的总控问题：

- 从 IDE compile 请求到增量/Gradle 回退的决策链。
- 单轮增量编译如何串联 asset/resource/source/dex/minify。
- 成功后为何可能继续下一轮编译，失败后为何可能重试或回退。

不展开单一子编译器实现细节：Java/Kotlin/Dex 见 `02_compile_source.md`，资源见 `02_compile_resource.md`，DataBinding 见 `02_compile_databinding.md`。

---

## 2. 核心源码索引

| 入口类 | 文件 | 作用 |
|--------|------|------|
| `JuggCompilerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt` | 共享 compile 入口，等待初始化/文件处理，决定增量或 Gradle，处理 Git 补检和回退提示 |
| `IncrementalCompilerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt` | 单轮增量循环，更新 undeployed/staging 状态，驱动影响传播重编译和一次性失败重试 |
| `JuggCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompiler.kt` | 组合 Compose resource/asset/resource/R.dex/source/dex/minify 等子阶段，按阶段失败快速收口 |
| `ComposeResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/compose/ComposeResourceCompiler.kt` | 为受支持的 Compose Multiplatform 资源准备 CVR/asset、生成 accessor Kotlin 并编译 generated expect/actual |
| `BaseCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt` | 所有编译器的模板方法：类型检查、模块/AndroidTest 分组、APK 分流、自定义编译器 hook |
| `CompileOrder` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileOrder.kt` | 自定义编译器插入点的顺序范围，不直接代表所有内置阶段的调度代码 |
| `CompileTask` / `CompileResult` / `CompileOutput` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt` | 编译输入、文件级结果、产物归属与 APK 分流模型 |
| `GitChangesCompileChecker` / `GitChangesRetryResolver` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/` | 编译前后异步 Git 补检与 unresolved reference 类失败重试 |

---

## 3. 核心状态与数据模型

| 对象 | 生命周期 | 关键语义 |
|------|----------|----------|
| `CompileTask` | 每次子编译阶段新建 | `parentTask` 传递取消状态与已编译通知；`outputDir` 按阶段切到 staging/classes/overlays/tmp 目录 |
| `CompileResult.details` | 子阶段返回后向上合并 | 记录输入文件成功/失败；失败时 `quickFailedOthers()` 会把未执行文件标为跳过失败 |
| `CompileResult.outputs` | 子阶段产物集合 | 后续写入 `DeployFileManager.addStagingFiles()`，部署只消费 staging 中的有效产物 |
| `CompileOutput.apkPath` | 产物归属锚点 | 兼容旧单 APK 语义；真实 APK 产物会至少包含自身 |
| `CompileOutput.targetApkPaths` | 多 APK 分流 | 表示产物实际影响的 APK 集合；资源/manifest/asset 经 `splitApkAndCompile()` 按 APK scoped 输出 |
| `CompileLoopStatus` | 一次增量 compile 调用内 | 标识首轮/重试，记录本轮已经编译过的文件，避免影响传播无限循环 |
| `CompileStatusHolder` | UI/任务共享 | 取消信号与当前编译文件列表，子阶段通过 `task.isShouldCancel` 快速停止 |

---

## 4. 核心调用链路

### 4.1 IDE compile 到增量/Gradle 决策

```text
JuggCompilerHelper.compile(options, uiHandler)
  -> 记录 LastCompileTimestampRegistry，用于 MCP/status/hook 基线
  -> 等待初始化和 pending file processing，避免文件事件未入队就开始判断
  -> preprocessIncrementalCompile()
     -> 有强制回退、构建/依赖/设备/文件数量等风险时返回一个增量失败结果
     -> 返回 null 才进入 incrementalCompile()
  -> 增量成功：直接返回
  -> 增量失败但不可回退：提示下一次直接运行会回退，当前返回失败
  -> 需要回退：通知 fallback，执行 gradleCompile()
```

### 4.2 单轮增量编译与影响传播

```text
IncrementalCompilerHelper.compile(undeployedFiles)
  -> ChangedFile 转 CompileFile，设置 CompileStatusHolder 当前文件
  -> asyncCheckBeforeCompile() 预热 const-ref 分析等待
  -> JuggCompiler.compile(CompileTask(stagingDir))
  -> 首轮更新 DeployFileManager 的 uncompiled 状态
  -> 所有输出写入 staging
  -> 成功后 getRecompileFiles()
     -> effectedSourceFiles 经 IFileChangesHandler 还原为 ChangedFile
        -> 各模块实际 build directory 与传统 `${moduleRootDir}/build` 下的路径被统一过滤
     -> redexClasses 转为 tempModule 下的 class 输入
     -> 若存在下一批文件，递归进入下一轮
  -> 失败且未重试过：交给 retryResolver chain 尝试修复后重试一次
```

### 4.3 `JuggCompiler` 内置阶段顺序

```text
JuggCompiler.doCompile(task)
  -> ComposeResourceCompiler：先准备 Compose resource、生成并编译 accessor Kotlin
     -> changed Compose asset 交给 AssetOverlayCompiler
     -> generated class 交给后续 SourceCompiler/DexCompiler
  -> AssetOverlayCompiler：asset/native lib（含 Compose asset）进入 overlays
  -> ResourceOverlayCompiler：resource/manifest 先编译到 tmp_resource
     -> overlay res 移到 overlays
     -> R.java 交给 SourceCompiler 编译
     -> DataBinding/ViewBinding 生成源暂存为下一步 source 输入
  -> RDexForSubmoduleCompiler：必要时把 R.class 生成 R.dex
  -> SourceCompiler：Kotlin/Java/DataBinding mapper/JuggApt/class -> dex/minify
  -> 任一阶段失败或取消：停止后续阶段，并把剩余输入收口为失败/取消结果
```

## 5. 阶段顺序与扩展点

### 5.1 内置阶段

- `compose resource`
- `asset`
- `res`
- `source`
- `minify`
- `dex`

`JuggCompiler.doCompile()` 显式编排 Compose resource/asset/resource/source；Compose 阶段必须先完成，生成的 asset 才能进入 `AssetOverlayCompiler`，生成的 class 才能并入 source/dex 链。source 内部再处理 DataBinding mapper、JuggApt、Kotlin、Java、Dex、Minify。`CompileOrder` 主要服务自定义编译器插入点。

### 5.2 自定义编译器插入点

`CompileOrder` 提供以下区间：`atFirst`、`beforeAsset/afterAsset`、`beforeRes/afterRes`、`beforeSource/afterSource`、`beforeMinify/afterMinify`、`beforeDex/afterDex`、`atLast`。

`BaseCompiler` 每个具体编译器都会执行自己的 before/after 区间。`JuggCompiler` 自身使用 `atFirst` 和 `atLast`，子编译器如 `ResourceOverlayCompiler`、`JavaCompiler`、`KotlinCompiler`、`DexCompiler` 会分别暴露对应阶段的插入点。

---

## 6. 隐形约束 / 设计思路

- 首轮成功文件会通过 `DeployFileManager.updateUncompiledFiles()` 从待编译集合移除；后续影响传播轮不再更新这组状态，避免把派生重编译误当成用户原始变更。
- 文件进入待编译状态时会记录 `lastModified + length` 快照。迟到的 IDE/Git 文件事件如果快照未变，会被忽略并保留原编译次数；只有文件内容确实变化时才重新进入待编译状态。成功编译后会刷新快照，避免已编译未部署文件被重复事件重新打开。
- Git 补检有两层：失败时 resolver 可刷新 Git 发现漏掉的新文件并重试一次；成功后 `GitChangesCompileChecker` 只在出现新的待编译文件时再触发一轮。
- 影响传播会排除上一轮已经编译过的文件，但 Kotlin top-level file facade 相关场景会例外：`getRecompileFiles()` 会读取 `.kotlin_module` 的 file facade 列表，若调用方 source 的 `effectedByClasses` 命中这些 facade，则通过 `topLevelFacadeEffectedSourcePaths` 标记允许再编译一次。
- `BaseCompiler` 是所有子编译器的模板层，负责类型校验、模块/androidTest 分批、APK 分流和 custom compiler hook；单个子编译器内部顺序优先直接读对应实现。
- `splitModuleAndCompile()` 会把 androidTest module 单独分批，且 androidTest 的 module 分组 key 包含 module root，避免同名测试模块被合并。
- `splitApkAndCompile()` 是 APK scoped 的产物分流；子类在 `doApkCompile()` 输出时必须保留当前 APK 归属，否则多 APK 场景部署会丢失目标。
- `JuggCompiler` 中资源阶段产生的 DataBinding/ViewBinding 源不会立即作为最终产物结束，而是转成下一步 `SourceCompiler` 输入。
- `FileChangesHandler` 统一排除所有模块的实际 Gradle build directory 与传统 `${moduleRootDir}/build`。文件监听、Git 补检、恢复事件和源码影响传播经过该入口时，Gradle generated source、resource、asset、manifest、native lib 或 build file 都不会进入变更列表；目录事件会在递归前剪枝。该规则不影响编译器在本轮内直接登记和交接的 JuggApt/Resource/Compose generated source。
- 删除事件只按路径移除此前登记的待编译项；已不存在的文件不会转成 `ChangedFile`，也不会生成 class、resource、asset 或 Manifest 的移除数据。删除本身因此不会让增量编译失败或自动回退，设备继续保留已安装 APK 和既有 overlay 中的旧内容。重命名会被拆成旧路径删除和新路径新增/修改，只有新路径能够进入编译。需要让旧内容真正消失时，才通过完整 Gradle build 刷新 APK 基线。
- Compose resource 按项目 Gradle task 暴露的 generator API 结构识别支持能力，不使用 Compose 或 Kotlin 精确版本白名单。项目快照会保留“已检测但不支持”的状态、已配置资源根和用户可见原因；资源变化仍进入编译并失败，随后复用现有下一次运行 Gradle fallback 语义，不会因 `composeResourceInfo=null` 静默过滤。
- Compose resource 文件删除同样不会形成编译输入；当前没有 deletion 图、generated source/cache 复用或完整 source-set 依赖图，旧 generated class 和已部署资源会继续保留到完整 Gradle build 刷新基线。
- 取消后如果递归影响传播过程中被打断，首轮会 rollback changed file 并清 staging，保证下一次还能重新编译。

---

## 7. 回退与重试机制

### 7.1 触发 Gradle 回退的常见条件

- 用户强制回退。
- 当前 Configuration 的 compile command 与最近一次成功 full build 基线不一致（例如 Sync 后切换了 Active Build Variant）。日志会同时打印 `last=` 与 `current=` 两条 command，便于确认是 task 切换还是选中了另一条 Jugg Configuration。
- 设备状态不满足增量部署。
- 变更文件点数/模块数超过阈值。
- 依赖变化、构建脚本变化或编译失败不可恢复。

无文件变化的 fallback 确认框和手动 `Force Gradle Compile` 确认框均允许用户选择忽略 Gradle build cache。选中后，本轮 Gradle command 追加 `--no-build-cache --rerun-tasks`；该选项只影响本轮回退，不写回 Run Configuration，并在任务启动后清除。

### 7.2 增量内重试

- 重试策略接口：`IIncrementalCompileRetryResolver`，由 `IncrementalCompileRetryResolverChain` 串联多个实现。
- 当前 chain 顺序：
  1. `GitChangesRetryResolver`（`idea` 层）：检测 `unresolved reference / cannot find symbol` 类错误 → 触发 `GitFileChangesDetector.updateChangedFiles()` → 若发现新文件则重试一次。
  2. `IncrementalCompileRetryResolver`：检测依赖缺失关键词 → 更新 compile context → 有变化则重试一次。
- 影响传播重编译：基于 `DeployFileManager.getRecompileFiles(...)`；`IncrementalCompilerHelper` continue compile 过滤两层：（1）排除**上一轮**已编译源文件（`lastRoundCompiledPaths`），但 `RecompileFiles.topLevelFacadeEffectedSourcePaths` 标记的 Kotlin top-level file facade 调用方可突破该过滤；（2）排除本 session 内已按相同影响触发键跟编过的源文件（`ContinueCompileEffectFilter.resolveUncompiledEffectedFiles`：派发跟编前 `schedulePendingEffectTriggers` 写入 `pendingEffectTriggerKeys`，子帧在过滤前先消费 pending 写入 `satisfiedEffectTriggers`；键为 `effectedPath + effectedByClasses` 或首轮 const-ref 批次）。更早轮次若出现**新的**触发方（如定义方 B 结构变化后首次要求重编调用方 A）仍会进入下一轮；同一 `CrashDataSource -> SafeMode` 键不会乒乓重复跟编。递归跟编轮次只做 class/dex 结构影响传播，不再把这些跟编源码作为 `ConstRefEngine` 的新 changed source 输入。
- 编译成功后的 Git 补检（`GitChangesCompileChecker`）：仅当 Git 刷新后出现**新的待编译**文件（`!hasCompiledOnce`）才触发二次增量编译；已在当轮编译完成、仅因 undeployed 集合成员变化的文件（如 Kuikly 改写 `KuiklyCoreEntry.kt` 且快照未变）不触发。编译结束后 `getAsyncResultIfCompleted()` 只消费已经完成的异步任务，不等待仍在运行的 Git 查询；未完成时记录 debug 并继续当前流程，迟到结果不会被后续 Run 误读。已完成结果仍会按路径用当前 `DeployFileManager` 状态再校验一次，避免缓存的 `ChangedFile` 仍显示 `compiledTimes=0` 而误触发 `compile again`。

### 7.3 编译器资源生命周期

`JuggCompilerHelper` 持有的 `JuggCompiler` 是 IntelliJ `Disposer` 树根。Compile Context 变化导致 compiler rebind，或 helper 关闭时，必须通过 `Disposer.dispose()` 递归释放注册的子资源，不能只调用根对象的 `dispose()`。standalone 使用的 `platform_compat` Disposer 保持 identity-based 注册、子节点主动释放后的父关系清理、兄弟节点逆注册顺序释放，以及单个节点抛错时仍完成其余子树和父节点清理的语义。

---

## 8. 排查入口

| 现象 | 优先入口 |
|------|----------|
| 用户说“这次没走增量 / 直接 Gradle” | `JuggCompilerHelper.preprocessIncrementalCompile()`、`checkFallback()` |
| 编译成功后日志出现 `found effected source files, continue compile` | `IncrementalCompilerHelper.compile()` 中 `getRecompileFiles()` 后的 `unCompiledEffectedFiles` |
| 编译成功后又因 Git 补检 `compile again` | `GitChangesCompileChecker.getAsyncResultIfCompleted()` |
| 资源/manifest/asset 产物影响错 APK | `BaseCompiler.splitApkAndCompile()` 与子类 `doApkCompile()` 输出的 `targetApkPaths` |
| R 相关运行时缺类或 `R.styleable` 异常 | `JuggCompiler` 中 `R.java` -> `SourceCompiler` -> `RDexForSubmoduleCompiler` 链路 |
| 取消后下次没有重新编译 | `IncrementalCompilerHelper` 取消分支的 `rollbackChangedFile()` / `clearStagingFiles()` |
| 自定义编译器没有插入预期阶段 | `CompileOrder` 数值区间 + 具体编译器的 `beforeCompileOrderRange` / `afterCompileOrderRange` |
| Compile Context 切换后后台资源未释放或重复释放 | `JuggCompilerHelper.juggCompiler` setter、`close()` 与 `Disposer` 注册树 |

---

## 9. 关联文档

- 源码编译：`02_compile_source.md`
- 资源编译：`02_compile_resource.md`
- DataBinding/ViewBinding：`02_compile_databinding.md`
- 自定义编译器与交互：`02_compile_custom_ui.md`
- 部署影响分析：`03_deploy_data_generator.md`
