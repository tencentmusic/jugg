# 插件运行时问题排查手册

> 最后核对：2026-03-28
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 0. AI 读取本文档时的自动行动清单

收到"排查问题"类请求且含有日志片段时，**按顺序执行**，无需等待用户追问：

1. **定位日志时间区间**：从用户提供的日志片段找到问题时间戳（精确到毫秒）。
2. **读取完整上下文**：在日志中向上/向下各扩展 50~100 行，确认前后调用链。
3. **检索关键词**：用下表"常用搜索词"在日志中定位锁等待、EDT 阻塞、耗时超标等信号。
4. **对照代码**：根据日志中的 `[ClassName]` 标签，直接用 IDE MCP 工具跳转到对应类。
5. **输出结论**：给出根因 + 调用链 + 修复方向，不要只描述现象。

---

## 1. 运行时目录结构

由 `JuggPathManager` 定义，所有路径均相对于 `{projectDir}`：

```
build/jugg/                            # juggRootDir
├── log/                               # 日志目录
│   ├── compile_latest.log             # 当前主日志的 best-effort 快捷入口
│   ├── compile_latest-1.log           # 上一份主日志的 best-effort 快捷入口
│   └── compile_YYYY-MM-DD_HH-mm-ss.0.log
├── build/staging/                     # 本次增量编译输出（dex/资源）
├── database/
│   ├── apk/                           # APK 解析后的 SQLite DB（*.db）
│   ├── project_infos.db/              # 模块/APK 配置快照
│   │   ├── project_infos.json
│   │   ├── gradle_project_infos.json
│   │   └── base_build_cmd.txt
│   ├── compile_context.db/            # classpath、模块信息
│   └── deploy_history.db/             # 部署历史（增量恢复）
├── classpath/
│   ├── root/                          # classpath jar
│   ├── apk/                           # APK 文件缓存
│   └── libraries/                     # 依赖库备份
├── config/
│   ├── custom_compilers/
│   ├── client_setup.md
│   └── jugg-android-dev-loop/
└── tmp/diff/                          # 远程编译 diff 结果

${projectRoot}/.gradle/jugg/
├── readProjectInfo.gradle.kts
└── jugg-runtime.jar

~/.jugg/const_ref/                     # 跨项目常量引用缓存（全局）
```

**代码位置**：`main/src/main/java/.../project/JuggPathManager.kt`

---

## 2. 日志格式

```
[2026-03-16 16:13:27.109] [FINE   ] [ClassName] message
```

- 时间戳精确到**毫秒**
- 级别：`FINE`=debug / `INFO` / `WARNING` / `SEVERE`
- `[ClassName]` 由 `logger.getInstance("ClassName")` 决定，可直接作为代码定位依据

---

## 3. 常用搜索词速查

| 排查目标 | 搜索关键词 |
|---------|-----------|
| 编译开始 | `Jugg compile started` |
| 增量/全量判断 | `preprocessIncrementalCompile` |
| 无文件变化弹框 | `confirmFallbackWhenNoFileChanges` |
| EDT 异步派发（文件变化） | `dispatching to background` |
| 锁等待耗时 | `waiting for TaskRunnerManager lock` / `waitCost=` |
| APK DB 初始化 | `initAfterInstall parsed apk start` / `database all init finish` |
| SQLite 查询 | `getClassNodes` |
| 部署开始 | `deploy start` |
| 编译耗时 | `cost ${costTime}ms` |
| 回退原因 | `fallback` / `Fallback` |
| 编译失败 | `incremental compile error` / `SEVERE` |
| UI freeze 起点 | `uiFreezeStarted` / `InvocationEvent has timed out` |
| ConstRef 启动延后 | `ConstRefEngine defer initial full scan until startup stabilizes` |
| ConstRef 限速实值 | `ConstRefEngine io throttle enabled` |
| ConstRef 全扫进度 | `ConstRefEngine full scan progress` |
| IDE 启动链 | `InitialVfsRefresh` / `postInit` / `clangd` |
| 重混淆结果 | `Obfuscated:` |
| 重混淆注解问题 | `visitAnnotation` / `mapType` |
| 重混淆类型引用遗漏 | `const-class` / `filled-new-array` / `NoClassDefFoundError` |
| 重混淆 access flag 对齐 | `alignAccessFlags` / `alignClassAccessFlags` / `alignMethodAccessFlags` / `alignFieldAccessFlags` / `IllegalAccessError` / `AbstractMethodError` / `IncompatibleClassChangeError` / `ExternalSyntheticLambda` |

