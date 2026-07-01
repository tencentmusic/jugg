# ConstRef Windows SQLite Lock 优化方案

## 背景

用户日志中出现大量：

```text
org.sqlite.SQLiteException: [SQLITE_BUSY] The database file is locked (database is locked)
```

堆栈集中在：

```text
ConstRefEngine.enqueueDeleteCleanup()
  -> ConstRefCacheDatabase.removeFilesByPrefix()
```

触发路径大量来自 `build/intermediates/**/*.class`、`build/tmp/kotlin-classes/**/*.class`。这些并不是 ConstRef 需要分析的源码文件，而是 IDE VFS 删除事件直接进入 `DeployFileManager.removeChangedFile()` 后，被 `ConstRefEngine.onFileDeleted()` 当作 prefix cleanup 路径处理。

ConstRef 全局 DB 位于用户目录：

```text
~/.jugg/const_ref/const_ref_shared.db
```

同一个 Android Studio 打开多个 Project 时，每个 Project 会创建独立 `JuggManager -> DeployFileManager -> ConstRefEngine -> ConstRefCacheDatabase`。当前 `ConstRefCacheDatabase` 只保证单实例内 `@Synchronized`，不能保证同一 IDE 进程内多个 Project 写同一个全局 DB 时串行。

## 目标

1. 减少无意义的 ConstRef cleanup 写事务，尤其是 Windows 上高频 build output 删除事件。
2. 保证同一 IDE 进程内按 DB path 单 writer 写入全局 ConstRef DB。
3. 遇到 `SQLITE_BUSY` 时不丢 cleanup 任务，使用可控重试与重新入队。
4. 增加锁竞争诊断信息，能定位 DB path、Project、操作、耗时和 retry。
5. 不改变 ConstRef 对主编译/部署链路的降级语义：cleanup 失败只 warning，不阻断 Run / compile / deploy。

## 非目标

- 不把 ConstRef DB 从全局共享改为 project-local DB。
- 不要求每次写完关闭 SQLite connection。
- 不引入会阻塞用户编译主链路的大锁等待。
- 不改变 ConstRef parser、impact resolver 的匹配语义。

## 根因拆解

### 1. `.class` 删除事件为什么会触发 DB 写

当前链路：

```text
FileChangesDetector.notifyFileChanges()
  -> VFileDeleteEvent 加入 deletedFiles
JuggManager.processFileChanged()
  -> deletedFiles 直接传给 deployFileManager.removeChangedFile()
DeployFileManager.removeChangedFile()
  -> constRefEngine.onFileDeleted(path)
ConstRefEngine.onFileDeleted()
  -> enqueueDeleteCleanup(path)
ConstRefEngine.enqueueDeleteCleanup()
  -> database.removeFilesByPrefix("$path/")
```

`changedFiles` 会经过 `fileChangesHandler.filter()` 后再进入增量编译状态；但 `deletedFiles` 当前没有等价过滤。`JuggManager.processFileChanged()` 里对 deleted files 的 `simpleFilterFiles` 只用于 debug 打印，不影响后续处理。

### 2. 同一 Android Studio 多 Project 是否多 writer

会。`JuggInitializer.instanceSet` 按 project path 持有多个 `JuggLoader`，每个 Project 都有独立 `JuggManager` 和独立 `ConstRefCacheDatabase` 实例，但 DB path 都是 `~/.jugg/const_ref/const_ref_shared.db`。

单个 `ConstRefCacheDatabase` 的 `@Synchronized` 只能串行该实例内的方法。多 Project、多实例、多 connection 写同一个 SQLite 文件时，仍依赖 SQLite 文件锁协调。

### 3. 写锁是否一直占用

正常情况下不会。`sharedConnection` 长期持有不等于长期持有写锁；写锁通常只在 write statement / transaction 执行期间持有。

但在 Windows 上，以下情况会让外部观察到持续 `SQLITE_BUSY`：

