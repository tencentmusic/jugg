# Direct sandbox 资源部署修复与验证

## 问题与范围

Direct app sandbox transport 在写入前拒绝任何非 class-only payload，导致系统应用的资源变化直接报错。去掉限制后还必须恢复全量资源标志、补齐运行时资源加载、避免把混合变化误判为纯在线方法更新。

本次修改：

- 删除 transport 的 class-only 白名单；APK 更新继续由既有上游改写、重签和安装流程负责。
- 将 `JuggDeployData.isFullRes` 传入 Direct Overlay writer。
- 迁移 Android Studio 的 `ResourceOverlays` 与 `LoadedApk.getResources()` hook。Android 11+ 使用 `ResourcesProvider.loadFromDirectory()` 加载资源和 assets；Android 8～10 与 compat flag 场景保持原资源 APK 路径。
- Direct transport 准备 startup agent 后写 `.jugg_direct_resource_overlay`。Agent 使用真实 dataDir 初始化，并核对宿主 APK 路径；未标记应用和非宿主 Resources 不接入 loader。
- 只有纯方法体、没有资源/APK 更新且不要求进程重启的 payload 才尝试在线 redefine。
- Agent 版本更新为 `1.0.62`，避免设备继续复用旧版本资源加载代码。

迁移来源：[AOSP ResourceOverlays.java](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/deploy/agent/runtime/src/main/java/com/android/tools/deploy/instrument/ResourceOverlays.java)、同目录 `Overlay.java` / `InstrumentationHooks.java` 与 `deploy/agent/native/instrumenter.cc`。保留原 Apache 2.0 声明。

## 失败证据与测试价值

原 transport 定向回归中，纯资源、代码与资源混合、全量资源 replay 均触发 class-only 异常；overlay-only class replay 错误返回无需重启。新增断言验证用户可观察的部署终态、是否进入在线 redefine，以及实际 writer request 的文件与全量资源语义。复用现有 owner，不在生产代码加入测试专用依赖或回调。

`ResourceOverlays` 依赖 Android ResourcesLoader 和真实资源表，Mockito 无法证明资源值实际变化，采用 Android 15 设备对照验证。

## 自动化与构建

| Owner | 层级 | 结果 |
|---|---|---|
| `DirectAppSandboxDeployTransportTest` | L2 | 7 通过；覆盖纯资源、混合变化、全量资源 replay、纯方法体、强制重启和普通 AS 路由 |
| `JuggDeployerHelperDeployFlowTest` | L2 Flow | 22 通过；覆盖部署、recover/reinstall 和状态提交 |
| `DirectOverlaySwapTransportTest` | L1 | 10 通过；同步假设备的 run-as 成功标记与 SELinux context 协议 |
| `:jvmti_agent:buildAgentBundle` | 构建 | Java、arm64/armeabi-v7a native 与 bundle 通过；共享工作区也完成构建 |
| Wiki | 文档 | 中英文镜像与链接检查、production build、`git diff --check` 通过 |

隔离工作树只包含本次补丁，39 个定向测试全部通过；共享工作区包含并行 Hot Reload 修改时，相同 39 个测试也通过。隔离工作树的首次全新构建遇到旧 AGP D8 处理既有 `ViewHierarchyServer$1.class` 的 NPE，改用本机 SDK 35.0.0 的 D8 按原输入生成 instruments JAR 后完成验证；未修改工程 D8 依赖。Flow 运行需要显式设置 `ANDROID_HOME`。

## 设备对照

设备：Android 15 / API 35 / arm64 模拟器，目标 `com.jugg.demo.privapp`，UID 10148，具有 SYSTEM、PRIVILEGED、UPDATED_SYSTEM_APP。以实际安装 APK 的资源表为基线，保持条目长度，把 `Priv App Demo` 修改成 `Jugg Res PASS`，并保留已有 class overlay。另建 assets-only 目录验证无资源表时的 asset 加载。

