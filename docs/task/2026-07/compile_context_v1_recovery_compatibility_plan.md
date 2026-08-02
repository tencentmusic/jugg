# Compile Context v1 恢复兼容方案

## 1. 背景

`support custom Gradle build directories` 为 `ModuleBuildPathInfo` 增加了
`buildDirRelativePath`，并将 `BuildPathInfoSerializer` 的磁盘版本从 1 提升到 2。
当前 reader 只接受与当前版本完全相同的数据，导致仍持有有效 v1
`compile_context.db` 的用户在升级后恢复失败，随后 `CompileContextDb` 删除
`complete_flag`，下一次 Run 被迫执行全量 Gradle 构建。

已经有部分用户完成了 v2 全量构建，因此修复必须同时满足：

- 仍保留 `complete_flag` 的 v1 用户可以继续恢复。
- 正常 v2 用户继续按真实 `buildDirRelativePath` 恢复；允许损坏 v2 的缺失字段降级到默认目录。
- 已丢失 `complete_flag` 的用户不自动修复，继续通过一次成功的 Jugg 全量构建重建上下文。

## 2. 已确认事实

- v1 的构建目录语义固定为 `<moduleRootDir>/build`。
- 当前 `ModuleBuildPathInfo` 使用空 `buildDirRelativePath` 表示同一传统目录语义。
- v1 缺失的字段可以确定性映射为 `buildDirRelativePath = ""`，不需要猜测路径。
- v2 已保存真实的 `buildDirRelativePath`，包括自定义 Gradle build directory。
- `complete_flag` 既表示数据完整，也承担写入事务完成标记；缺失时不能仅凭 JSON 文件存在自动补建。
- 当前恢复失败由 `BuildPathInfoSerializer.load()` 返回 `null` 引起，`CompileContextDb` 的后续失效逻辑本身无需修改。

## 3. 目标与非目标

### 3.1 目标

1. writer 继续只写 v2，不产生新的磁盘版本。
2. reader 明确支持 v1 和 v2。
3. v1 使用传统构建目录语义恢复，并保留已有 `complete_flag`。
4. 正常 v2 必须保留磁盘中的真实路径；允许损坏 v2 的缺失字段回退到传统目录。
5. 未知版本继续拒绝恢复。
6. 缺失 `complete_flag` 时继续要求一次成功的 Jugg 全量构建。

### 3.2 非目标

- 不自动重建已经丢失的 `complete_flag`。
- 不把 v1 文件原地改写为 v2。
- 不增加新的迁移数据库、接口或恢复管理类。
- 不改变 project info 的版本刷新策略。
- 不改变 Gradle 编译、classpath 拉取、deploy history 的事务顺序。

## 4. 方案比较

| 方案 | 行为 | 优点 | 风险/代价 | 结论 |
|---|---|---|---|---|
| A. 提升到 v3 并统一迁移 | v1/v2 都转成 v3 | schema 边界显式 | 再次扩大失效面，v2 用户承担无必要风险 | 不采用 |
| B. writer 保持 v2，reader 兼容 v1/v2 | 缺失路径使用默认目录，正常 v2 保留真实目录 | 改动最小，不影响现有 v2 用户 | 损坏 v2 可能回退到默认目录 | 采用 |
| C. 自动补建缺失 flag | 尝试救回已受影响用户 | 减少一次全量构建 | 无法可靠区分旧版本失效与中断写入，可能混合 compile context 和 deploy history | 不采用 |

## 5. 详细设计

### 5.1 版本策略

`BuildPathInfoSerializer` 保持当前 writer 版本：

```kotlin
private const val VERSION = 2
private const val MIN_SUPPORTED_VERSION = 1
```

不提升到 v3。后续正常全量构建仍通过 `save()` 写入 v2。

### 5.2 DTO 兼容

将磁盘 DTO 中新增字段改为可空：

```kotlin
val buildDirRelativePath: String?
```

