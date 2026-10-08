# SO 统一 Overlay 部署实施方案

> 基线：`develop/3.6`（68c097359）
> 状态：已实施，待 PR 审查
> 目标 PR：合入 `develop/3.6`

## 目标与边界

开启现有 SO hot update 开关时，普通 SO 与 dex、resource、asset 复用同一 `JuggOverlayUpdate.fileOverlays`、overlay ID 和部署缓存；超过 `Int.MAX_VALUE` 的 file-backed SO 绕过 `ByteString`，用现有 ADB push 与 app sandbox copy 传输，但最终也发布到 `code_cache/.overlay/<apkName>/lib/<abi>/`。Runtime 只从已提交的 overlay 中查找新补丁；旧 `.jugg_native` 仅用于兼容已下发的补丁和清理。开关关闭及 API 不支持时沿用 APK 更新、重签、重装。SO 生效仍需重启 App。

## 文件与责任

- `main/.../deploy/data/DeployDataGenerator.kt`：NativeLib 独立写入 `nativeLibraryOverlays`，资源 `overlays` 与 `isFullRes` 保持原语义。
- `main/.../deploy/run/JuggDeployData.kt`：新增字段，纳入 `isEmpty`、重启、APK 过滤、日志和最后分片。
- `idea/.../deploy/run/JuggDeployerHelper.kt`：按开关和 API 在 APK 更新前选路；删除普通 SO sandbox planner 与提前推送；失败不伪造历史。
- `idea/.../deploy/run/applychanges/OverlayUpdateBuilder.kt`：普通 SO 使用现有 APK scope 加入 `fileOverlays`；file-backed SO 不进入 `ByteString`。
- `idea/.../deploy/run/applychanges/JuggDeployer.kt` 与 `main/.../deploy/nativesandbox/NativeSandboxWriter.kt`：大型 SO 先暂存，通用 overlay 成功后发布到相同目标路径，再提交 deployment cache；发布失败恢复旧文件，整轮失败。rootless 且无法写 sandbox 时，大型 SO 明确失败。
- `idea/.../deploy/hotreload/DirectAppSandboxDeployTransport.kt`：NativeLib 要求进程重启，不触发资源刷新。
- `jvmti_agent/.../hotfix/NativeLibraryPathInstaller.java`：按进程 ABI 与 base/split 顺序扫描已提交 `.overlay` 的 SO 目录，幂等注入；旧 `.jugg_native` 仅作低优先级兼容读取。
- 设置与 UI：保留开关，删除待同步标记；切换时明确提示 `pm clear` 并复用 `forceReInstallNextTime()`。
- 删除 `NativeSandboxDeployPlanner.kt` 及只保护已删除行为的测试。

## 提交与失败契约

大型 SO 暂存到非生效目录；通用 overlay 返回成功后替换同名目标文件。替换前保留旧文件供失败恢复，全部发布成功后才提交 deployment cache、deploy history 和文件状态。传输或发布失败时清理本轮暂存；通用 overlay 已提交而本轮失败时，交给现有 overlay ID mismatch/recover 对齐。没有 sandbox 的 rootless 普通 SO 先走现有 importer，并以真实 linker 加载验证；若不生效，该场景退回 APK 更新。大型 SO 不回退 APK。跨 APK 同名库由 APK scope 隔离，Runtime 按原 ClassLoader APK/split 顺序选择。

## 验证与文档

先以当前 APK 更新与普通 SO sandbox 流程作为失败证据，按 `06_testing.md` 价值门禁在现有 owner 中先加失败断言，再修改生产代码。定向覆盖开关、scope、资源隔离、分片、普通 SO 同 batch、大型 SO 不读取 `content`、旧版恢复、缓存提交、SO 重启和 rootless 边界；执行至少一条 L3 Flow。真机用 Build ID、`findLibrary()` 与 `/proc/<pid>/maps` 验证普通、大型、连续更新、base/split、32 位进程、Direct/rootless 与失败场景。同步 `02_compile_core.md`、`03_deploy_core.md`、`03_runtime_jvmti.md`、`98_code_map.md` 和中英文 SO Wiki。

## 实施验证记录（2026-09-27）

- 基线为 `develop/3.6` 的 `68c097359`，工作分支 `codex/so-unified-overlay`。
- 首次失败证据：旧生成器把 NativeLib 无条件写入 `updateApkFiles`，普通 SO 无法作为通用 overlay；新回归断言在生产修改前先建立。首次执行被本机 Android SDK 未配置和旧 JDK 下 `:jvmti_agent:buildInstrumentJar` 的 R8 NPE 阻断，使用本机 Android SDK 与 JDK 17 后通过。
- L1：`JuggDeployDataTest`、`DeployDataGeneratorTest`、`NativeSandboxWriterTest`。L2：`OverlayUpdateBuilderTest`、`JuggDeployerHelperDeployFlowTest`、`JuggRunSettingsComponentTest` 的 SO 设置用例。L3：`TopLevelFlowTest`。各定向任务通过；`jvmti_agent:buildAgentBundle` 与 IDE Kotlin 测试编译通过。
- 大型 SO 发布中断使用真实临时文件执行 shell 脚本，验证已替换的旧库恢复、未处理的旧库保留。
- Wiki 中英文镜像校验和 VitePress production build 通过。
- 当前模拟器未装有包含 NativeLib 的 demo；真实 linker 的 `findLibrary()`、`/proc/<pid>/maps`、base/split 与 32 位进程验证仍需设备集成回归。