- 多 Project 的 ConstRef full scan / cleanup 连续写同一个全局 DB。
- 高频 build output 删除事件造成 cleanup 写事务密集排队。
- 大 prefix cleanup 或 checkpoint/vacuum 持锁较久。
- 另一个 Android Studio/Jugg 进程写同一 DB。
- Windows Defender、同步盘、文件索引器干扰 SQLite WAL/SHM/DB 文件句柄。

## 方案分层

### Phase 1：ConstRef 删除事件过滤

优先级最高，收益最大，风险最低。

改动点：

- 在 `ConstRefEngine.onFileDeleted()` 前或内部增加过滤。
- 只允许以下路径进入 ConstRef cleanup：
  - `.java`
  - `.kt`
  - 真实源码目录
- 明确跳过：
  - `build/`
  - `.gradle/`
  - `.idea/`
  - `build/intermediates/`
  - `build/tmp/kotlin-classes/`
  - `build/generated/` 中非源码产物

建议实现：

```text
DeployFileManager.removeChangedFile()
  -> sourceFileManager.updateFiles() 可继续接收删除列表
  -> ConstRef 侧只处理 mayAffectConstRefIndex(path)
```

`mayAffectConstRefIndex(path)` 需要基于 `moduleInfos.sourceDirs` 判断目录是否在源码根内。文件不存在时不能只靠 `File.isDirectory`，需要从路径前缀判断。

预期效果：

- `.class` 删除不再触发 `removeFilesByPrefix()`。
- Windows 高频构建产物删除事件不再放大 SQLite 写锁竞争。

测试落点：

- `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngineTest.kt`（L1）
  - 删除 `.class` 不调用 DB cleanup。
  - 删除源码文件仍清理单文件索引。
  - 删除源码目录仍触发 prefix cleanup。

### Phase 2：cleanup `SQLITE_BUSY` 重试与重新入队

当前 cleanup catch 后只 warning，路径会丢失。优化为 best-effort 可靠清理。

改动点：

- 捕获 SQLite busy / locked 类异常时，执行有限重试。
- 重试仍失败时重新入队，延迟下一轮后台处理。
- 同一路径 prefix 去重，避免重复写。
- 增加最大 retry 次数和最大队列大小，防止异常场景无限积压。

建议策略：

```text
initialDelayMs = 200
maxDelayMs = 5000
maxAttemptPerPath = 3
requeueAfterMs = 30000
```

非 busy 异常仍按现有语义 warning，不做无限重试。

测试落点：

- `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngineTest.kt`（L1）
  - busy 后重试成功。
  - busy 超过上限后重新入队。
  - 非 busy 异常不无限重试。

### Phase 3：同一 IDE 进程内 DB path 单 writer

目标是解决多 Project、多 `ConstRefCacheDatabase` 实例写同一全局 DB 的进程内竞争。

建议新增一个进程内注册表：

```text
ConstRefDatabaseRegistry
  getOrCreate(dbFile, logger): SharedConstRefDatabaseHandle
```

设计要点：

- key 使用 canonical db path。
- 同一 db path 只创建一个底层 `ConstRefCacheDatabase` writer。
- 写操作通过单线程 dispatcher 或 mutex 串行。
- handle 使用引用计数，Project dispose 后 release。
- read 操作可先全部走同一个 handle，避免读写交错复杂化；后续确认性能瓶颈再区分 read/write。

注意：

- 不建议每次写完 close connection。close/open 会增加成本，也不能解决多实例并发写。
- `ConstRefCacheDatabase` 现有 `@Synchronized` 可以保留，但注册表提供更高层的跨实例串行。

测试落点：

- `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabaseTest.kt`（L1）
  - 同 path 获取同一 writer handle。
  - 不同 path 获取不同 handle。
  - release 引用计数归零后 close。

### Phase 4：跨进程 sidecar lock

用于多个 Android Studio/Jugg 进程同时写 `~/.jugg/const_ref/const_ref_shared.db` 的情况。

建议使用 sidecar lock 文件：

```text
~/.jugg/const_ref/const_ref_shared.db.write.lock
```

