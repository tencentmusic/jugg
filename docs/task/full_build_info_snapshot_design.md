# FullBuildInfo 快照存储方案

## 1. 背景

当前 Gradle full build 后的增量基线由多处文件和入口共同维护：

- APK 信息由 `CompileContextDb` 写入 `build/jugg/database/compile_context.db/apks/apks.json`。
- 模块 build path 由 `CompileContextDb` 写入 `build/jugg/database/compile_context.db/module_builds.json`。
- full build 命令与 `BuildTarget` 由 `BaseBuildCommandHelper` 写入 `build/jugg/database/project_infos.db/base_build_cmd.txt`。

这些数据都描述同一次 Gradle full build 之后的基线，但当前写入入口不一致。`base_build_cmd.txt` 会在 `JuggRunningTask` 的初始化任务中提前写入，而 APK/module 信息要等 `initIncrementalCompileAfterFullBuild()` 获取 APK 后才写入。若 APK 获取失败，可能出现 command/target 已更新但 APK/module 基线未更新的状态。

目标是新增 `FullBuildInfo`，把 full build 元信息纳入 `CompileContextDb`，并通过 `IDeployHistoryManager` 屏蔽外部实现细节。

## 2. 设计目标

1. 将 full build 的命令与目标类型记录到 `compile_context.db` 下，与 APK/module 基线使用同一生命周期。
2. 保留 `IDeployHistoryManager` 作为唯一外部入口，调用方不直接依赖 `CompileContextDb` 或具体 `DeployHistoryManager`。
3. 保持 `apks.json` 与 `module_builds.json` 独立，避免把 command/target 融入 APK 数据。
4. 允许旧 `base_build_cmd.txt` 丢失，不做迁移兼容。
5. 不破坏旧 `apks.json` 的恢复能力，避免因为 full build 元信息缺失触发不必要降级。

## 3. 数据模型与存储

新增 `FullBuildInfo`，只记录 full build 元信息：

```kotlin
data class FullBuildInfo(
    val compileCommand: String?,
    val buildTarget: BuildTarget,
    val createdAt: Long,
)
```

说明：

- `compileCommand` 允许为空，用于表达没有可用命令记录的状态。
- `buildTarget` 缺省容错建议为 `BuildTarget.APP`，延续旧 `BaseBuildCmdRecord` 的容错行为。
- `createdAt` 使用 full build 基线成功写入时的当前时间，仅用于日志和排查，不参与 deploy history 的 changed-file cutoff 判断。

新增文件：

```text
build/jugg/database/compile_context.db/full_build_info.json
```

保留现有文件：

```text
build/jugg/database/compile_context.db/apks/apks.json
build/jugg/database/compile_context.db/module_builds.json
```

废弃但不主动处理：

```text
build/jugg/database/project_infos.db/base_build_cmd.txt
```

`full_build_info.json` 推荐格式：

```json
{
  "version": 1,
  "compileCommand": "./gradlew :app:assembleDebug",
  "buildTarget": "APP",
  "createdAt": 1710000000000
}
```

## 4. API 设计

### 4.1 `CompileContextDb`

`CompileContextDb` 继续负责底层持久化，但不作为外部业务入口。

建议新增或调整内部 API：

```kotlin
fun saveCompileContext(
    fullBuildInfo: FullBuildInfo,
    apkInfos: List<ApkInfo>,
    modules: Map<String, ModuleInfo>,
): CompileContextInfo

fun getFullBuildInfoFromDb(): FullBuildInfo?
```

现有 API 保持语义：

```kotlin
fun getCompileBuildPathInfoFromDb(): CompileContextInfo?
```

读取 compile context 时仍只依赖 `complete_flag`、`apks.json` 和 `module_builds.json`。`full_build_info.json` 缺失不应导致 `getCompileBuildPathInfoFromDb()` 返回 null。

### 4.2 `IDeployHistoryManager`

外部只通过接口读取 full build 基线。

采用精简 API：

```kotlin
fun getFullBuildInfo(): FullBuildInfo?

fun isBuildTargetChanged(options: JuggGradleCompileOptions): Boolean
```

约定：

- `getFullBuildInfo()` 返回 null 表示没有可用 command/target 元信息。
- `isBuildTargetChanged(options)` 在没有 `FullBuildInfo` 时返回 false，保持首次运行行为。
- 调用方需要 compile command 时使用 `getFullBuildInfo()?.compileCommand`，不新增 `getFullBuildCommand()` 之类便利方法，避免接口膨胀。

### 4.3 `DeployHistoryManager`

`DeployHistoryManager` 作为 `IDeployHistoryManager` 实现，委托 `CompileContextDb` 读写 `FullBuildInfo`。

`reInitAfterFullCompiled` 建议调整为：

