# 插件运行时问题排查手册

> 最后核对：2026-05-23
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 0. AI 读取本文档时的自动行动清单

收到"排查问题"类请求且含有日志片段时，**按顺序执行**，无需等待用户追问：

1. **定位日志时间区间**：从用户提供的日志片段找到问题时间戳（精确到毫秒）。
2. **读取完整上下文**：在日志中向上/向下各扩展 50~100 行，确认前后调用链。
3. **检索关键词**：用下表"常用搜索词"在日志中定位锁等待、EDT 阻塞、耗时超标等信号。
4. **对照代码**：根据日志中的 `[ClassName]` 标签，优先用 android-studio-index MCP 跳转到对应类；调用时必须带项目目录参数。
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
│   │   └── gradle_project_infos.json
│   ├── compile_context.db/            # classpath、模块信息
│   │   ├── full_build_info.json        # Gradle full build 命令、BuildTarget、写入时间
│   └── deploy_history.db/             # 部署历史（增量恢复）
├── classpath/
│   ├── root/                          # classpath jar
│   ├── apk/                           # APK 文件缓存
│   └── libraries/                     # 依赖库备份
├── config/
│   ├── custom_compilers/
│   ├── agent_setup.md
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
| ConstRef 降级 | `fallback to no-op const-ref` |
| IDE 启动链 | `InitialVfsRefresh` / `postInit` / `clangd` |
| 重混淆结果 | `Obfuscated:` |
| 重混淆注解问题 | `visitAnnotation` / `mapType` |
| 重混淆类型引用遗漏 | `const-class` / `filled-new-array` / `NoClassDefFoundError` |
| 重混淆 access flag 宽化 | `widenAccessFlags` / `invoke-direct` / `IllegalAccessError` / `AbstractMethodError` / `IncompatibleClassChangeError` / `ExternalSyntheticLambda` |
| Jugg Debug attach | `Jugg Debug attach:` / `waitForClientReadyForDebug` / `Debugger is waiting for application to start` / `Connected to the target VM` |

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

### 4.1.2 ConstRef SQLite 缓存损坏

**信号**：`idea.log` 或插件初始化栈出现 `SQLITE_CORRUPT` / `SQLITE_NOTADB` / `database disk image is malformed`，类名落在 `ConstRefCacheDatabase.ensureSchema()`、`ConstRefCacheDatabase.init()`、`DeployFileManager.<init>` 或 `JuggManager.<init>` 附近。

**当前期望行为**：
- `DeployFileManager` 可直接创建 `ConstRefEngine`，但 `ConstRefEngine` 构造不应初始化 SQLite runtime；`JuggManager.<init>` 不应因 ConstRef DB 异常失败。
- `ConstRefCacheDatabase` 初始化遇到损坏库时会重建 `~/.jugg/const_ref/const_ref_shared.db` 及其 WAL/SHM。
- 若 DB 重建或 `RepoSharedFingerprintStore` 初始化仍失败，日志应出现 `fallback to no-op const-ref`，后续编译/部署按无 ConstRef 继续。

**人工恢复**：若仍因文件权限或磁盘状态导致无法重建，可关闭 IDE 后删除 `~/.jugg/const_ref/const_ref_shared.db*` 和 `~/.jugg/const_ref/repo_fingerprint.db*`，重新打开项目。

### 4.1.3 Jugg Debug 断点不生效

**信号**：Jugg Debug 启动后 App 显示等待/已 attach，但断点不打勾或点击后不 suspend；原生 Android Studio Debug / Attach 可以命中同一断点。

**排查步骤**：
1. 在 `build/jugg/log/compile_latest.log` 搜 `Jugg Debug attach:`，确认 Jugg 进入 attach 阶段。
2. 在 Android Studio `idea.log` 同一时间窗搜 `waitForClientReadyForDebug`，确认是否出现：
   - `Waiting for clients [<package>] for 15 seconds`
   - `Found process <package>. Waiting for it to be debuggable.`
   - `<package> is now debuggable.`
3. 继续搜 `Debugger is waiting for application to start` 与 `Connected to the target VM`。
4. 若只有 `Debugger is waiting for application to start`，没有 `Connected to the target VM`，说明 Java debugger session 未完成 VM 连接，断点不会生效。

**期望顺序**：
1. Jugg 主日志：`Jugg Debug attach: waiting for <package> to enter debugger WAITING state.`
2. `idea.log`：`waitForClientReadyForDebug - Waiting for clients [<package>] for 15 seconds`
3. `idea.log`：`waitForClientReadyForDebug - Found process <package>. Waiting for it to be debuggable.`
4. `idea.log`：`waitForClientReadyForDebug - <package> is now debuggable.`
5. `idea.log`：`Connecting to the target VM`
6. `idea.log`：`Debugger is waiting for application to start`
7. `idea.log`：`Connected to the target VM`
8. Jugg 主日志：`Jugg Debug attach: Android Studio Java debugger session created for <package>.`

