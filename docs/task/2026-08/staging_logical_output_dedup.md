# Staging 逻辑产物去重

## 背景

Jugg 在 reinstall recover 时会通过 `DeployFileStateTracker.resetAfterReinstall()` 将历史 deployed 产物重新放入 staging，准备重放。历史 Dex 位于 `database/compile_context.db/deployed/classes/`，后续增量编译生成的新 Dex 位于 `build/staging/classes/`。

`DeployFileStateTracker` 当前使用文件绝对路径作为 `stagingFiles` 的 Map key。相同逻辑产物只要来自不同目录，就会同时保留。2026-08-10 的运行日志出现以下时序：

1. 恢复出 822 个历史 deployed Dex。
2. reinstall 后将历史 Dex 放入 staging。
3. 重放失败，staging 未 commit/clear。
4. 后续空编触发 effected source 再编译，生成新的 staging Dex。
5. staging 增长到 1607，超过自动 Dex 合并阈值。
6. D8 同时收到历史 `IDResource.dex` 和新 `IDResource.dex`，报出 `Type IDResource is defined multiple times`。

重复 effected 编译只是触发条件。直接缺口是 staging 以物理路径保存产物，但部署规划、历史 shadow 和 multi APK 隔离使用“目标 APK + relative path”作为逻辑身份，两个边界的身份规则不一致。

## 解决的问题

所有进入 staging 的产物统一按现有 deploy key 去重：

```text
target APK + relative path
```

相同逻辑产物后写入优先，不同 target APK 下的同 relative path 继续共存。恢复历史中缺失 APK scope 的 Dex 使用现有兼容规则：有明确 scope 的新 Dex 优先于 lossy recovered Dex，与写入顺序无关。

该约束需要覆盖两个 staging 入口：

- 正常编译产生的 `addStagingFiles()`。
- reinstall recover 产生的 `resetAfterReinstall()`。
- 部署成功后 staging 进入 deployed 的 `commitAndClear()`。

修复后，即使同一源文件再次编译，也只会更新 staging 中对应的逻辑产物，不会因为历史目录和新编译目录不同而形成冲突输入。

## 已确认边界

- 规则复用 `getNotStagingDeployedFiles()` 已有的 deploy key 语义，适用于所有 `CompileOutput`，不新增 Dex 专用身份模型。
- lossy history 的 relative path shadow 仍然只适用于 Dex。
- Dex target APK 归属变化会伴随 build 文件变化并触发降级，本次不处理 target 集合部分重叠和 scope 拆分。
- 同一个写入批次若出现重复 deploy key，按输入顺序后写覆盖，并记录 debug 日志用于诊断。
- 不修改 compile context 数据格式。staging 入口与 commit 使用相同 shadow 规则，历史物理路径不能以冲突形式保留在运行时 deployed 状态。

## 方案比较与权衡

### 方案一：D8 合并前去重

只在 `DeployDataPlanner` 或 `IncrementalCompilerHelper` 传入 D8 前删除重复 Dex。

优点是修改位置接近报错点。缺点是 staging 内部仍然包含冲突状态，effect 分析、普通部署、compat deploy 和 commit 仍可能消费重复产物；没有解决状态源头，因此不采用。

### 方案二：仅对 Dex 增加 staging 去重

只为 Dex 建立额外的 relative path 或 target APK 判断。

可以解决当前报错，但会与 `getNotStagingDeployedFiles()` 已适用于全部产物的 deploy key 规则形成两套身份模型，增加分支和维护成本，因此不采用。

### 方案三：统一 staging 写入边界

复用现有 deploy key 和 lossy Dex shadow 规则，在 `DeployFileStateTracker` 内统一处理所有 staging 写入。

该方案修改范围最小，并使 staging 与下游已有逻辑身份保持一致，因此采用。

## 实施范围

- `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileStateTracker.kt`
  - 提取 staging 写入的逻辑 shadow 判断。
  - `addStagingFiles()` 写入前移除被新产物覆盖的旧 staging 产物。
  - `resetAfterReinstall()` 复用同一写入入口，禁止按绝对路径直接写入。
  - `commitAndClear()` 写入 deployed 前移除被 staging 覆盖的旧逻辑产物。
  - commit 发生清理时聚合记录原因、数量和前 20 个旧文件，说明保留旧物理路径会导致后续部署或 Dex merge 消费重复逻辑产物。
  - 重复逻辑产物被替换时记录 debug 日志。
- `main/src/test/java/com/sickworm/intellij/jugg/deploy/DeployFileStateTrackerTest.kt`
  - 保护普通产物相同 deploy key 后写覆盖。
  - 保护 reinstall 恢复多个物理路径时按 deploy key 收敛。
  - 保护不同 target APK 的同 relative path 共存。
- `main/src/test/java/com/sickworm/intellij/jugg/deploy/DeployFileManagerRecoverTest.kt`
  - 保护 `reset -> add` 时 scoped Dex 覆盖 lossy history。
  - 保留既有 `add -> reset` 和不同 target APK 回归。

## 明确排除

- 不修复 effected source 被再次编译的上游原因。
- 不修改 Dex target APK 归属模型。
- 不处理 target 集合部分重叠。
- 不修改 deployed Map key 或历史数据库格式。
- 不在 D8 合并阶段增加兜底去重。
- 不清理磁盘中的历史 Dex 文件。

## 验证策略

| 层级 | 测试 owner | 场景 | 修改前 | 修改后 |
|---|---|---|---|---|
| L1 | `DeployFileManagerRecoverTest` | `reset -> add`，新 scoped Dex 与 lossy history 同 relative path | staging/deploy data 同时包含两份类定义 | 只保留新 Dex |
| L1 | `DeployFileManagerRecoverTest` | `add -> reset`，恢复历史缺少 scope | 既有回归通过 | 继续只保留新 Dex |
| L1 | `DeployFileStateTrackerTest` | 相同 target APK 和 relative path 连续写入 | 两个物理路径同时保留 | 后写产物覆盖先写产物 |
| L1 | `DeployFileStateTrackerTest` | reinstall 恢复相同 deploy key 的多个物理文件 | 多个文件同时进入 staging | 按恢复顺序保留最后产物 |
| L1 | `DeployFileStateTrackerTest` | scoped staging Dex 成功 commit，deployed 中存在同路径无 scope 历史 Dex | deployed 同时保留新旧两份 Dex | 只保留新 Dex |
| L1 | `DeployFileStateTrackerTest` | 相同 deploy key 的 staging 产物成功 commit | deployed 按物理路径保留新旧两份产物 | 后提交产物覆盖旧产物 |
| L1 | `DeployFileStateTrackerTest` | 不同 target APK、相同 relative path | 两个产物共存 | 保持不变 |

定向验证：

```text
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.DeployFileStateTrackerTest" --tests "com.sickworm.intellij.jugg.deploy.DeployFileManagerRecoverTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.DeployFileManagerDexMergeTest"
./gradlew :idea:compileKotlin
git diff --check
```
