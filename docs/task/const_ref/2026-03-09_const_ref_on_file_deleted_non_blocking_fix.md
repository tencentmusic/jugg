# ConstRefEngine 删除路径非阻塞修复记录

- 日期：2026-03-09
- 目标：避免 `onFileDeleted` 在调用线程（尤其 EDT）上等待 const-ref 锁或数据库锁，导致 Android Studio 卡死。

## 1. 触发链路

freeze dump 显示：

1. `AWT-EventQueue-0 -> DeployFileManager.removeChangedFile -> ConstRefEngine.onFileDeleted`
2. `onFileDeleted` 同步路径等待锁；
3. 后台线程在 `NativeDB.step / queryLatestDefinitionsByWhere` 长时间执行，导致前台等待放大为 UI freeze。

## 2. 本次代码修复

文件：`main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngine.kt`

1. `onFileDeleted` 改为轻量同步路径：
   - 立即更新内存状态（pending/current/analyzedAt）；
   - 立即更新 `changeTracker`；
   - 立即清理 `sessionCache`；
   - 不再在调用线程执行数据库删除。
2. 新增删除清理队列：
   - 使用 `pendingDeleteCleanupPaths` 聚合删除路径；
   - 使用单个 `deleteCleanupJob` 在 `Dispatchers.IO` 后台串行执行 DB 删除；
   - 覆盖 `removeFile` + `removeFilesByPrefix`。
3. 移除 `withAnalysisLockBlocking` 在删除路径上的使用，避免调用线程 `runBlocking` 等待。

## 3. 测试与验证

文件：`main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngineTest.kt`

- 新增测试：`onFileDeleted should not block when database monitor is busy`
  - 人为占用 `ConstRefCacheDatabase` monitor；
  - 断言 `onFileDeleted` 调用耗时应在短阈值内。

## 4. 文档同步

- 已更新 `docs/ai_knowledge/03_deploy_const_ref.md` 中 `onFileDeleted(path)` 行为说明：
  - 补充“数据库删除改为后台队列异步执行，避免在调用线程等待 DB 锁”。