| 场景 | 可判定结果 |
|---|---|
| 旧 1.0.61 Agent 已成功完成 instrumentation，写入新资源后冷启动 | 仍显示 `Priv App Demo`，复现缺少资源加载 |
| 新 1.0.62 Agent 冷启动 | 显示 `Jugg Res PASS` |
| 同一目标第二轮替换资源表并冷启动 | 显示 `Jugg Res NEXT`，无旧 loader 缓存 |
| 实际 Android Resources + assets-only 目录 | 资源值为 `Jugg Res PASS`，asset 内容为 `direct-assets-pass` |
| 无 Direct 标记 | 原资源不变，不添加 loader |
| 非宿主 Resources / 重复添加宿主资源 | 系统 Resources 的 loader 数不变；宿主只保留一个 loader |

测试先重命名保留完整 `code_cache`，在复制的缓存上验证，finally 恢复原缓存和 startup agent。现场旧版全局 instruments JAR 存在 SELinux `{ map }` 拒绝；为使资源对照成立，验证期间仅临时将新旧 JAR 标签统一为可映射类型，结束后恢复原标签。该 JAR 路径问题不属于本次资源补丁，不能用这次设备验证宣称它已被修复。

本轮是实际 startup agent + 资源的设备验证，以及 Host 虚拟部署 Flow；未把手工缓存写入等同于 IDE 点击 Run 的完整端到端验证。设备现场和测试脚本保存在本机 `/tmp/jugg-direct-resource-verification/`。

## 依据

- 知识库：`00_overview.md`、`99_index.md`、`98_code_map.md`、`09_plugin_runtime_debug.md`、`03_deploy_core.md`、`03_deploy_system_app.md`、`03_runtime_jvmti.md`、`06_testing.md`、`10_wiki_authoring.md`、`10_wiki_architecture.md`。
- 历史方案：`docs/task/2026-09/root_system_app_jvmti_hot_reload_plan.md`。
- Wiki：中英文 Direct Overlay concept/capability、Hot Reload、Jugg JVMTI Agent，以及中文 concepts/deploy index。
- 源码：Direct sandbox transport、Direct Overlay writer/builder、deploy helper、兼容资源 APK generator/loader、Jugg startup agent 和对应测试。
- 日志：仓库与 SystemAppDemo 的 compile_latest；本次隔离/共享 Gradle 日志、设备 UI XML、目标进程 logcat。


## 补充评审：范围收敛与切片一致性

| 要求 | 评审证据与结论 |
|---|---|
| 纯资源、代码＋资源可以 Direct；Manifest/so 保持 APK 路径 | `DirectAppSandboxDeployTransport.tryDeploy()` 已删除 class-only 白名单。`JuggDeployerHelper.deployIncrementalChanges()` 在 runTask 前仍以 `isNeedUpdateApk` 调用 `IncrementalDeployHelper.updateApk()` 并走 recover/install；本次不更改 APK 更新逻辑。 |
| 全量资源标志与清理优化 | `data.isFullRes` → builder → `DirectOverlayWriteRequest.isFullResourcePush`。Writer 已有逻辑跳过 `base.apk/` 下逐文件删除，不删除整个 base 目录；其他目标按原规则清理。无需增加全量清理策略。 |
| Agent 打包、更新和资源生效 | 1.0.62 bundle 的 instruments DEX 包含 `ResourceOverlays`，ARM64/ARM32 SO 包含资源 hook。补充设备验证通过生产 `JuggJvmtiAgentManager.pushAgentToApp()` 从无设备端 bundle 的状态执行 push/setup，再使用生产 writer 部署；无手工 JAR label 修复。 |
| 纯在线方法更新的语义 | `isPureHotReload` 已排除 overlays/updateApkFiles、new/hotfix classes、`isNeedRestartApp`、install 和 compat；`isPushOverlayOnly` 通过非空数据的 `isNeedRestartApp` 生效。新增 reinstall replay、compat 以及 Manifest/so 混合数据的回归，均要求重启且不进入在线 redefine。 |
| 上层切片与 Direct 通道一致 | **发现遗漏并修复**：上层原先只检查普通 `DirectOverlaySwapTransport.canTry()`，系统应用在 ready/禁用普通 Direct 时仍被拆片。新增回归在修复前得到 expected 1 / actual 3 次写入。现在上层与最终 Direct sandbox transport 共用 `canTry(packageName)`，复用 LaunchContext 缓存的 sandbox 能力；任一 Direct 候选整批部署，官方 Apply Changes 保留原切片及失败清理。 |