```kotlin
fun reInitAfterFullCompiled(
    fullBuildInfo: FullBuildInfo,
    apkInfos: List<ApkInfo>,
    modules: Map<String, ModuleInfo>,
    startCompileTime: Long,
): CompileContextInfo
```

该方法一次性提交 full build 元信息、APK 信息和模块 build path，然后重置 deploy history。

## 5. 触发时机与调用点迁移

### 5.1 写入时机

`JuggRunningTask` 不再提前写 `base_build_cmd.txt`。

`JuggManager.initIncrementalCompileAfterFullBuild()` 在获取 APK 成功后组装 `FullBuildInfo`，再调用 `IDeployHistoryManager.reInitAfterFullCompiled(...)` 提交基线。这样 command/target 与 APK/module 信息处于同一次提交边界。

`MoreOptionsManager.markAsGradleCompiledAndReInitCompiler()` 需要把本次 `JuggGradleCompileOptions` 传入初始化流程，避免只写 APK/module 而缺失 command/target。

### 5.2 读取调用点

以下调用点从 `BaseBuildCommandHelper` 迁移到 `IDeployHistoryManager`：

- `JuggCompileHelper`：build target 变化判断改为 `deployHistoryManager.isBuildTargetChanged(options)`。
- `CompileContextManager`：当前 build target 改为 `deployHistoryManager.getFullBuildInfo()?.buildTarget ?: BuildTarget.APP`。
- `GradleProjectInfoLocalFetchManager`：project info 可用性和 base command 获取改为读取 `deployHistoryManager.getFullBuildInfo()?.compileCommand`。

生产代码不再 new `BaseBuildCommandHelper`。

## 6. 兼容策略

1. `base_build_cmd.txt` 不迁移、不读取、不再写入。该文件可丢失，缺失后相关逻辑按“没有 full build command 记录”处理。
2. `apks.json` 继续作为 compile context 恢复的必要数据，不删除、不迁移。
3. 没有 `full_build_info.json` 时：
   - `getCompileBuildPathInfoFromDb()` 仍可正常恢复 APK/module 基线。
   - `getFullBuildInfo()` 返回 null。
   - `isBuildTargetChanged()` 返回 false。
   - 需要 base command 的 project info update 可跳过，等待下一次 Gradle full build 写入 `full_build_info.json`。

## 7. 失败处理

- `saveCompileContext()` 继续先清理旧 compile context，保持现有写新基线前清空的语义。
- 写入 `full_build_info.json`、`apks.json` 或 `module_builds.json` 任一失败时，不创建 `complete_flag`。
- `full_build_info.json` 解析失败只影响 `getFullBuildInfo()`，不影响 `getCompileBuildPathInfoFromDb()`。
- `apks.json` 为空或 APK 文件缺失时，沿用现有逻辑删除 `complete_flag` 并返回 null。

## 8. 测试计划

遵循 TDD：先补 `main/src/test`，再修改 `src/main`。

建议测试：

1. `FullBuildInfoSerializerTest`
   - JSON roundtrip。
   - `compileCommand = null` 可正常处理。
   - 缺失或非法 `buildTarget` 时容错为 `BuildTarget.APP`。

2. `CompileContextDbFullBuildInfoTest`
   - `saveCompileContext()` 后能读取 `FullBuildInfo`。
   - 没有 `full_build_info.json` 但有 `apks.json`、`module_builds.json` 和 `complete_flag` 时，compile context 恢复成功。
   - `full_build_info.json` 缺失时 `getFullBuildInfoFromDb()` 返回 null。

3. `DeployHistoryManagerFullBuildInfoTest`
   - `getFullBuildInfo()` 委托读取 `CompileContextDb`。
   - `isBuildTargetChanged()` 在无记录、相同 target、不同 target 三种场景下行为正确。

旧测试处理：

- `BaseBuildCmdRecordTest` 迁移为 `FullBuildInfo` 序列化测试。
- `BaseBuildCommandHelperBuildTargetTest` 迁移为 `DeployHistoryManager` 的 target 判断测试。

建议验证命令：

```bash
./gradlew :main:test --tests "*FullBuildInfo*"
./gradlew :main:test --tests "*CompileContextDb*"
./gradlew :main:test --tests "*DeployHistoryManager*"
./gradlew :idea:compileKotlin
```

注意：禁止运行无 `--tests` 过滤的完整 `:main:test` 或 `:idea:test`。

## 9. 文档同步

实现后需要同步：

- `docs/ai_knowledge/09_plugin_runtime_debug.md`：运行时目录结构中移除 `project_infos.db/base_build_cmd.txt`，补充 `compile_context.db/full_build_info.json`。
- 如 `98_code_map.md` 中职责描述需要调整，可补充 `CompileContextDb` / `DeployHistoryManager` 对 full build 基线元信息的说明。
