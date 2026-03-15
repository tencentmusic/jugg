# Dex Merge 重复类 Bug 排查记录

## 错误现象

```
Merge dex failed, reason: Compilation failed to complete,
origin: .../deployed/classes/ENResource.dex

Caused by: Type ENResource is defined multiple times:
  1. .../database/compile_context.db/deployed/classes/ENResource.dex
  2. .../build/staging/classes/ENResource.dex
```

日志文件：`build/jugg/log/compile_2026-03-12_20-09-38.0.log`（行 14496-14566）

触发条件：dex 总数（staging + deployed）超过 `MAX_DEPLOYED_DEX_COUNT = 800`，触发 dex merge。

## 涉及文件

| 文件 | 作用 |
|------|------|
| `main/.../deploy/DeployDataPlanner.kt` | dex merge 触发逻辑 |
| `main/.../deploy/DeployFileStateTracker.kt` | staging/deployed 文件状态管理，含去重逻辑 |
| `main/.../deploy/CompileContextDb.kt` | deployed dex 持久化存储与读取 |
| `main/.../compiler/IncrementalCompilerHelper.kt` | `mergeDex()` 实现 |
| `main/.../compiler/JuggCompiler.kt` | 设置 `classesOutputDir = staging/classes/` |

## 目录结构关系

```
build/jugg/build/
  staging/
    classes/               <- JuggCompiler 设置 classesOutputDir = File(stagingDir, "classes")
      ENResource.dex       <- 无包名的 KMP 类
      com/tme/joox/
        DressCenterPage.dex

database/compile_context.db/
  deployed/
    classes/               <- CompileContextDb.dexDeployedDir
      ENResource.dex       <- 由 copyToBaseDir(baseDir=staging/classes, dest=deployed/classes) 写入
```

**`relativeFile.path` 计算方式**（`ICompiler.kt:137`）：
```kotlin
val relativeFile get() = file.absoluteFile.relativeTo(baseDir)
```

正常情况下两者路径一致：

| 来源 | file | baseDir | `relativeFile.path` |
|------|------|---------|---------------------|
| staging | `staging/classes/ENResource.dex` | `staging/classes/` | `ENResource.dex` |
| deployed (正常) | `deployed/classes/ENResource.dex` | `deployed/classes/` | `ENResource.dex` |

## 根因分析

### 去重逻辑（`DeployFileStateTracker.getNotStagingDeployedFiles()`）

```kotlin
val stagingFileRelativeSet = stagingFiles.map { it.value.relativeFile.path }.toSet()
return deployedFiles.values.filter {
    it.relativeFile.path !in stagingFileRelativeSet
    && it.relativeFile.path !in mergedDexFilePathSet
}
```

### 推测根因：历史遗留格式不一致

某个历史版本中 staging dex 的 baseDir 可能是 `staging/`（而非 `staging/classes/`），导致当时 `copyToBaseDir` 存储时：

```
relativeTo(staging) = classes/ENResource.dex
存储到 deployed/classes/classes/ENResource.dex
读取时 relativeFile.path = classes/ENResource.dex
```

而当前 staging 的 `relativeFile.path = ENResource.dex`，**两者不一致**，`getNotStagingDeployedFiles()` 去重失败，ENResource 同时出现在 stagingFiles 和 deployedDexOutputs 里，D8 merge 时报重复类型错误。

### `DeployDataPlanner` 中已移除二次过滤

旧版本曾在 `convertToMergedDexDeployData` 里有二次过滤（现已移除）：

```kotlin
// 旧版（已移除）
val stagingDexNames = stagingDexOutputs.map { it.relativeFile.path }.toSet()
val filteredDeployedDex = deployedDexOutputs.filterNot { it.relativeFile.path in stagingDexNames }
val mergedOutputs = mergeDex(stagingFiles + filteredDeployedDex, mergeOutputDir)

// 当前代码（第 99 行）
val mergedOutputs = mergeDex(stagingFiles + deployedDexOutputs, mergeOutputDir)
```

## ENResource 为何无包名

源文件：`joox_kuikly/JXB/src/commonMain/kotlin/com/tme/joox/resources/res/ENResource.kt`

该类是 Kotlin Multiplatform 的资源类，编译后 class 无包名（KMP 特殊处理），因此 dex 路径为根目录的 `ENResource.dex`，而非 `com/tme/joox/resources/res/ENResource.dex`。

## 待定修复方案

**方案 A**：`getNotStagingDeployedFiles()` 改用 `file.name` 去重，容忍路径前缀不一致
- 优点：健壮，向前兼容历史数据
- 风险：不同路径下的同名 dex 可能误杀（实际概率极低）

**方案 B**：`convertToMergedDexDeployData` 恢复二次过滤，改用 `file.name` 比较
- 优点：影响范围小，只修改 merge 路径
- 缺点：只治标，`getNotStagingDeployedFiles` 的旧格式问题仍在

**方案 C**：升级时触发 clean reinstall 清理 deployed db
- 优点：彻底解决历史数据问题
- 缺点：用户体验差

**方案 D**：`CompileContextDb.updateDeployedData` 存储前按文件名查找并删除旧格式文件，再写入
- 优点：自愈，不需要用户操作
- 缺点：实现略复杂
