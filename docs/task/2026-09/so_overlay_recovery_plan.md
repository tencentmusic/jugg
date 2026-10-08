# SO Overlay 跨会话恢复方案

> 基线：`develop/3.6` 上的 SO 统一 Overlay 实现（`codex/so-unified-overlay`）
> 状态：已实施，待代码审查

## 问题与目标

SO hot update 成功后，进程内的 `DeployFileStateTracker` 仍持有 NativeLib，但 `CompileContextDb.updateDeployedData()` 对 NativeLib 执行 no-op。关闭并重新打开工程后，恢复的部署历史缺少 SO。若设备状态恢复需要重装 APK，`resetAfterReinstall()` 只能重放 Dex、资源和 Asset；先前仅存在于设备 overlay 中的 SO 会丢失。

目标是让成功部署的 NativeLib 与其它已部署产物一样跨会话恢复，并在重装后的 follow-up deploy 中按当前 SO hot update 能力重放。历史记录必须保留目标 APK 与相对路径，base APK 和 split APK 的同名 SO 不得相互覆盖。

## 实现

1. `CompileContextDb` 在部署成功提交历史时，将 NativeLib 按 APK scope 拷贝到 `deployed/native` 或 `deployed/native_<APK key>`。读取部署历史时，按已有 APK 列表还原 `CompileOutput.Type.NativeLib`、`apkPath` 和相对路径。旧数据库没有这些目录时按空历史读取，无需迁移。
2. `DeployFileStateTracker.resetAfterReinstall(replayNativeLibraries)` 继续把未被本轮 staging 覆盖的历史产物放入 staging。只有 SO hot update 开启时才放入历史 NativeLib；开关关闭时沿用 APK 基线。低于 API 26 的设备不进入增量编译和此恢复流程。当前编译产生的 staging 文件不因该过滤被删除，仍交给已有路由处理。
3. `IncrementalDeployDataDatabase` 只将 Res、Asset 计入资源 overlay 历史。NativeLib 不能改变 `isDeployedOverlaysBefore` / `isFullRes` 的语义。
4. 后续 SO 下发复用现有 `DeployDataPlanner`、APK scope 过滤、普通 SO overlay 和大型 SO file-backed transport。恢复流程不引入另一种 SO 部署格式或新设置。

## 边界与取舍

- 历史快照保存的是成功部署的文件，即使 SO 当轮写入 APK 也会留有快照。开启 SO hot update 后若因状态恢复重装，可能重复下发与 APK 中内容相同的 SO；这是正确但有额外 I/O 的保守处理。暂不增加 APK entry CRC/size 比较：它需要针对 base/split、损坏或不可读 APK 设计额外失败边界，且不是恢复正确性的必要条件。
- 大型 SO 在磁盘上增加一份历史副本，但不读入 IDE 堆；若保存快照失败，本轮部署历史提交失败，不能声称可恢复。
- 旧会话中未保存的 NativeLib 无法从历史还原；首次部署本修复后的 SO 才会形成可重放的本地快照。

## 验证

- 失败证据：先添加跨会话 NativeLib 恢复断言，在旧实现上因恢复列表为空而失败。
- `DeployHistoryManagerTest` 覆盖 base/split 同名 SO 的独立快照、路径和内容，以及 NativeLib 不污染资源 overlay 历史。
- `DeployFileStateTrackerTest` 覆盖开关/API 门槛对应的重放策略，以及当前 staging 保留。
- `JuggDeployerHelperDeployFlowTest` 覆盖可用设备的 reinstall follow-up；既有 `JuggDeployerHelperRecoverTest` 覆盖关闭开关路径；执行定向测试和 `TopLevelFlowTest` 回归。