**关键类**：
```
idea/.../ide/logic/JuggDebugSessionManager.kt
deploy_compat/interface/.../AndroidDebugClientReadyWaiter.kt
deploy_compat/interface/.../JavaDebuggerSessionStarter.kt
deploy_compat/v_giraffe/.../GiraffeAsDeployerCompat.kt
deploy_compat/v_quail/.../QuailAsDeployerCompat.kt
```

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

### 4.6 release 增量编译后 IllegalAccessError / AbstractMethodError / IncompatibleClassChangeError（access flag 不匹配）

**信号**：runtime crash 报以下之一：
- `IllegalAccessError: Method '...' is inaccessible to class 'xxx'`
- `AbstractMethodError: abstract method "..."`（涉及 `ExternalSyntheticLambda` 或混淆后接口调用）
- `IncompatibleClassChangeError: The method '...' was expected to be of type direct but instead was found to be of type virtual`

**根因模式**：R8 启用 `-allowaccessmodification` 时会宽化 `private`/`protected`/`package-private` → `public`。Jugg 增量链路（`javac → D8 → DexObfuscator`）不做此宽化，导致增量产物 access flags 与 APK 不一致。具体表现为：方法可见性不足（IllegalAccessError）、方法从 direct section 移到 virtual section 导致 dispatch 类型不匹配（IncompatibleClassChangeError）。

**排查步骤**：
1. 从 crash log 定位类和方法，确认是否涉及 lambda（`lambda$...`）或混淆后接口方法
2. 用 `dexdump -a` 对比增量 DEX 和 APK DEX 中方法的 access flags 及 direct/virtual section 分类
3. 确认 `DexObfuscator` 宽化和 invoke 指令修改是否正常执行（搜日志 `Obfuscated:`）

**修复方案（方案 E'）**：
- `widenAccessFlags()` 无条件将所有非 public 成员宽化为 `public`
- `visitMethodStmt()` 中将本类非 `<init>` 非 `static` 方法的 `invoke-direct` → `invoke-virtual`（含 `INVOKE_DIRECT_RANGE` → `INVOKE_VIRTUAL_RANGE`）
- 外部 APK 中 private 方法天然不可能被外部类直接调用，宽化安全

**关键类**：`DexObfuscator.kt`（`widenAccessFlags()` + `visitMethodStmt()`）

**关联文档**：`docs/task/release_incremental_access_flag_mismatch.md`

### 4.7 release 增量编译后 AbstractMethodError（类不在 mapping 中，方法名未映射）

**信号**：`AbstractMethodError`，涉及新增类、匿名类（编号漂移）、`ExternalSyntheticLambda` 类。

**根因模式**：类不在 `mapping.txt` 中 → `mapMethod()` 查不到方法名映射 → 保留原方法名 → 但 APK 中接口/父类方法已被混淆 → 签名不匹配。典型场景：D8 `ExternalSyntheticLambda` 编号漂移后，mapping 条目对应的是旧编号的语义类。

**修复方案（方案 L）**：`DexObfuscator.mapMethodForCurrentClass()` 采用"接口/父类优先"策略：
1. 先从接口和父类的 mapping 条目推导方法名
2. 未命中时回退到类自身查找
3. 都未命中时保留原名

对所有类通用，无需区分 lambda 与普通类。

**关键类**：`DexObfuscator.kt`（`mapMethodForCurrentClass()` / `mapMethodNameFromHierarchy()`）

**关联文档**：`docs/task/release_incremental_access_flag_mismatch.md` §12

---

### 4.8 release 增量编译后 NoSuchMethodError（R8 synthesized 方法映射问题）

**信号**：runtime crash 报 `NoSuchMethodError: No static method xxx(...)` in class `Lxxx/...;`，方法名保留了原名（未被混淆），调用目标是 Kotlin stdlib facade 类（`CollectionsKt`、`RangesKt` 等）或 keep 类。

**根因模式**：`DexObfuscator.init{}` 构建 `methodNameMap` 时，R8 synthesized 方法条目存在三种键不匹配：