这是磁盘兼容边界，不改变生产领域模型 `ModuleBuildPathInfo` 的非空约束。
不能依赖 Kotlin 默认参数，因为 Gson 读取缺失字段时不会执行构造函数默认值。

### 5.3 读取规则

`load()` 先校验磁盘版本是否在支持范围内：

| 版本 | `buildDirRelativePath` 处理 | 结果 |
|---|---|---|
| v1 | 缺失字段使用空字符串 | 恢复为 `<moduleRootDir>/build` |
| v2 | 字段存在时原样读取，缺失时使用空字符串 | 保留正常路径；损坏数据回退到传统目录 |
| 其他 | 不解析 module entries | 返回 `null` |

建议保留当前简单结构，不引入 migration interface。版本检查只负责拒绝未知版本，字段转换统一使用：

```kotlin
buildDirRelativePath = args.buildDirRelativePath.orEmpty()
```

正常 writer 写出的 v2 始终包含该字段，因此 `orEmpty()` 只影响 v1 或损坏的 v2 数据。

### 5.4 `complete_flag` 行为

不修改 `CompileContextDb`：

- v1 合法读取后 `moduleBuilds` 非空，恢复成功，已有 flag 保留。
- v2 合法读取行为不变。
- 未知版本仍返回 `null`，沿用现有逻辑删除 flag。
- v2 缺失字段按传统目录恢复，这是本方案明确接受的损坏数据行为。
- flag 原本不存在时，仍在读取 module 数据前返回 `null`，不会进入 legacy reader，也不会补建 flag。

### 5.5 是否原地升级 v1

本次不在读取时重写 `module_builds.json`：

- 恢复路径保持只读，避免引入新的启动期事务。
- 多次启动读取 v1 的结果稳定且成本可忽略。
- 用户下一次成功完成 Jugg 全量构建时，现有 `saveCompileContext()` 自然写入 v2。

### 5.6 日志

- v1 兼容恢复打印 `debug`，包含磁盘版本和 module 数量。
- 未知版本打印 `warn`，包含实际版本。
- 不使用 `info`，避免把正常的兼容恢复展示给用户。
- 不打印完整 module path 列表，避免大型工程日志膨胀。

## 6. 用户分组与发布行为

| 用户状态 | 新版本行为 |
|---|---|
| v1，flag 仍存在 | 启动时兼容恢复，不触发全量构建 |
| v2，flag 存在 | 完全沿用 v2 路径，不发生降级 |
| v1，但 flag 已被问题版本删除 | 不自动恢复；下一次 Run 完成一次 Jugg 全量构建 |
| v2 缺少路径字段 | 使用传统 `<moduleRootDir>/build` 恢复 |
| 未知未来版本 | 拒绝恢复，避免旧 reader 误读新语义 |

## 7. TDD 测试矩阵

### 7.1 执行清单

| 层级 | 测试路径 | 场景 | 修改前预期 | 修改后预期 |
|---|---|---|---|---|
| L1 | `main/src/test/java/com/sickworm/intellij/jugg/deploy/CompileContextDbFullBuildInfoTest.kt` | v1 + flag 恢复 | 返回 `null` 并删除 flag | 恢复成功，`buildDir` 为 `<module>/build`，flag 保留 |
| L1 | 同上 | v2 自定义路径恢复 | 成功 | 继续成功且路径原样保留 |
| L1 | 同上 | 未知版本 | 返回 `null` | 行为保持，flag 删除 |
| L1 | 同上 | v1 数据完整但 flag 缺失 | 返回 `null` | 行为保持，不自动创建 flag |
| L3 | `idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowWithGitTest.kt` | 全量构建和增量部署后把 module snapshot 转为 v1，再创建新 `MockJugg` 恢复 | 新实例进入 `READY_FULL_COMPILE` | 新实例恢复到 `READY_DEPLOY` 并保留全量编译状态 |