本次补充仅修改两个生产文件：Direct sandbox transport 提取可复用的候选判断，JuggDeployerHelper 在切片前调用它。未新增 Direct 切片、分片结果汇总、资源格式、Agent 协议或权限修复重构。普通 Direct 在前置失败后回落官方 Apply Changes 的既有行为保持不变。

### 补充验证

- 隔离工作树定向回归：`DirectAppSandboxDeployTransportTest` 9、`JuggDeployerHelperDeployFlowTest` 23、`DirectOverlaySwapTransportTest` 10、`DirectOverlayWriterTest` 10，共 **52 个通过**。Flow 验证系统 Direct 只写一次并重启、普通 Direct 只写一次、官方 Apply Changes 仍分三片且仅末片重建 Activity；权限边界由 Mockito 隔离，未新增生产测试 seam。
- 当前共享工作区集成回归：上述四个 owner 加 `JuggJvmtiAgentManagerDirectSandboxTest`，包含另一任务已有 Activity relaunch 分支，共 **54 个通过**。Agent 构建完成；知识库差异检查、中英文 Wiki 校验和 production build 通过。
- Android 15 系统应用设备验证分两轮：第一轮使用本资源补丁的隔离 Agent + 生产 writer；第二轮使用当前共享工作区的生产 Agent push/setup + 生产 writer。都验证 Dex 首次写入 → 首次 full resource → Dex 与资源再次写入 → 再次冷启动，资源依次显示 `Priv App Demo`、`Jugg Res PASS`、`Jugg Res NEXT`；旧 Dex 与未变化 assets 保留。
- 第二轮包含另一任务已有的 instrumentation JAR 入 sandbox 修复，并验证该链路与本资源补丁兼容；该并行修改不计入本次提交。独立资源补丁本身不能被表述为已经修复全局 JAR 的 SELinux 可读性问题。
- 设备验证先备份完整 code_cache 与设备端 1.0.62 bundle，结束后恢复原 cache、startup agent、bundle，并确认原资源值。验证驱动及日志位于 `/tmp/jugg-direct-resource-review/`；临时设备 verifier 通过 Gradle init script 注入外部测试源，不作为常规单元测试提交。
- 这仍是 Agent/Writer 的实际设备验证与 Host Flow 回归，未执行 IDE 点击 Run 的完整编译部署端到端链路。

### 超时和性能检查

`DirectOverlayWriter` → `AppSandboxExecutor.execNoFallback()` → `IdeaDeviceAdb.execAdbShellScriptNoFallback()` → `invokeAdbShellCmd()` 仍给 ADB 调用传 `timeout=5L, timeUnit=SECONDS`。Heartbeat 只产生周期输出，不代表调用完全没有超时；本次没有修改超时参数、重试或失败语义。设备验证驱动自身为每条 ADB CLI 命令设置 30 秒硬超时，不把它与 IDE 的 5 秒参数混同。

`AppSandboxExecutor.buildRepairScript()` 仍在命令退出时对整个 code_cache 做权限/label 修复。测试数据为 29 个文件，三次只读 find 的 Host/ADB 往返耗时 59/42/40 ms；该数字不代表完整递归 chown/chcon 耗时，也不足以推断大工程性能。本项只记录潜在随文件量增长的成本，不自动扩展为重构。

补充依据：`03_deploy_complete.md` 的 runTask/lifecycle 说明；其余已读文档与 Wiki 页面见上方“依据”。同时修正 `03_deploy_core.md` 中以 Direct 切片解释 full resource 清理的旧描述，并同步中英文 Direct Overlay concept 页面。
