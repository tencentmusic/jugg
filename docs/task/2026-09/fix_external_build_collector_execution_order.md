# 修复 Configuration on Demand 下 C++ 增量收集任务提前执行方案

创建日期：2026-09-17。状态：待实施。

---

## 1. 问题背景与现场证据

### 1.1 现场现象
在 Jugg report `584a6a17` 中，用户修改了 C++ 源码并增加日志，期望通过 Jugg 增量编译部署生效，但实际运行发现改动未生效。

### 1.2 日志时间戳证据（`compile_2026-09-17_11-23-16.0.log`）
分析 Jugg 增量编译日志中的任务调度与执行时间：
1. **11:41:39.606**：执行 ExternalBuildCompiler，打印：
   ```text
   External build command: ./gradlew :mp:dtmp:mergeDebugNativeLibs :mp:appcommon:mergeDebugNativeLibs :juggCollectExternalBuildInfo ...
   Configuration on demand is an incubating feature.
   ```
2. **11:41:44.602 ~ 11:41:48.404**：`:juggCollectExternalBuildInfo` 已经启动并执行完毕！
   ```text
   Jugg: start stripped output for :mp:dtmp:mergeDebugNativeLibs
   Jugg: stripped output for :mp:dtmp:mergeDebugNativeLibs into ...
   Jugg: start stripped output for :mp:appcommon:mergeDebugNativeLibs
   Jugg: stripped output for :mp:appcommon:mergeDebugNativeLibs into ...
   ```
   此时收集器剥离并记录的是构建开始前文件系统上残留的**旧 `.so` 产物**。
3. **11:41:47.304 ~ 11:42:12.802**：`:mp:appcommon:buildCMakeDebug`（CMake 编译与链接）才在另一个 Worker 线程中进行，并在 11:42:12 链接出新的 `.so`。
4. **11:42:18.501**：`:mp:appcommon:mergeDebugNativeLibs` 才最终完成。

**直接矛盾**：本应在 `mergeDebugNativeLibs` 完成后才运行的 `:juggCollectExternalBuildInfo`，在真正的 C++ 编译和合并完成前 30 秒就提前运行并结束了！Jugg 最终部署了旧 `.so`，导致用户修改失效。

---

## 2. 根因剖析

### 2.1 不是 `localTasks` 为空
代码在 `juggCollectExternalBuildInfo` 执行时输出了：
```text
Jugg: start stripped output for :mp:dtmp:mergeDebugNativeLibs
Jugg: start stripped output for :mp:appcommon:mergeDebugNativeLibs
```
证明 `readExternalBuildInfoRequests()` 解析正常，模块匹配正确，`localTasks` 包含且仅包含这两个待执行的 module merge task。

### 2.2 真正的根因：Configuration on Demand (COD) 下的生命周期顺序缺陷
在 `readProjectInfo.gradle.kts` 中，原有依赖注入放置在 `gradle.projectsEvaluated` 中：
```kotlin
gradle.projectsEvaluated {
    val projectInfoReaderManager = gradleProjectInfoReaderManager(rootProject, gradle.includedBuilds)
    projectInfoReaderManager.configureExternalBuildInfoCollector()
}
```
内部实现为：
```kotlin
fun configureExternalBuildInfoCollector() {
    val collector = rootProject.tasks.maybeCreate(COLLECT_EXTERNAL_BUILD_INFO_TASK_NAME)
    val localTasks = readExternalBuildInfoRequests().mapNotNull { request ->
        rootProject.allprojects.firstOrNull {
            it.projectDir.absoluteFile.normalize() == request.moduleRootDir.absoluteFile.normalize()
        }?.tasks?.findByPath(request.taskPath)
    }
    if (localTasks.isNotEmpty()) {
        collector.mustRunAfter(localTasks)
    }
}
```

原作者之所以选择在 `projectsEvaluated` 中执行，是因为在配置早期，子工程尚未完成配置，调用 `project.tasks.findByPath(...)` 会返回 `null`。

但在开启 `org.gradle.configureondemand=true` 时，Gradle 的生命周期发生了本质变化：
1. **Task Graph 计算提前挑选任务**：Gradle 为了按需配置，在最开始就解析了命令行直接指定的 Primary Tasks（`:mp:appcommon:mergeDebugNativeLibs` 与 `:juggCollectExternalBuildInfo`），并将它们加入初始 Execution Plan；
2. **`projectsEvaluated` 触发过晚**：直到任务图骨架已生成完毕后，Gradle 才触发 `projectsEvaluated`。
3. **`mustRunAfter` 无法动态补入已定型的调度节点**：在 `projectsEvaluated` 中后追加的 `collector.mustRunAfter(localTasks)`，不会促使 Gradle 重新为已经进入执行队列的跨工程 Task 重建软排序等待；
4. **并行构建（`org.gradle.parallel=true`）引发抢跑**：Gradle Worker 1 领取了 `buildCMakeDebug`（长耗时 C++ 编译）；Worker 2 空闲，且发现 `:juggCollectExternalBuildInfo` 没有未满足的硬依赖（`dependsOn`），立即将其调度执行。

