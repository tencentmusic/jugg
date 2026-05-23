# 编译系统：核心架构

> 最后核对：2026-05-23
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
| `JuggCompileHelper` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` | IDE compile 入口，等待初始化/文件处理，决定增量或 Gradle，处理 Git 补检和回退提示 |
| `IncrementalCompilerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt` | 单轮增量循环，更新 undeployed/staging 状态，驱动影响传播重编译和一次性失败重试 |
| `JuggCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompiler.kt` | 组合 asset/resource/R.dex/source/dex/minify 等子阶段，按阶段失败快速收口 |
| `BaseCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt` | 所有编译器的模板方法：类型检查、模块/AndroidTest 分组、APK 分流、自定义编译器 hook |
| `CompileOrder` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileOrder.kt` | 自定义编译器插入点的顺序范围，不直接代表所有内置阶段的调度代码 |
| `CompileTask` / `CompileResult` / `CompileOutput` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt` | 编译输入、文件级结果、产物归属与 APK 分流模型 |
| `GitChangesCompileChecker` / `GitChangesRetryResolver` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/` | 编译前后异步 Git 补检与 unresolved reference 类失败重试 |

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
JuggCompileHelper.compile(options, uiHandler)
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
     -> redexClasses 转为 tempModule 下的 class 输入
     -> 若存在下一批文件，递归进入下一轮
  -> 失败且未重试过：交给 retryResolver chain 尝试修复后重试一次
```

### 4.3 `JuggCompiler` 内置阶段顺序

```text
JuggCompiler.doCompile(task)
  -> AssetOverlayCompiler：asset/native lib 进入 overlays
  -> ResourceOverlayCompiler：resource/manifest 先编译到 tmp_resource
     -> overlay res 移到 overlays
     -> R.java 交给 SourceCompiler 编译
     -> DataBinding/ViewBinding 生成源暂存为下一步 source 输入
  -> RDexForSubmoduleCompiler：必要时把 R.class 生成 R.dex
  -> SourceCompiler：Kotlin/Java/DataBinding mapper/JuggApt/class -> dex/minify
  -> 任一阶段失败或取消：停止后续阶段，并把剩余输入收口为失败/取消结果
```

### 4.4 `BaseCompiler` 模板逻辑

```text
BaseCompiler.compile(task)
  -> 校验 supportedTypes、context、outputDir
  -> 执行 order 落在 beforeCompileOrderRange 的自定义编译器
     -> consumeFiles() 可过滤后续内置编译输入
  -> doCompile(filteredTask)
     -> 默认按非 androidTest / androidTest 分两批
     -> 每批按 modulesWithOrder 编译
     -> 资源/manifest/asset 类编译器可再 splitApkAndCompile()
  -> 执行 order 落在 afterCompileOrderRange 的自定义编译器
  -> 通知 task.notifyCompiled()