---

## 4. 高频问题 → 根因 → 代码位置

### 4.1 IDE 卡顿（EDT 被阻塞）

**信号**：用户描述点击/操作时短暂冻结；日志在某时间点停顿后才有输出。

**排查步骤**：
1. 找停顿区间（两条日志时间戳差值 > 100ms 且无中间日志）
2. 搜 `waitCost=` 确认是否有锁等待
3. 搜 `dispatching to background` 确认 EDT 调用是否正确派发
4. 检查 `@Synchronized` 方法是否可能被 EDT 直接调用

**已知根因**（已修复，供参考）：
- `FileChangesDetector.afterVfsChange()` 在 EDT 调用 `DeployFileManager.addChangedFile()`，与编译线程持有的 `@Synchronized` 锁竞争，导致 EDT 阻塞 ~150ms
- 修复：EDT 调用时通过 `IBackgroundTaskRunner.runBackgroundSafe()` 异步派发

**关键类**：
```
idea/.../project/FileChangesDetector.kt       # VFS 事件监听（afterVfsChange 在 EDT）
main/.../deploy/DeployFileManager.kt          # addChangedFile / removeChangedFile
main/.../project/BackgroundTaskRunner.kt      # IBackgroundTaskRunner.isOnEdt
idea/.../project/TaskRunnerManager.kt         # isOnEdt 实现（ApplicationManager.isDispatchThread）
```

### 4.1.1 启动后长时间卡死（`postInit / InitialVfsRefresh / clangd / ConstRef` 竞争）

**先收集四份证据**：
1. `build/jugg/log/compile_latest.log` 或最近的 `compile_*.log`
2. IDE `idea.log`
3. `threadDumps-freeze-*`
4. 一份当场 `jcmd <pid> Thread.print -l`

**时间线对齐**：
- 先用 `idea.log` 中 `uiFreezeStarted` 或用户感知时间做锚点；
- 再对齐 `compile_*.log` 中 `ConstRefEngine defer initial full scan until startup stabilizes` / `ConstRefEngine io throttle enabled` / `ConstRefEngine full scan progress`；
- 若 freeze 时间窗与 `FULL_SCAN` 运行区间重叠，再看 worker 栈是否落在 `ConstRefEngine.parseReferencesByDbSessionMode`、`ConstRefCacheDatabase.queryLatestDefinitionsByWhere`、`NativeDB.step`。

**快速分桶**：
- `ConstRef` 侧高负载：compile log 有活跃 `FULL_SCAN`，worker 热点在 const-ref / SQLite。
- IDE 启动链：`ApplicationImpl.postInit`、`InitialVfsRefresh`、`clangd` 更活跃，而 Jugg 日志缺少对应重负载信号。
- EDT 锁竞争：`waitCost=`、`TaskRunnerManager lock`、`dispatching to background` 附近有明显延迟。

**口径校验**：
- 以当前代码 + 当前运行日志为准；
- `docs/task` 历史方案用于解释背景，不代表全部已落地；
- 若源码默认值与运行日志不一致，优先怀疑 IDE 中加载的插件产物未更新，或系统属性覆盖。

### 4.2 每次都回退全量 Gradle 编译

**信号**：日志出现 `No file changes. will fallback to gradle compile.`，但用户明确有改文件。