`BuildPathInfoSerializer` 属于确定性序列化逻辑，L1 允许覆盖；为减少测试文件扩散，优先追加到已有
`CompileContextDbFullBuildInfoTest`。用户可见的重启恢复行为由已有
`TopLevelFlowWithGitTest` 承担 L3 证明。

### 7.2 关键断言

v1 测试必须同时断言：

- `getCompileBuildPathInfoFromDb()` 非空。
- module 数量正确。
- `buildDirRelativePath == ""`。
- `buildDir == File(moduleRootDir, "build")`。
- `complete_flag` 仍存在。

v2 测试必须同时断言：

- 自定义 `buildDirRelativePath` 原样保留。
- `buildDir` 指向 project-root-relative 自定义目录。

missing-flag 测试必须断言：

- 返回 `null`。
- `complete_flag` 不会被自动创建。

## 8. 实施步骤

1. 在 `CompileContextDbFullBuildInfoTest` 添加 v1 + flag 失败测试并单独运行，确认失败原因为严格版本比较。
2. 在同一文件补齐 v2 自定义路径、未知版本和 missing-flag 回归测试。
3. 在 `TopLevelFlowWithGitTest` 扩展恢复场景，构造真实 v1 module snapshot，确认新实例修改前进入全量构建状态。
4. 修改 `BuildPathInfoSerializer.BuildPathInfoArgs`，仅将磁盘 DTO 字段改为可空。
5. 修改 `BuildPathInfoSerializer.load()`，接受 v1/v2 并对缺失字段使用空字符串；writer 版本保持 2。
6. 增加精确的 debug/warn 日志，不修改 `CompileContextDb`、`DeployHistoryManager` 或 flag 事务。
7. 运行定向 L1 和 L3 测试。
8. 同步 `04_engineering_project.md` 和 `09_plugin_runtime_debug.md` 的兼容/排查说明。
9. 检查 diff，确认没有 v3 或自动补 flag 逻辑，正常 v2 自定义路径测试仍通过。
10. 按仓库规范提交本次 bugfix。

## 9. 验证命令

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.CompileContextDbFullBuildInfoTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowWithGitTest.recoveryDeployFromVersion1CompileContextWithGit"
./gradlew :idea:compileKotlin
```

禁止运行无 `--tests` 过滤的全量 `:main:test` 或 `:idea:test`。

## 10. 文档同步

实现完成后更新：

- `docs/ai_knowledge/04_engineering_project.md`：说明 compile context writer 保持 v2，reader 兼容 v1/v2。
- `docs/ai_knowledge/09_plugin_runtime_debug.md`：说明 v1 有 flag 时可恢复，flag 已丢失时仍需一次全量构建。

原 `custom_gradle_build_directory_support_plan.md` 保留为历史方案，不回写当前实现结论。

## 11. 风险与控制

| 风险 | 控制 |
|---|---|
| 损坏 v2 缺字段时使用默认目录 | 已明确接受；正常 v2 自定义路径由回归测试保护 |
| v1 自定义目录信息缺失 | v1 只恢复旧版本能够表达的传统目录语义；构建脚本变化继续由现有回退机制处理 |
| 已丢失 flag 的上下文被错误拼接 | 不自动补 flag，不绕过 `hasBeenFullCompiled` 检查 |
| reader 修改影响现有 v2 用户 | writer 不变、版本不变，并用 v2 自定义路径回归测试证明 |
| 启动时重写缓存中断 | 不在恢复阶段写回 v1 数据 |

## 12. 验收标准

- v1 + flag 用户升级后无需 Gradle 全量构建即可恢复。
- v2 默认目录和自定义目录用户行为与修复前一致。
- 正常 v2 默认目录和自定义目录均原样恢复。
- flag 缺失时不会自动恢复或补建。
- 未知版本继续失败关闭。
- 定向 L1、L3 和编译验证全部通过。
- 建议实现提交信息：`[bugfix] avoid unnecessary full builds after Jugg upgrade`