| 子问题 | 现象 | 原因 | 修复 |
|--------|------|------|------|
| qualified 方法名 | facade 类方法名未映射（如 `listOf` → 保留原名） | synthesized 条目的 `originalName` 含混淆包名前缀（`xxx.CollectionsKt.listOf`），key 拼接后与查找键不匹配 | `method.originalName.substringAfterLast('.')` 提取简单名 |
| 参数中间格式 | 带对象类型参数的方法未映射（如 `coerceIn(int,xxx.ClosedRange)`） | synthesized 条目参数使用"混淆包名 + 原始简单类名"中间格式，与 DEX 中原始全名不匹配 | 构建 `intermediateToOriginal` 辅助映射，`normalizeMethodParams()` 规范化参数 |
| 恒等映射覆盖 | keep 类方法名未映射（如 `d` → 保留为 `d`，但 APK 中为 `a`） | 同一方法同时有正常条目（`d→a`）和 synthesized 条目（`d→d`），恒等映射覆盖了真正重命名 | 优先保留 `obfuscatedName != simpleMethodName` 的条目 |

**排查步骤**：
1. 从 crash log 确认方法名是否为原名（未混淆）
2. 在 mapping.txt 中搜索该方法，确认是否存在 synthesized 条目（含 `com.android.tools.r8.synthesized` 注释）
3. 对比 mapping 条目的原始名格式和参数格式

**关键类**：`DexObfuscator.kt`（`init{}` — methodNameMap 构建逻辑）

---

### 4.9 JDK 25+ 宿主上 Kotlin 编译 INTERNAL_ERROR（shaded JavaVersion 解析失败）

**信号**：日志出现 `kotlin compile result code: INTERNAL_ERROR`，且向上可见：

```
[KotlinCompiler] exception: java.lang.IllegalArgumentException: 25.0.3
	at org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse(JavaVersion.java:...)
	at org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem...
```

recreate compiler 重试同样失败（根因是宿主环境，与编译器实例无关）。

**根因模式**：Jugg 进程内调用 Kotlin 编译器（`K2JVMCompilerIsolate`），宿主 = IDE 的 JVM。
< 2.1.20 的编译器 shaded `JavaVersion.parse` 写死接受上限 25（上游 Kotlin 2.1.20 才修复），
IDE 运行在 JDK 25+ 时 `JavaVersion.current()` 解析宿主版本号直接抛 IAE。
触发点在 `CoreJrtFileSystem` 把宿主 JDK 挂载为编译输入的初始化代码中。

**修复机制**（`KotlinCompilerHostCompat`，两层叠加）：
1. classloader 创建后探测 shaded `JavaVersion.current()`，失败则用 `compose(宿主 feature)` 反射预置
   `current` 缓存字段，绕过坏掉的字符串解析；探测通过（>= 2.1.20 或 JDK <= 24）则零侵入。
2. 宿主 JDK >= 25 且 classpath 含 android.jar 时给编译命令追加 `-no-jdk`（对齐 AGP/KGP 行为，
   `java.*` 由 android.jar 提供，不再挂载宿主 JDK）。

**验证**：搜日志 `preset shaded JavaVersion.current to` / `add -no-jdk`。

**关键类**：
```
main/.../compiler/source/kotlin/KotlinCompilerHostCompat.kt
main/.../compiler/source/kotlin/K2JVMCompilerIsolate.kt    # 三处 classloader 创建点接入
main/.../compiler/source/kotlin/KotlinCompilerInvoker.kt   # -no-jdk 追加
```

---

### 4.10 Windows 命令中文输出乱码

**信号**：Android Studio Build 窗口中文正常，但 Jugg 输出出现 `璀﹀憡`、`娉�`、`鏌愪簺杈撳叆鏂囦欢` 等文本。

**根因模式**：Windows 同一命令管道中的不同进程可能分别输出 UTF-8 或 GBK 字节。若 Jugg 在读取原始字节前固定使用其中一种编码，另一种输出就会乱码；出现 `�` 时部分原始字节已丢失，不能通过切换日志查看器编码恢复。

**当前期望行为**：
- `CmdExecutor` 的 stdout / stderr 都先按行保留原始字节。
- Windows 下每行严格校验 UTF-8；校验成功按 UTF-8 解码，失败回退 GBK。
- 非 Windows 继续固定使用 UTF-8。
- 编码按行判断，不锁定整个进程，以兼容同一管道内的混合编码输出。

**关键类**：
```
main/.../gradle/compile/CmdExecutor.kt
main/.../gradle/compile/ProcessOutputReader.kt
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
# 定向测试验证；按 06_testing.md 选择 L1/L2/L3，不跑无过滤的全量 :main:test / :idea:test
./gradlew :main:test --tests "*YourTest*"
./gradlew :idea:test --tests "*YourFlowTest*"

# 打包
./gradlew :idea:buildPlugin

# 线上验证：复现步骤后搜索修复标志
grep "dispatching to background" build/jugg/log/compile_latest.log
```