**排查步骤**：
1. 搜 `isNoFileChanges` / `getChangedFilesSinceLastFullCompiled`
2. 确认 `deploy_history.db/` 是否损坏或为空
3. 检查文件变化是否被正确送入 `DeployFileManager.addChangedFile()`

**清理方案**：删除 `build/jugg/database/deploy_history.db/` 后重新全量编译。

### 4.3 APK 数据库初始化慢

**信号**：`database all init finish, cost Xms` 中 X > 3000。

**排查步骤**：
1. 确认 APK 大小（`build/jugg/classpath/apk/`）
2. 搜 `APK size exceeds threshold` 确认是否触发了隔离进程解析
3. 检查 `build/jugg/database/apk/` 下 db 文件大小

### 4.4 release 增量编译后注解类型不匹配 crash

**信号**：runtime crash 报某类 "has no public methods with @Subscribe annotation" 或其他注解查找失败（如 `EventBusException`、Dagger/Hilt 注入失败等注解类型不匹配异常）。

**排查步骤**：
1. **从 crash log 定位涉及的 Activity/类和注解类型**：例如 `MainTabActivity` 和 `@Subscribe`（`org.greenrobot.eventbus.Subscribe`）
2. **在 `mapping.txt` 中搜索注解类名**，确认是否被 R8 混淆：
   - 路径：`build/jugg/classpath/root/.../mapping/release/mapping.txt`
   - 搜索：`org.greenrobot.eventbus.Subscribe`，若找到如 `→ xxx.gkp` 则注解类已被混淆
3. **在编译日志中搜索 `Obfuscated:` 确认重混淆是否执行成功**：
   - 路径：`build/jugg/log/compile_latest.log`
4. **用 `dexdump -a` 对比 staging DEX 和原始 APK DEX 中方法注解**：
   - staging DEX 路径：`build/jugg/build/staging/classes/{package}/{ClassName}.dex`
   - 原始 APK DEX：从 APK 中提取 `classes*.dex`
   - 命令：`~/Library/Android/sdk/build-tools/<version>/dexdump -a <file.dex> | grep -A5 "onMessageEvent"`（替换为目标方法名）
5. **对比注解类型描述符是否一致**：若 staging DEX 中为原始名（如 `Lorg/greenrobot/eventbus/Subscribe;`），而原始 APK DEX 中为混淆名（如 `Lxxx/gkp;`），则确认 `DexObfuscator` 未对注解类型做映射

**根因模式**：`DexObfuscator` 的 visitor 链中未对注解类型名做 `mapType()` 映射。具体表现为 `visitMethod()` 返回的 `DexMethodVisitor` 没有重写 `visitAnnotation()`，或 `visitAnnotation()` 的 `name`（注解类型描述符）未调用 `mapType()`。

**关键类**：
```
main/.../compiler/obfuscation/DexObfuscator.kt     # visitMethod / visitAnnotation
main/.../compiler/obfuscation/DexMinifyCompiler.kt  # 混淆调度
```

### 4.5 release 增量编译后 NoClassDefFoundError（字节码级类型引用未映射）

**信号**：runtime crash 报 `NoClassDefFoundError: Failed resolution of: Lcom/xxx/ClassName;`，且该类**未被增量编译和部署**（日志中不出现该类名）。

**排查步骤**：
1. **确认 crash 中的类名在编译日志中不出现**：搜索日志确认该类未被编译/部署/结构对比
2. **确认被增量编译的类引用了该类**：从 crash 堆栈找到调用方（通常是被增量编译的 Activity/类），查看其源码中是否使用了 `ClassName.class`（触发 `const-class` 指令）、`new ClassName[]`（触发 `filled-new-array`）或 `try { } catch (ClassName e)`（触发 `try-catch` 异常类型引用）
3. **在 `mapping.txt` 中搜索该类名**：确认该类已被 R8 混淆
4. **用 `dexdump -a` 检查 staging DEX 中的字节码**：
   - 搜索 `const-class` 指令，检查其类型引用是否仍为原始名
   - 命令：`dexdump -a <file.dex> | grep -B2 -A2 "const-class"`