### 2.3 本地对照复现证据（Gradle 8.11.1）
在本地构造相同依赖关系的最小工程复现：
- **`COD=false` + `projectsEvaluated`**：顺序为 `buildCMakeDebug` $\rightarrow$ `mergeDebugNativeLibs` $\rightarrow$ `collector`（正常）。
- **`COD=true` + `projectsEvaluated`**：顺序为 `buildCMakeDebug` $\rightarrow$ `collector` $\rightarrow$ `mergeDebugNativeLibs`（**100% 复现线上抢跑 Bug**）。
- **`COD=true` + 在 `gradle.rootProject` 中使用 taskPath 路径声明 `mustRunAfter`**：顺序为 `buildCMakeDebug` $\rightarrow$ `mergeDebugNativeLibs` $\rightarrow$ `collector`（恢复正常）。

---

## 3. 修复方案设计（奥卡姆剃刀与最小修复）

### 3.1 核心思路
打破原有的“必须等待项目评估完毕才能获取 Task 对象”的假象：
- Gradle 的 `Task.mustRunAfter(...)`（以及 `dependsOn`）原生支持接受**任务路径字符串**（如 `":mp:appcommon:mergeDebugNativeLibs"`）。
- 在 Gradle 中，字符串形式的任务路径是在 Task Graph 计算期间延迟解析的，不依赖子工程是否已经预先执行完毕 `build.gradle`。
- 因此，我们可以直接在 `gradle.rootProject` 创建 `juggCollectExternalBuildInfo` 收集任务的同时，立刻将 `mustRunAfter(localTaskPaths)` 声明给收集任务。

### 3.2 最小代码改动点

#### 1) `GradleProjectInfoReaderManager.kt`
在 `configureExternalBuildInfoCollector()` 中，将 `localTasks` 改为收集匹配本地子工程的 `taskPath` 字符串列表：
```kotlin
fun configureExternalBuildInfoCollector() {
    if (!isExternalBuildInfoCollection()) {
        return
    }
    val collector = rootProject.tasks.maybeCreate(COLLECT_EXTERNAL_BUILD_INFO_TASK_NAME)
    val localTaskPaths = readExternalBuildInfoRequests().mapNotNull { request ->
        val matchesLocalProject = rootProject.allprojects.any {
            it.projectDir.absoluteFile.normalize() == request.moduleRootDir.absoluteFile.normalize()
        }
        if (matchesLocalProject) request.taskPath else null
    }.distinct()
    if (localTaskPaths.isNotEmpty()) {
        collector.mustRunAfter(localTaskPaths)
    }
    includeBuildProjects.forEach { includedBuild ->
        collector.dependsOn(includedBuild.task(COLLECT_EXTERNAL_BUILD_INFO_TASK_PATH))
    }
}
```

#### 2) `buildReadProjectInfoScript.gradle` 与 `readProjectInfo.gradle.kts`
将 `configureExternalBuildInfoCollector()` 的调用时机从 `gradle.projectsEvaluated` 前移到 `gradle.rootProject`（在创建 `COLLECT_EXTERNAL_BUILD_INFO_TASK_NAME` 之后立即配置）：
```groovy
gradle.rootProject {
    if (properties[GradleProjectInfoReaderManager.PARAM_EXTERNAL_BUILD_INVOCATION] != null) {
        tasks.maybeCreate(GradleProjectInfoReaderManager.COLLECT_EXTERNAL_BUILD_INFO_TASK_NAME).apply {
            group = "jugg"
            description = "Writes refreshed external build info for this build"
            outputs.upToDateWhen { false }
            doLast {
                gradleProjectInfoReaderManager(rootProject, gradle.includedBuilds).collectExternalBuildInfo()
            }
        }
        gradleProjectInfoReaderManager(rootProject, gradle.includedBuilds).configureExternalBuildInfoCollector()
    }
    if (gradle.parent != null) {
        tasks.maybeCreate(GradleProjectInfoReaderManager.READ_PROJECT_INFO_TASK_NAME).apply {
            group = "jugg"
            description = "Writes Jugg project info for this included build"
        }
    }
}
```
从 `gradle.projectsEvaluated` 中移除重复调用。

---

## 4. 验证计划

1. **单元测试回归**：
   - 运行现有的 `GradleProjectInfoReaderManagerNativeStripTest`，确保既有关于 `collector.mustRunAfter` 的断言全部通过。
2. **新增针对 Task Path 字符串排序的单元测试**：
   - 验证 `configureExternalBuildInfoCollector()` 在子工程 Task 尚未在容器中解析时，依然能正确将 taskPath 绑定到 `collector.mustRunAfter`。
3. **全量构建/资源编译验证**：
   - 执行 `./gradlew :main:compileKotlin` 及 `:main:test` 相关测试，确保 `readProjectInfo.gradle.kts` 正确重新生成且无语法或编译错误。