```

---

## 5. 阶段顺序与扩展点

### 5.1 内置阶段

- `asset`
- `res`
- `source`
- `minify`
- `dex`

`JuggCompiler.doCompile()` 显式编排 asset/resource/source；source 内部再处理 DataBinding mapper、JuggApt、Kotlin、Java、Dex、Minify。`CompileOrder` 主要服务自定义编译器插入点。

### 5.2 自定义编译器插入点

`CompileOrder` 提供以下区间：`atFirst`、`beforeAsset/afterAsset`、`beforeRes/afterRes`、`beforeSource/afterSource`、`beforeMinify/afterMinify`、`beforeDex/afterDex`、`atLast`。

`BaseCompiler` 每个具体编译器都会执行自己的 before/after 区间。`JuggCompiler` 自身使用 `atFirst` 和 `atLast`，子编译器如 `ResourceOverlayCompiler`、`JavaCompiler`、`KotlinCompiler`、`DexCompiler` 会分别暴露对应阶段的插入点。

---

## 6. 隐形约束 / 设计思路

- 首轮成功文件会通过 `DeployFileManager.updateUncompiledFiles()` 从待编译集合移除；后续影响传播轮不再更新这组状态，避免把派生重编译误当成用户原始变更。
- 成功编译的文件会记录 `lastModified + length` 快照。迟到的 IDE 文件事件如果快照未变，会被忽略，避免已编译未部署文件重新回到未编译状态。
- Git 补检有两层：失败时 resolver 可刷新 Git 发现漏掉的新文件并重试一次；成功后 `GitChangesCompileChecker` 只在出现新的待编译文件时再触发一轮。
- 影响传播会排除本次已经编译过的文件，但 Kotlin top-level/extension 相关场景可能强制重编，入口在 `CheckEffectByTopLevelClass` 日志段。
- `splitModuleAndCompile()` 会把 androidTest module 单独分批，且 androidTest 的 module 分组 key 包含 module root，避免同名测试模块被合并。
- `splitApkAndCompile()` 是 APK scoped 的产物分流；子类在 `doApkCompile()` 输出时必须保留当前 APK 归属，否则多 APK 场景部署会丢失目标。
- `JuggCompiler` 中资源阶段产生的 DataBinding/ViewBinding 源不会立即作为最终产物结束，而是转成下一步 `SourceCompiler` 输入。
- 取消后如果递归影响传播过程中被打断，首轮会 rollback changed file 并清 staging，保证下一次还能重新编译。

---

## 7. 回退与重试机制

### 7.1 触发 Gradle 回退的常见条件

- 用户强制回退。
- 设备状态不满足增量部署。
- 变更文件点数/模块数超过阈值。
- 依赖变化、构建脚本变化或编译失败不可恢复。

### 7.2 增量内重试

- 重试策略接口：`IIncrementalCompileRetryResolver`，由 `IncrementalCompileRetryResolverChain` 串联多个实现。
- 当前 chain 顺序：
  1. `GitChangesRetryResolver`（`idea` 层）：检测 `unresolved reference / cannot find symbol` 类错误 → 触发 `GitFileChangesDetector.updateChangedFiles()` → 若发现新文件则重试一次。
  2. `IncrementalCompileRetryResolver`：检测依赖缺失关键词 → 更新 compile context → 有变化则重试一次。
- 影响传播重编译：基于 `DeployFileManager.getRecompileFiles(...)`。
- 编译成功后的 Git 补检（`GitChangesCompileChecker`）：仅当 Git 刷新后出现**新的待编译**文件（`!hasCompiledOnce`）才触发二次增量编译；已在当轮编译完成、仅因 undeployed 集合成员变化的文件（如 Kuikly 改写 `KuiklyCoreEntry.kt` 且快照未变）不触发。异步 Git 任务可能在 Kotlin 编译结束前完成，`getAsyncResultWithTimeout` 会按路径用当前 `DeployFileManager` 状态再校验一次，避免缓存的 `ChangedFile` 仍显示 `compiledTimes=0` 而误触发 `compile again`。

---

## 8. 排查入口

| 现象 | 优先入口 |
|------|----------|
| 用户说“这次没走增量 / 直接 Gradle” | `JuggCompileHelper.preprocessIncrementalCompile()`、`checkFallback()` |
| 编译成功后日志出现 `found effected source files, continue compile` | `IncrementalCompilerHelper.compile()` 中 `getRecompileFiles()` 后的 `unCompiledEffectedFiles` |
| 编译成功后又因 Git 补检 `compile again` | `GitChangesCompileChecker.getAsyncResultWithTimeout()` |
| 资源/manifest/asset 产物影响错 APK | `BaseCompiler.splitApkAndCompile()` 与子类 `doApkCompile()` 输出的 `targetApkPaths` |
| R 相关运行时缺类或 `R.styleable` 异常 | `JuggCompiler` 中 `R.java` -> `SourceCompiler` -> `RDexForSubmoduleCompiler` 链路 |
| 取消后下次没有重新编译 | `IncrementalCompilerHelper` 取消分支的 `rollbackChangedFile()` / `clearStagingFiles()` |
| 自定义编译器没有插入预期阶段 | `CompileOrder` 数值区间 + 具体编译器的 `beforeCompileOrderRange` / `afterCompileOrderRange` |

---

## 9. 关联文档

- 源码编译：`02_compile_source.md`
- 资源编译：`02_compile_resource.md`
- DataBinding/ViewBinding：`02_compile_databinding.md`
- 自定义编译器与交互：`02_compile_custom_ui.md`
- 部署影响分析：`03_deploy_data_generator.md`
