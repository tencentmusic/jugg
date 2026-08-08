# 部署系统：影响分析与部署数据生成

> 最后核对：2026-08-07
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只回答一件事：Jugg 拿到增量编译产物后，如何判断哪些 class/resource/lib 进入本轮 `JuggDeployData`，以及哪些源码或字节码需要补偿更新。

本页不展开部署执行、设备状态恢复、常量引用数据库细节；对应入口见 `03_deploy_core.md`、`03_deploy_complete.md`、`03_deploy_const_ref.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `DeployDataGenerator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt` | 从 `DeployItem` 和部署历史生成 `JuggDeployData`，集中决定 hot reload / hot fix / reinstall 输入 |
| `DeployDataDatabase` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataDatabase.kt` | APK 与增量部署索引 facade，聚合 SQLite helper 的引用查询和 commit |
| `DeployDataDatabaseSqLiteHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataDatabaseSqLiteHelper.kt` | method/field/subclass/source 索引的 SQLite 查询实现，是 effectedSource 传播的主要事实来源 |
| `ApkParserProcessLauncher` / `ApkParserProcess` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/` | 在独立 JVM 中解析 APK/Dex 并直接更新 SQLite，隔离大工程解析时的瞬时堆占用 |
| `ClassNodeComparator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/ClassNodeComparator.kt` | 比较新旧 `ClassNode`，输出结构变化、abstract 变化和 generic signature 变化 |
| `InlineMethodDetector` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/InlineMethodDetector.kt` | release/minify 场景从 mapping 里找 R8 inline 调用方，补齐字节码补偿类 |
| `EffectedClassNode` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/EffectedClassNode.kt` | 受影响类模型，区分源码重编译、inline 补偿、minify 移除补偿 |
| `ConstRefEffectProvider` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/ConstRefEffectProvider.kt` | 常量引用影响分析入口；结果走 `constRefEffectedSourcePaths`，不混入 `effectedClassNodes` |

---

## 3. 核心数据模型

### 3.1 `JuggDeployData` 的关键字段

| 字段 | 来源 | 部署语义 |
|---|---|---|
| `newClasses` | 旧 DB 中不存在的新 class | 无需 JVMTI 重定义，作为新 class overlay 延迟加载 |
| `hotReloadModifiedClasses` | `ClassNodeComparator.isCanHotReload = true` | 结构未变，可走更轻量的 class 更新 |
| `hotFixModifiedClasses` | 多 dex / library dex / 结构变化 class | 结构或归属更复杂，走 hot fix 路径 |
| `effectedSourceAndClassNodes` | method/field/subclass/generic/minify/inline 分析 | 需要源码重编译或字节码补偿的调用方 |
| `overlays` / `isFullRes` | resource/asset 变更 + 首次 overlay 历史 | 首次资源部署会补齐全量 res，避免设备端缺资源 |
| `updateApkFiles` | manifest、`resources.arsc`、native lib | 需要改 APK 并重签/重装的产物 |
| `constRefEffectedSourcePaths` | `ConstRefEffectProvider` | 常量引用命中的源码路径，独立于 class 引用传播 |

这三类 class 不是同一失败链路的不同名字，而是在部署前就按基线和结构差异主动分流：

- `newClasses` 尚未被旧 APK/历史部署定义，写入 overlay 后可在首次引用时由 ClassLoader 加载。
- `hotReloadModifiedClasses` 保持可重定义结构，`OverlayUpdateBuilder` 将其作为 `modifiedClasses` 交给 Android Studio deployer/JVMTI。
- `hotFixModifiedClasses` 是已经存在但结构不满足 JVMTI 约束的 class。它和真正的新 class 一起走 deployer 的 `newClasses` transport，并由 `JuggDeployData.isNeedRestartApp` 要求重启进程，从 overlay 加载替代版本。

因此 Jugg 的“热重载 + 热修复”复用同一份 overlay 数据通道，但分别使用 JVMTI redefine 和重启后 ClassLoader 覆盖。设备或运行时不适合 JVMTI 时，compat deploy 会进一步切为 push-only 并补入兼容运行时文件；若前置结构判断漏掉设备特有限制，`DeployRetryHandler` 仍会在 `JVMTI_ERROR_UNMODIFIABLE_CLASS`、redefiner/internal error 等确定信号下把全部 modified class 转为 HOT_FIX 重试一次。

### 3.2 `ClassNodeDiffResult` 到下游的映射

| 字段 | 触发条件 | 下游集合 |
|---|---|---|
| `effectMethods` | 方法删除、签名变化、`private` 与非 private 切换、其他有效 access flag 变化 | `changedMethodRef` |
| `deletedFields` | 字段删除 | `changedFieldRef` |
| `isAddedAbstractMethodForNonAbstractClass` | 抽象类/接口新增 abstract 方法 | `changedAbstractClasses` |
| `modifiedGenericSignature` | 类级泛型 signature 变化 | `changedGenericSignatureClasses` |

