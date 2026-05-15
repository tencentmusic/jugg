# 编译系统：核心架构（AI 速查）

> 最后核对：2026-02-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页关注编译总控与调度，不展开单一子编译器实现细节。

---

## 2. 核心入口与职责

| 入口类 | 文件 | 作用 |
|--------|------|------|
| `JuggCompileHelper` | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` | 决策增量/Gradle 回退、预检查、触发编译 |
| `IncrementalCompilerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt` | 增量循环编译、影响传播重编译、重试逻辑 |
| `JuggCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompiler.kt` | 统一串联资源/源码/dex/附加产物编译 |
| `CompileOrder` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileOrder.kt` | 阶段顺序范围定义 |
| `CompileTask` / `CompileResult` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/*` | 编译输入输出统一模型 |

---

## 3. 核心流程（增量路径）

1. `JuggCompileHelper.preprocessIncrementalCompile` 做回退判定。  
2. `IncrementalCompilerHelper.compile` 执行一轮编译。  
3. `JuggCompiler.doCompile` 按阶段组合子编译器。  
4. 若检测到受影响源码/类，继续下一轮增量编译。  
5. 结果写入 `DeployFileManager` staging，用于后续部署。

成功编译的文件会记录文件快照（`lastModified + length`）。如果后续 IDE 文件事件迟到，但文件当前快照与已编译快照一致，`DeployFileManager` 会忽略这次重复变更事件，避免把已编译未部署的文件重新刷回未编译状态；真正再次修改且快照变化的文件仍会重新进入待编译集合。

编译输出模型支持多 APK 归属语义：

- `CompileOutput.apkPath` 保留旧的单 APK 锚点。
- `CompileOutput.targetApkPaths` 表示该输出实际影响的所有 APK；当 `apkPath` 是真实 APK 路径时，构造期会保证 target 列表至少包含它。
- `BaseCompiler.splitApkAndCompile()` 会按 `getAllBelongsApk()` 将同一 module 的资源/manifest/asset 输入分发到多个 APK 组，并对每个 APK 调用 `doApkCompile()`。
- `doApkCompile()` 是 APK-scoped hook，子类输出应保留当前 APK 的归属信息。

---

## 4. 阶段顺序（`CompileOrder`）

- `asset` 阶段
- `res` 阶段
- `source` 阶段
- `minify` 阶段
- `dex` 阶段

`BaseCompiler` 通过 `beforeCompileOrderRange` / `afterCompileOrderRange` 挂接扩展处理。

---

## 5. 回退与重试机制

### 5.1 触发 Gradle 回退的常见条件

- 用户强制回退。
- 设备状态不满足增量部署。
- 变更文件点数/模块数超过阈值。
- 依赖变化或编译失败不可恢复。

### 5.2 增量内重试

- 重试策略接口：`IIncrementalCompileRetryResolver`，由 `IncrementalCompileRetryResolverChain` 串联多个实现。
- 当前 chain 顺序：
  1. `GitChangesRetryResolver`（`idea` 层）：检测 `unresolved reference / cannot find symbol` 类错误 → 触发 `GitFileChangesDetector.updateChangedFiles()` → 若发现新文件则重试一次。
  2. `IncrementalCompileRetryResolver`：检测依赖缺失关键词 → 更新 compile context → 有变化则重试一次。
- 影响传播重编译：基于 `DeployFileManager.getRecompileFiles(...)`。

---

## 6. 关键状态对象

- `CompileUiHandler`：取消、提示、process handler 等交互。  
- `CompileStatusHolder`：编译中状态与取消标记。  
- `CompileTaskResult`：增量/回退路径统一结果。

---

## 7. 高频排查建议

- “为什么这次没走增量”：看 `JuggCompileHelper.preprocessIncrementalCompile`。  
- “为什么编译成功但又继续编译”：看 `IncrementalCompilerHelper` 的 effected source 逻辑。  
- “为什么产物不在预期目录”：看 `CompileTask.outputDir` 与 `JuggPathManager`。

---

## 8. 关联文档

- 源码编译：`02_compile_source.md`
- 资源编译：`02_compile_resource.md`
- 部署影响分析：`03_deploy_data_generator.md`
