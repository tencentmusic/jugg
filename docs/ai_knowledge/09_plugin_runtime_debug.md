# 插件运行时问题排查手册

> 最后核对：2026-03-23
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