**根因模式**：`DexObfuscator` 的 `DexCodeVisitor` 中缺少对特定 visitor 方法的覆写，导致这些方法中的类型引用未经 `mapType()` 映射。dex2jar 不像 ASM 的 `ClassRemapper` 那样自动处理所有类型引用，每个 visitor 方法都需要手动覆写。

**需要覆写的 DexCodeVisitor 方法完整清单**：

| 方法 | 类型引用位置 | 典型 DEX 指令 |
|------|------------|-------------|
| `visitConstStmt(Op, int, Object)` | value 为 DexType 时 | `const-class` |
| `visitFieldStmt(Op, int, int, Field)` | Field 的 owner/type | `iget`/`sput` 等 |
| `visitMethodStmt(Op, int[], Method)` | Method 的 owner/proto | `invoke-*` |
| `visitMethodStmt(Op, int[], Method, Proto)` | invoke-polymorphic | `invoke-polymorphic` |
| `visitMethodStmt(Op, int[], String, Proto, MethodHandle, Object...)` | bsmArgs 中 DexType/Method/Proto | `invoke-custom` |
| `visitFilledNewArrayStmt(Op, int[], String)` | 第三参数为类型描述符 | `filled-new-array` |
| `visitTryCatch(DexLabel, DexLabel, DexLabel[], String[])` | String[] 为异常类型描述符数组 | `.catch` |
| `visitTypeStmt(Op, int, int, String)` | 第四参数为类型描述符 | `new-instance`/`check-cast` |

**关键类**：
```
main/.../compiler/obfuscation/DexObfuscator.kt     # visitCode() 内的 DexCodeVisitor 覆写
main/.../compiler/obfuscation/DexMinifyCompiler.kt  # 混淆调度
```

**对比参考**：`ClassObfuscator.kt` 使用 ASM 的 `ClassRemapper` + `Remapper`，自动处理所有类型引用（LDC Type、ANEWARRAY、CHECKCAST、异常表等），无需逐个覆写。`DexObfuscator` 使用 dex2jar visitor 模式，必须手动覆写每个含类型引用的方法。

### 4.6 release 增量编译后 IllegalAccessError / AbstractMethodError（access flag 不匹配）

**信号**：runtime crash 报 `IllegalAccessError: Method '...' is inaccessible to class 'xxx.xxx'`，或 `AbstractMethodError: abstract method "..."`,且 crash 涉及 `ExternalSyntheticLambda` 类或混淆后的接口调用。

**根因模式**：R8 在全量构建时（启用 `-allowaccessmodification`）会将 `private`/`protected`/`package-private` 成员宽化为 `public`。Jugg 增量编译的 `javac → D8 → DexObfuscator` 链路不做此宽化，导致增量产物中的方法 access flags 与 APK 不一致。

**排查步骤**：
1. **从 crash log 定位涉及的类和方法**：确认是否涉及 lambda 方法（`lambda$...`）或混淆后的接口方法
2. **确认 `-allowaccessmodification` 启用**：检查 `build/intermediates/default_proguard_files/global/proguard-android-optimize.txt*`
3. **用 `dexdump -a` 对比 access flags**：检查增量编译产物中方法的 access flags 是否与 APK 中对应方法一致
4. **确认 `DexObfuscator` 的宽化逻辑是否正确执行**：搜索编译日志中 `Obfuscated:` 确认重混淆已执行

**修复方案**：已通过方案 E' 修复 — `DexObfuscator` 中使用 `widenAccessFlags()` 无条件将所有 `private`/`protected`/`package-private` 成员宽化为 `public`，同时在 `visitMethodStmt()` 中将本类的非 `<init>` 非 `static` 方法的 `invoke-direct` 改为 `invoke-virtual`，保证 direct/virtual 分类一致。