不要锁 SQLite 主 DB 文件，避免干扰 SQLite 自身锁协议。

实现策略：

- 写事务、checkpoint、vacuum 前尝试 `FileChannel.tryLock()`。
- 获取失败时短退避，不长时间阻塞主链路。
- cleanup 失败可重新入队。
- pre-compile / on-demand 等用户等待链路需要更短超时，并允许降级。

风险：

- `FileChannel.lock()` 如果用阻塞形式，可能引入新卡顿。
- Windows 上进程异常退出后 OS 会释放文件锁，但 sidecar 文件会残留；不能把文件存在视为锁状态，只能依赖 OS lock。

测试落点：

- `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabaseTest.kt`（L1）
  - lock 获取失败时返回 busy-like 结果。
  - release 后后续写入可继续。

### Phase 5：锁诊断日志

新增低噪音 debug 日志，busy 时提升到 warn。

建议字段：

```text
dbPath
operation
projectPath
thread
pid
attempt
waitMs
transactionMs
queueSize
path/prefix
```

示例：

```text
ConstRef DB write busy, db=..., op=deletePrefix, project=..., attempt=2, waitMs=5000, prefix=...
```

收益：

- 能判断是同 Project 连续写、同 IDE 多 Project 写，还是跨进程写。
- 能识别大事务和 checkpoint/vacuum 导致的锁等待。

## 推荐实施顺序

1. Phase 1：过滤非 ConstRef 相关删除事件。
2. Phase 2：cleanup busy 重试与重新入队。
3. Phase 5：补诊断日志，便于验证线上效果。
4. Phase 3：同 IDE 进程内单 writer。
5. Phase 4：跨进程 sidecar lock。

Phase 1 + Phase 2 应该能解决大部分日志刷屏问题；Phase 3/4 用于处理多 Project / 多 IDE 的长期稳定性。

## 风险与兼容

- 过滤删除事件时不能漏掉源码目录删除，否则可能留下旧 const definition / reference 索引。
- cleanup 重新入队必须有上限和去重，避免队列膨胀。
- 单 writer registry 需要处理 Project dispose，避免 DB connection 泄漏。
- sidecar lock 必须避免阻塞 EDT 和用户主链路。
- 保持 ConstRef 可选能力语义：运行期异常只影响当前 ConstRef 操作，不阻断编译部署。

## 验证清单

### 自动化测试

- `ConstRefEngineTest`（L1）
  - 非源码 `.class` 删除不触发 DB cleanup。
  - 源码文件删除仍清理。
  - 源码目录删除仍 prefix cleanup。
  - busy cleanup 重试 / 重新入队。
- `ConstRefCacheDatabaseTest`（L1）
  - DB path registry 单例 writer。
  - sidecar lock 获取 / 释放。

### 手工验证

1. Windows 打开单 Project，执行 Gradle clean / rebuild，确认 `build/intermediates/**/*.class` 删除不再触发 ConstRef cleanup warning。
2. Windows 同一 Android Studio 打开两个 Project，分别触发 ConstRef full scan / cleanup，确认不再出现密集 `SQLITE_BUSY`。
3. Windows 打开两个 Android Studio 实例，确认 sidecar lock 下 busy 会延期重试，不阻断编译部署。
4. 检查 `compile_latest.log`，确认 busy 日志包含 db path、project path、operation、attempt、waitMs。

## Rollback 方案

- Phase 1 可通过保留旧 cleanup 入口回退。
- Phase 2 可关闭重新入队，仅保留 warning。
- Phase 3 可让 registry 每次返回独立实例，恢复旧行为。
- Phase 4 可通过系统属性禁用 sidecar lock。

建议预留开关：

```text
jugg.constref.cleanup.filter.enabled=true
jugg.constref.cleanup.retry.enabled=true
jugg.constref.db.registry.enabled=true
jugg.constref.db.sidecar.lock.enabled=false
```

sidecar lock 建议默认先关闭，通过灰度或问题用户定向开启；确认无副作用后再默认开启。