`effectMethods` 判断会忽略 `ACC_ABSTRACT` 和 `ACC_PRIVATE` 之外的等价细节；仅方法体变化不会进入 `effectMethods`。`R$xxx` class 会整体跳过 method/field 引用传播，避免资源修复流程制造大量误重编译。

### 3.3 `EffectedType`

| 类型 | 检测来源 | 处理路径 |
|---|---|---|
| `SOURCE` | method/field/subclass/abstract/generic 传播 | 源码重编译 |
| `INLINE_IMPL_CHANGE` | `InlineMethodDetector` 解析 R8 mapping inline 调用方 | `DexMinifyCompiler` 字节码补偿 |
| `MINIFY_MEMBER_REMOVED` | `getEffectedClassNodesForMinify` 发现类或成员被 R8/ProGuard 移除 | `DexMinifyCompiler` 字节码补偿 |

---

## 4. 核心调用链路

```text
编译产物成为 DeployItem
  -> DeployDataGenerator.buildDeployData(items)
     解析 changed dex，按 resource / asset / native lib 分组
  -> ClassNodeComparator.compare(oldClassNode, newClassNode)
     把结构变化压缩为 changedMethodRef / changedFieldRef / abstract / generic 四类信号
  -> DeployDataDatabase.getEffectedSourceAndClass(...)
     用历史引用索引找调用方、子类、generic 受影响类，并可附加 minify 移除补偿
  -> InlineMethodDetector.findInlineEffectedClasses(...)
     release/minify 场景补齐持有旧 inline 副本的类
  -> ConstRefEffectProvider.ensureReadyForRecompile() + getEffectedFiles()
     常量引用独立查询，失败只退化为 completed cache / empty result
  -> JuggDeployData
     交给后续 deploy/run 决定 install、apply changes、restart 和 commit
```

不能把 `buildDeployData()` 的结果视为已提交状态。部署历史只在后续成功部署后由 `commitDeployedData()` 写回；失败轮的 staging / deploy data 不能污染下一轮。

### 4.1 APK 基线索引与解析边界

APK database 不只是“class 是否存在”的缓存。Jugg 需要持久化 class 结构、method/field 引用、父子类关系、source 映射，以及 APK 内 dex/resource entry 的 checksum，才能同时支撑 HOT_RELOAD/HOT_FIX 分类、影响传播、资源补全和下一次 APK 更新 diff。把这些数据长期留在 IDE heap 中会让大 APK 的解析峰值和 GC 直接影响 Android Studio，因此当前 `ApkParserProcessLauncher` 的隔离门槛为 0 MB，正常体积的 APK 会启动独立 JVM 解析；子进程直接更新 app-scoped SQLite，退出后释放解析期内存。

解析仍按 Best-effort 收口：独立进程启动、classpath 或执行失败时会 warn，并回退当前 IDE 进程解析，而不是直接让完整构建后的上下文初始化失败。数据库更新先按 APK `lastModified` 快速判断，再用 entry checksum 找新增、删除和变化的 dex/overlay；变化 dex 超过 3 个或达到现有 dex 数量 20% 时重建该 app 数据库，否则只解析变化部分。这个阈值是性能策略，不是部署语义，调整时必须保留“少量变化增量更新、大量变化完整重建”的契约。

查询时 `IncrementalDeployDataDatabase` 中已成功部署的 class/overlay 优先于 APK SQLite 基线。否则连续两次增量修改会一直和最初 APK 比较，既会误判 class 结构，也会让影响传播引用已经过时的数据。

---

## 5. effectedSource 传播规则

`DeployDataDatabaseSqLiteHelper.getEffectedClassNodes()` 当前按 6 个阶段收敛到 `EffectedClassNode(SOURCE)`：

| 阶段 | 作用 | 关键约束 |
|---|---|---|
| Step 1 | 将 changed method/field/abstract/generic class 转成 DB classId | 后续 SQL 都依赖历史 APK / deploy DB 中已有 classId |
| Step 2 | 对非 static changed method 的 owner 查 `subclass_refs`，构造子类虚拟 method ref | 只模拟虚方法分发；static 方法保留给 Step 3，但不能启动子类遍历 |
| Step 3 | 查 `method_refs` / `field_refs`，找到直接调用或访问变更成员的类 | `changedMethodRefsWithSubclasses` 包含 static 方法，保证 static 直接调用仍会命中 |
| Step 4 | 对新增 abstract method 的 class/interface 递归找子类 | 非抽象子类必须重编；abstract 子类继续向下传播 |
| Step 5 | 对 generic signature 变化类及其子类，查直接 member callers 并递归找子类 | 解决 DEX 擦除后 descriptor 不变但源码泛型约束改变的问题 |
| Step 6 | 将受影响 classId 反查 class name/source，生成 `EffectedClassNode(SOURCE)` | 这里才形成 SourceCompiler 可消费的源码路径 |