**关键类**：
```
main/.../compiler/obfuscation/DexObfuscator.kt     # widenAccessFlags() + invoke-direct → invoke-virtual
main/.../compiler/obfuscation/DexMinifyCompiler.kt  # 混淆调度
```

**关联文档**：`docs/task/release_incremental_access_flag_mismatch.md`

### 4.7 release 增量编译后 IncompatibleClassChangeError（direct/virtual method 分类不匹配）

**信号**：runtime crash 报 `IncompatibleClassChangeError: The method '...' was expected to be of type direct but instead was found to be of type virtual`，crash 涉及 Jugg 增量部署的类。

**根因模式**：DEX 中方法按 access flags 分为 direct methods（`private` 非 static、`static`、`<init>`）和 virtual methods（`public`/`protected`/`package-private` 非 static）。如果增量编译产物中方法的 access flags 与 APK 中不一致，导致方法从 direct 变为 virtual（或反向），APK 中的调用者（使用 `invoke-direct`）会因 dispatch 类型不匹配而抛出此错误。

**DEX 方法分类规则**：

| 方法类型 | DEX 分类 | 调用指令 |
|---------|----------|---------|
| `private` (非 static) | direct method | `invoke-direct` |
| `private static` / `static` | direct method | `invoke-static` |
| `public`/`protected`/`package-private` (非 static) | virtual method | `invoke-virtual` |

**R8 不总是宽化所有 private 非 static 方法**（三种例外）：
1. 接口中的 private 方法
2. synthetic 方法（编译器生成的合成方法）
3. 命名冲突（继承层级中同签名方法已存在）

**排查步骤**：
1. **从 crash log 定位方法名和类名**：确认是混淆后的名称（如 `J1()`），说明经过 R8 处理
2. **用 `dexdump -a` 检查增量 DEX 中方法是否在 virtual methods section**：若是，但 APK 中相同方法在 direct methods section，则是 access flag 不匹配
3. **确认 `DexObfuscator` 宽化和 invoke 指令修改是否正常执行**

**修复方案**：同 §4.6 — 方案 E' 通过无条件宽化 + `invoke-direct` → `invoke-virtual` 调用指令同步修改解决。宽化后方法统一进入 virtual section，同时增量 DEX 内部的 `invoke-direct` 也同步改为 `invoke-virtual`，保证自洽性。外部 APK 中的调用者不受影响，因为 private 方法天然不可能被外部类直接调用。

**注意**：DEX 的 invoke 指令有两种变体——`invoke-direct`（`INVOKE_DIRECT`，寄存器 ≤ 4 bit）和 `invoke-direct/range`（`INVOKE_DIRECT_RANGE`，高位寄存器）。修复时必须同时处理 `INVOKE_DIRECT_RANGE` → `INVOKE_VIRTUAL_RANGE`。遗漏 range 变体会导致高寄存器场景仍然 crash。

**历史教训**：
- 方案 E（无条件 `private → public` 宽化，但**不改调用指令**）解决了 §4.6 的 IllegalAccessError/AbstractMethodError，但引入了本条的 IncompatibleClassChangeError
- 方案 D（精确对齐 APK access flags）正确但过于复杂
- 方案 E'（宽化 + 改指令）是最终方案——实现简洁且逻辑自洽
- 方案 E' 初版仅处理 `INVOKE_DIRECT`，遗漏 `INVOKE_DIRECT_RANGE`，在方法参数寄存器号较大时仍会触发 IncompatibleClassChangeError

**关键类**：
```
main/.../compiler/obfuscation/DexObfuscator.kt     # access flags 对齐逻辑
main/.../compiler/obfuscation/DexMinifyCompiler.kt  # 混淆调度
main/.../deploy/data/DeployDataDatabase.kt          # APK access flags 数据源
```

**关联文档**：`docs/task/release_incremental_access_flag_mismatch.md` §8, §9

### 4.8 release 增量编译后 AbstractMethodError（类不在 mapping 中，方法名未映射）

**信号**：runtime crash 报 `AbstractMethodError: abstract method "..."` 或 `AbstractMethodError: Ljava/lang/Object;.a()V`，crash 涉及新增类、类名变更后的类、匿名类（编号漂移），或 `ExternalSyntheticLambda` 类。

**根因模式**：当一个类不在 `mapping.txt` 中（新增类、类名变更、编号漂移等），`DexObfuscator.mapMethod()` 查不到该类的方法名映射 → 保留原方法名 → 但 APK 中该方法的接口/父类声明已被混淆（如 `run → a`）→ 方法签名不匹配 → `AbstractMethodError`。

**排查步骤**：
1. **从 crash log 定位涉及的类和方法**：确认该类是否是新增类、匿名类、或 `ExternalSyntheticLambda` 类
2. **确认该类是否在 `mapping.txt` 中**：如果不在，说明旧 mapping 无法覆盖
3. **检查该类实现的接口或继承的父类**：确认接口/父类的方法在 mapping 中有映射
4. **确认增量 DEX 中方法名是否与接口/父类的混淆后方法名一致**

**修复方案**：方案 L — `DexObfuscator` 在 `visitMethod()` 中使用 `mapMethodForCurrentClass()` 实现"接口/父类优先"的方法名映射策略：
1. 优先从接口和父类的 mapping 条目推导方法名
2. 未命中时回退到类自身的 mapping 条目
3. 都未命中时保留原名

该策略对所有类通用，无需区分 lambda 与普通类。

**关键类**：
```
main/.../compiler/obfuscation/DexObfuscator.kt     # mapMethodForCurrentClass() / mapMethodNameFromHierarchy()
```

**关联文档**：`docs/task/release_incremental_access_flag_mismatch.md` §12

---

### 4.9 release 增量编译后 NoSuchMethodError（R8 synthesized qualified method name 未映射）

**信号**：runtime crash 报 `NoSuchMethodError: No static method xxx(...)` in class `Lxxx/...;`，方法名未被混淆（保留了原名如 `listOf`、`mapOf`、`emptyList` 等），调用目标是 Kotlin stdlib facade 类（如 `CollectionsKt`、`MapsKt`、`SequencesKt`）或其他被 R8 synthesized 处理的类。

**根因模式**：R8 在 `mapping.txt` 中为 facade 类生成的 synthesized 方法条目使用 **qualified 原始方法名**（含混淆后的包名前缀），例如：
```
kotlin.collections.CollectionsKt -> xxx.s47:
    1:1:java.util.List xxx.CollectionsKt.listOf(java.lang.Object):0:0 -> e
      # {"id":"com.android.tools.r8.synthesized"}
```
此处 `R8MappingReader` 解析的 `method.originalName` 为 `xxx.CollectionsKt.listOf`（而非简单的 `listOf`）。

`DexObfuscator.init{}` 构建 `methodNameMap` key 时若直接拼接 `classMapping.originalName + "." + method.originalName`，得到：
```
kotlin.collections.CollectionsKt.xxx.CollectionsKt.listOf(java.lang.Object)
```
但 `mapMethod()` 查找时用的 key 是：
```
kotlin.collections.CollectionsKt.listOf(java.lang.Object)
```
两者不匹配 → 方法名映射未命中 → 保留原名 → APK 中方法已被混淆 → `NoSuchMethodError`。

**排查步骤**：
1. **从 crash log 确认调用的方法名是否为原名**（如 `listOf`、`mapOf`、`sorted` 等 Kotlin stdlib 方法）
2. **在 mapping.txt 中搜索该方法**：确认 facade 类下是否有 qualified 形式的 synthesized 条目
3. **确认该方法在 facade 类下被映射为短名**（如 `listOf -> e`）

**修复方案**：在 `DexObfuscator.init{}` 构建 `methodNameMap` 时，使用 `method.originalName.substringAfterLast('.')` 提取简单方法名构建 key。

**关键类**：
```
main/.../compiler/obfuscation/DexObfuscator.kt     # init{} — methodMap key construction
main/.../compiler/obfuscation/R8MappingReader.kt    # convertClassNaming() — method name parsing
```