Step 2 的 static 过滤是高风险边界：`changedMethodRefsWithSubclasses` 必须保留全部 method，供 Step 3 查直接引用；但 `currentSuperClassIds` 只能来自 `access == MISS_ACCESS || non-static` 的 method owner。否则 Kotlin lambda / `$r8$lambda$` 这类 static 方法会误触发整棵子类级联重编译。

Generic signature 传播只能覆盖两类确定场景：子类声明链，以及对变化类/受影响子类的 direct method/field caller。纯源码泛型约束但 DEX 中没有 direct member ref 的间接场景，不能假定一定命中。

---

## 6. release/minify 补偿

`isNeedCheckRecompileMinifyRemovedClass = true` 时，`DeployDataGenerator` 会把 `parsedDex` 传入 DB 查询和 inline 检测：

- `getEffectedClassNodesForMinify()` 检查增量 dex 引用的类或成员是否已被 APK 中的 R8/ProGuard 结果移除，命中后标为 `MINIFY_MEMBER_REMOVED`。
- `InlineMethodDetector` 读取 mapping，找“被改方法曾经 inline 到哪些类”，命中后标为 `INLINE_IMPL_CHANGE`。
- `DeployDataGenerator.merge()` 合并 inline 结果时，同一 class 如果已是 `SOURCE`，必须保留 `SOURCE`。源码重编译能力强于字节码补偿，反向不成立。

`isCompilingEffectedSourceFiles = true` 时会跳过 inline 检测，避免“正在补偿受影响源码”又继续制造下一轮 inline 补偿循环。

---

## 7. 隐形约束

- `isNeedCheckRecompile = false` 会同时跳过 class 引用传播和 constRef 查询；此时 `effectedSourceAndClassNodes` 与 `constRefEffectedSourcePaths` 都应为空。
- constRef readiness 失败不会中断部署数据生成，只会记录 warn 并退化查询；这类运行时风险应去 `03_deploy_const_ref.md` 查缓存准备状态。
- 首次 overlay 部署会通过 `addFullRes()` 补全资源；不要只根据本轮 changed resource 数量判断设备端资源完整性。
- `updateApkFiles` 只收 manifest、配套 `resources.arsc` 和 native lib；普通 overlay 不等价于需要改 APK。
- `deletedNormalMethodClasses` 会过滤方法名含 `$` 的合成方法，避免把编译器生成方法删除当作用户代码删除信号。
- APK 解析独立进程只是内存隔离边界；SQLite 文件仍由 applicationId 归属。多 APK 同属一个 applicationId 时必须共享同一个 helper，废弃 applicationId 的 DB 只在新一轮初始化完成后清理。

---

## 8. 排查入口

| 现象 | 优先入口 |
|---|---|
| 改动很小却触发大量 `effectedSource` | `DeployDataDatabaseSqLiteHelper.getEffectedClassNodes()` Step 2，检查 changed method 是否 static / `$r8$lambda$` |
| 调用方没重编译导致运行异常 | `ClassNodeComparator.compare()` 输出，以及 Step 3 `method_refs` / `field_refs` 是否命中 |
| 修改泛型约束但 effectedSource 为空 | `ClassNodeComparator.modifiedGenericSignature` 与 Step 5 generic propagation |
| release 增量后缺类/缺成员 | `getEffectedClassNodesForMinify()` 与 `EffectedType.MINIFY_MEMBER_REMOVED` |
| release 方法体修改但调用方仍旧逻辑 | `InlineMethodDetector.findInlineEffectedClasses()` 和 mapping 文件是否存在 |
| 常量改动未触发调用方 | `ConstRefEffectProvider.ensureReadyForRecompile()`，再转 `03_deploy_const_ref.md` |
| `Isolated process parsing failed` 且 `ClassNotFoundException: ApkParserProcess` | `ApkParserProcessLauncher` 的 classpath 构建，检查是否用了 URL 编码路径 |
| 完整构建后 APK DB 初始化导致 IDE 内存突增 | 确认是否进入独立进程；若已回退 in-process，先查 Java home、plugin classpath 与子进程输出 |

---

## 9. 关联文档

- 部署核心：`03_deploy_core.md`
- 完整部署流程：`03_deploy_complete.md`
- 常量引用影响分析：`03_deploy_const_ref.md`
- 编译主流程：`02_compile_core.md`
- 级联重编译案例：`docs/task/2026-03/recompile_cascade_bug_analysis.md`