### 4.10 release 增量编译后 NoSuchMethodError（R8 synthesized 方法参数中间格式未映射）

**信号**：runtime crash 报 `NoSuchMethodError: No static method xxx(...)` in class `Lxxx/...;`，方法名未被混淆，调用目标是 Kotlin stdlib facade 类（如 `RangesKt`、`CollectionsKt`）中带有非原始类型参数的 synthesized 方法。

**根因模式**：R8 synthesized 方法的 mapping 条目中，参数类型可能使用**中间格式**（混淆后包名 + 原始简单类名），而非原始全名或完全混淆名。例如：
```
kotlin.ranges.ClosedRange -> xxx.z07:
kotlin.ranges.RangesKt -> xxx.iul:
    1:1:int xxx.RangesKt.coerceIn(int,xxx.ClosedRange):0:0 -> n
      # {"id":"com.android.tools.r8.synthesized"}
```
此处参数类型 `xxx.ClosedRange` 是中间格式：
- 原始名：`kotlin.ranges.ClosedRange`
- 完全混淆名：`xxx.z07`
- 中间形式：`xxx.ClosedRange`（混淆包名 `xxx` + 原始简单类名 `ClosedRange`）

`DexObfuscator.init{}` 构建 `methodNameMap` key 时直接使用 mapping 中的参数字符串：
```
key = "kotlin.ranges.RangesKt.coerceIn(int,xxx.ClosedRange)"
```
但 `mapMethod()` 查找时从 DEX 中的 proto 构建 key，参数是原始名：
```
key = "kotlin.ranges.RangesKt.coerceIn(int,kotlin.ranges.ClosedRange)"
```
两者参数不匹配 → 方法名映射未命中 → 保留原名 `coerceIn` → APK 中方法实际叫 `n` → `NoSuchMethodError`。

**排查步骤**：
1. **从 crash log 确认调用的方法名是否为原名**（如 `coerceIn`、`rangeTo` 等带对象类型参数的方法）
2. **在 mapping.txt 中搜索该方法**：确认参数中是否包含中间格式的类名（混淆包名 + 原始简单类名）
3. **确认该中间格式类名对应的完全混淆名和原始名**

**修复方案**：在 `DexObfuscator.init{}` 中，先完成 `classNameMap` 构建，再构建**中间格式→原始名**的辅助映射（`intermediateToOriginal`），最后构建 `methodNameMap` 时对每个参数类型通过辅助映射规范化为原始名。具体实现通过 `normalizeMethodParams()` 方法完成。

**关键类**：
```
main/.../compiler/obfuscation/DexObfuscator.kt     # init{} — deferred method processing, normalizeMethodParams()
```

### 4.11 release 增量编译后 NoSuchMethodError（R8 synthesized 方法条目覆盖正常方法映射）

**信号**：runtime crash 报 `NoSuchMethodError: No static method xxx(...)` in class `Lcom/xxx/XxxUtil;`，方法名保留了原名（如 `d`），目标类的类名未被混淆（被 keep 保留）或已正确映射，但方法名未被映射。

**根因模式**：R8 mapping 中同一个类的同一个方法可能同时存在两个条目——正常方法条目和 synthesized 方法条目：
```
com.tencent.component.utils.LogUtil -> com.tencent.component.utils.LogUtil:
    1:10:void d(java.lang.String,java.lang.String):123:132 -> a
    1:1:void com.tencent.component.utils.LogUtil.d(java.lang.String,java.lang.String):0:0 -> d
      # {"id":"com.android.tools.r8.synthesized"}
```
- 正常条目将 `d` 映射为 `a`（真正的重命名）
- Synthesized 条目将 `d` 映射为 `d`（恒等映射，名字未变）

由于 `substringAfterLast('.')` 将 synthesized 的 qualified 名 `com.tencent.component.utils.LogUtil.d` 提取为简单名 `d`，两个条目在 `methodNameMap` 中产生了相同的 key。如果 synthesized 条目在后面处理，其恒等映射 `d → d` 会覆盖正常条目的 `d → a`。

最终增量 DEX 中方法名保留为 `d`，但 APK 中方法已被 R8 重命名为 `a` → `NoSuchMethodError`。

**排查步骤**：
1. **从 crash log 确认方法名是否为短原名**（如 `d`、`e`、`i`——常见的日志工具方法名）
2. **在 mapping.txt 中搜索该方法**：确认是否存在两个条目（正常 + synthesized）
3. **对比两个条目的 obfuscatedName**：如果一个是恒等映射、另一个是真正重命名，说明存在覆盖问题

**修复方案**：在 `DexObfuscator.init{}` 构建 `methodNameMap` 时，当同一 key 已存在映射时，优先保留"真正重命名"的条目（`obfuscatedName != simpleMethodName`），不允许恒等映射（`obfuscatedName == simpleMethodName`）覆盖真正重命名。

**关键类**：
```
main/.../compiler/obfuscation/DexObfuscator.kt     # init{} — methodMap deduplication logic
```

---

## 5. 排查前：保存现场

**在任何操作前先备份**，避免复现步骤覆盖原始日志：

```bash
BACKUP=~/Desktop/jugg_debug_$(date +%Y%m%d_%H%M%S)
mkdir -p $BACKUP
cp -r  {projectDir}/build/jugg/log/          $BACKUP/log/
cp -r  {projectDir}/build/jugg/database/     $BACKUP/database/
```

> `compile_*.log` 是主日志文件。
> `compile_latest.log` / `compile_latest-1.log` 仅为 best-effort 快捷入口，创建失败时可能不存在。

提交 Bug 时需附带的文件：

| 文件 | 路径 | 备注 |
|------|------|------|
| 运行日志 | `build/jugg/log/compile_latest.log` | 快捷入口；若不存在则改传最新的 `compile_*.log` |
| IDE 主日志 | `idea.log` | 用于对齐 `uiFreezeStarted`、`InvocationEvent has timed out`、`postInit` 等信号 |
| freeze thread dump | `threadDumps-freeze-*` | UI freeze 时 IDE 自动抓取的线程快照 |
| 现场线程栈 | `jcmd <pid> Thread.print -l` 输出 | 补足自动 dump 之外的即时线程状态 |
| 项目信息 | `build/jugg/database/project_infos.db/project_infos.json` | |
| APK 数据库 | `build/jugg/database/apk/*.db` | DB 状态相关问题 |
| 部署历史 | `build/jugg/database/deploy_history.db/` | 增量状态相关 |

---

## 6. TDD 修复流程

**原则：先复现，再修改代码。**

### Step 1：稳定复现（写测试，确认 FAIL）

根据日志定位问题后，先写测试使其 FAIL，再动手改代码：

```kotlin
// 示例：验证 EDT 调用 addChangedFile 不阻塞
@Test
fun `addChangedFile on EDT should dispatch async and return immediately`() {
    val elapsed = AtomicLong()
    SwingUtilities.invokeAndWait {
        val t0 = System.currentTimeMillis()
        deployFileManager.addChangedFile(listOf(fakeChangedFile))
        elapsed.set(System.currentTimeMillis() - t0)
    }
    assertTrue("expected <10ms, got ${elapsed.get()}ms", elapsed.get() < 10)
}
```

运行：`./gradlew :main:test --tests "*YourTest*"`，确认 **FAIL**。

### Step 2：实现修复

- 最小化改动
- 修改点保留诊断日志（便于线上验证）
- 并发修改需注明锁范围和线程假设

### Step 3：验证

```bash
# 单测验证
./gradlew :main:test :idea:test

# 打包
./gradlew :idea:buildPlugin

# 线上验证：复现步骤后搜索修复标志
grep "dispatching to background" build/jugg/log/compile_latest.log
```
