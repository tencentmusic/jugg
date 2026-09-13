# Flutter Tool VM Service Hot Reload 初步实现方案

> 日期：2026-09-13  
> 状态：初步方案，未进入实现  
> 目标：评估 Jugg 复用 Flutter Tool Hot Reload 能力的实现方式、边界和成本。

## 1. 背景

当前 Jugg 对 Flutter Dart 变更的可靠部署路径，是重新生成并部署完整 JIT bundle，再重启 Android 应用。该路径可以避免 APK 重打包，但每次仍需要完整编译、文件部署和进程重启，无法保留 Flutter 页面状态。

Flutter 官方的 Hot Reload 并不是简单替换 APK 中的 `kernel_blob.bin`。它依赖一个持续存活的增量编译会话，并通过 DevFS 和 VM Service 把增量 kernel 应用到正在运行的 Dart isolate，最后触发 Flutter reassemble。

因此，如果 Jugg 要支持真正的 Flutter Hot Reload，需要接入 Flutter Tool 的常驻会话，而不能把一次性生成的 `.dill` 文件直接复制到设备后期待运行时自动生效。

## 2. Flutter 官方机制

Flutter Tool 的核心流程如下：

1. `ResidentCompiler` 持有 frontend server 的增量编译状态。
2. 文件变化后，DevFS 调用 resident compiler 生成增量 dill。
3. DevFS 将增量 dill 和相关资源同步到设备。
4. Flutter Tool 通过 VM Service 调用 `reloadSources`。
5. Reload 成功后调用 `ext.flutter.reassemble` 刷新 Widget 树。
6. Flutter Tool 根据结果接受或回滚本次编译状态。

首次 `flutter attach` 需要建立 resident compiler 和 DevFS 基线。官方 attach 流程通常先执行一次完整同步和 Hot Restart；之后的 Dart 变更才能使用 Hot Reload。

这意味着 Jugg 若自行实现相同能力，还需要正确处理 frontend server 状态、增量 dill、DevFS URI、多个 isolate、reload 状态提交与回滚，以及 Flutter 框架 reassemble，兼容成本较高。

## 3. 方案比较

| 方案 | 做法 | 预估成本 | 结论 |
| --- | --- | --- | --- |
| A. 托管 `flutter attach --machine` | Jugg 启动并管理官方 Flutter Tool 常驻进程，通过 machine protocol 请求 Hot Reload/Hot Restart | MVP 约 3～4 周 | 推荐 |
| B. 复用 Flutter IntelliJ 插件内部 API | 从 Flutter 插件对象和 Runner 中取得 resident session | 初期可能较快，长期版本耦合高 | 不推荐 |
| C. 自行实现增量编译与 VM Service 编排 | Jugg 直接管理 frontend server、DevFS 和 VM Service | 约 10～16 周，且持续承担 Flutter 兼容维护 | 不推荐 |

推荐方案 A。它把 Flutter 工具链协议和版本差异留给对应版本的 Flutter SDK，Jugg 只负责进程生命周期、请求编排、能力判断和状态归档。

## 4. MVP 能力边界

首版只在以下条件全部满足时进入 Hot Reload 路径：

- Flutter Debug/JIT 构建。
- 单设备、单 Flutter module。
- 本轮只有 Dart 源文件变化。
- 目标 Android 应用正在运行，并且 VM Service 可连接。
- 使用本地编译，不包含远程编译和 AndroidTest 场景。
- Flutter SDK、target Dart 文件、applicationId、variant 和构建参数已明确。

以下情况回退到既有完整编译和部署路径：

- Dart 与 Java、Kotlin、资源或 native 文件混合变化。
- Profile/Release 模式。
- Flutter assets、字体、shader 等非 Dart 内容变化。
- 多设备或多个 Flutter module。
- Flutter Tool 会话不可建立，或当前项目参数无法完整还原。

MVP 不尝试对混合变更做部分提交，避免 Dart 已生效但 Android 侧部署失败造成状态不一致。

## 5. 推荐架构

### 5.1 顶层路由

Hot Reload 应作为 `JuggRunningTask` 中普通 compile/deploy 之前的一条兄弟路径，而不是伪造为一种 `CompileOutput`：

```text
Dart-only changes
    -> eligibility check
    -> session ready?
       -> no: start flutter attach --machine
              -> initial DevFS sync + Hot Restart
       -> yes: request Hot Reload
    -> success: commit only runtime-applied Dart files
    -> reload rejected: retry once with Hot Restart
    -> unavailable: fall back to full Flutter JIT bundle deployment
    -> compile/restart failure: retain pending files and report failure
```

首次满足条件的 Jugg Run 再延迟启动 attach 会话。这样不会在用户尚未执行 Jugg Run 时，由后台会话自动把磁盘变更应用到设备。

### 5.2 会话管理

建议以以下字段作为会话键：

```text
(Flutter module root, device serial, applicationId, variant)
```

会话管理器需要负责：

- 启动和停止 `flutter attach --machine` 长驻进程。
- 解析 Flutter Tool 使用的方括号包裹 JSON machine protocol。
- 为请求分配 ID，并关联 response、event、超时和取消。
- 串行化 Hot Reload 请求；连续文件变化只保留一次可执行请求。
- 记录 `app.start`、`app.debugPort`、`app.started`、`app.log` 和 `app.stop` 等事件。
- 在项目关闭、设备断开、应用退出或构建上下文变化时使会话失效。

建议失效条件包括：

- 收到 `app.stop`。
- Android 进程重新启动或设备断开。
- applicationId、variant、Flutter SDK、target 或编译参数变化。
- 执行完整 Gradle 构建。
- 项目关闭或插件释放。

### 5.3 Machine protocol 请求

建立会话后，Jugg 可发送 `app.restart` 请求：

```json
[{
  "id": 1,
  "method": "app.restart",
  "params": {
    "appId": "<flutter-app-id>",
    "fullRestart": false,
    "reason": "jugg",
    "debounce": true
  }
}]
```

其中：

- `fullRestart=false` 表示 Hot Reload。
- `fullRestart=true` 表示 Hot Restart。
- 首次 attach 建立基线时接受 Hot Restart。
- Hot Reload 返回非零结果时，只重试一次 Hot Restart；该重试改变了失败条件，符合 Best-effort 原则。

### 5.4 项目模型补充

当前 `ExternalBuildInfo` 主要描述 source、task、assets/native output 和 Gradle inputs，不足以等价启动 Flutter Tool。正式实现前需要确认并最小化补充以下信息：

- Flutter SDK 路径。
- Flutter module root 和 target Dart 文件。
- build mode、variant、applicationId。
- Dart defines。
- filesystem roots 和 filesystem scheme。
- frontend server options，例如 package config、initialize-from-dill 或 local engine 参数。

这些参数应从现有 Gradle/Flutter 构建上下文读取，不能由 Jugg 猜测。参数不完整时应直接回退可靠路径。

### 5.5 文件状态与结果语义

Hot Reload 成功后没有普通的 Asset/DEX/Native `CompileOutput`，但本轮 Dart 文件已经应用到运行时。因此需要在现有文件状态管理中增加一个窄能力，例如：

```text
commitRuntimeAppliedFiles(dartFiles)
```

该能力只提交成功应用的 Dart 文件快照，清除对应 pending 状态，并保留部署历史和恢复语义；不得伪造 Asset 或 DEX 输出。

用户可见结果建议区分：

- `FLUTTER_HOT_RELOAD`
- `FLUTTER_HOT_RESTART`
- `FLUTTER_BUNDLE_RESTART`

若编译失败、Hot Restart 失败或会话异常，Dart 文件必须继续保持待处理状态，不能记录为成功。

## 6. 预计代码影响范围

以下只是预估落点，不代表本次已经确定接口或开始实现。

现有代码可能涉及：

- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`：增加顶层能力判断和分流。
- `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt`：补充 Flutter Tool 所需项目元数据。
- `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt`：读取 Flutter 构建上下文。
- `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/ProjectInfoSerializerInGradle.kt`：序列化新增元数据。
- `main/src/main/resources/gradle/readProjectInfo.gradle.kts`：从 Gradle/Flutter task 提取确定性参数。
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt`：提交运行时已应用文件。
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileStateTracker.kt`：维护 Dart 文件状态和恢复语义。

建议新增的最小实现单元：

- `FlutterMachineProcess`：进程和 machine protocol。
- `FlutterHotReloadSessionManager`：会话生命周期和请求编排。
- `FlutterHotReloadEligibility`：纯能力边界判断；仅在判断逻辑足够复杂且可独立测试时保留。

不预设接口层。只有出现多个真实实现或明确业务边界时才增加接口。

## 7. 验证方案

该能力具有稳定、用户可观察的行为，并涉及外部进程协议、状态机和失败回退，自动化测试价值较高。实现阶段建议按以下层级验证：

| 层级 | 建议 owner | 关键场景 |
| --- | --- | --- |
| L1 | `FlutterMachineProcessTest` | 方括号 JSON response/event 解析、请求关联、超时、进程停止 |
| L1 | `FlutterHotReloadEligibilityTest` | Dart-only 命中；混合变更、非 Debug、多设备不命中 |
| L2 | Session manager test | 首次 attach、后续 reload、一次 Hot Restart 重试、会话失效 |
| L2 | `JuggRunningTaskTest` | Hot Reload 成功时跳过普通编译；失败保留文件；不满足条件时走原路径 |
| L3 | `jugg_flutter_native_demo` Flow | 首次 Hot Restart、第二次 Hot Reload 保留页面状态、进程重启后重建会话、不可用时可靠回退 |

POC 至少需要取得以下证据：

1. 第一次 Dart 变更建立 attach 会话并完成 Hot Restart。
2. 第二次 Dart 变更使用 Hot Reload，且 Widget 状态得以保留。
3. 修改 `const` 等需要重新编译的代码后，运行时能观察到新值。
4. 模拟 reload rejected 后只执行一次 Hot Restart。
5. Flutter Tool 不可用时，不误报成功，并能回退到完整 JIT bundle 路径。

## 8. 成本预估

以下按一名熟悉 Jugg、需要补充 Flutter Tool 机制认知的工程师估算：

| 阶段 | 范围 | 预估 |
| --- | --- | --- |
| POC | 固定 demo、单设备、固定参数，验证 attach machine protocol 和连续 Hot Reload | 3～5 人日 |
| 严格 MVP | 项目元数据、进程协议、会话生命周期、Dart-only 分流、状态提交、一次重试和自动化验证 | 3～4 周 |
| 可发布首版 | 补齐异常恢复、Flutter 版本兼容、日志、用户提示和真实项目回归 | 4～6 周 |
| 扩展能力 | 多设备、多 module、assets、复杂 Flutter 参数矩阵 | 累计 7～10 周 |

运行时预期需要由 POC 实测，初步目标可设为：

- 首次 attach 和 Hot Restart：约 2～8 秒。
- 后续小型 Dart Hot Reload：约 0.3～2 秒。
- 常驻 Flutter Tool、frontend server 和 DDS 会占用数百 MB 内存，需要记录实际峰值和空闲占用。

## 9. 风险与待确认项

- 不同 Flutter 版本的 machine protocol 和 attach 参数兼容性。
- 如何从 Android/Gradle 模型完整还原 Flutter Tool 编译参数。
- Flutter Tool attach 与用户已有 IDE Flutter Run/Debug 会话是否冲突。
- 应用进程、VM Service URI 和 Flutter appId 的稳定关联方式。
- Hot Reload 成功后，Jugg 部署历史和崩溃恢复应记录到什么粒度。
- 首次 attach 的 Hot Restart 是否符合用户对“本次 Run”的耗时预期。
- 常驻进程的 CPU、内存、日志量以及项目关闭时的资源释放。

这些问题应先通过固定 demo POC 验证，再确定正式接口。POC 未通过前，不扩展多设备、assets 或自研协议能力。

## 10. 本次不实现的内容

- 不修改 Jugg 生产代码和测试。
- 不启动或托管 Flutter Tool 常驻进程。
- 不实现 frontend server、DevFS 或 VM Service 协议。
- 不调整当前 Flutter bundle 部署和 APK 重签名行为。
- 不承诺以上类名、接口和工期为最终设计。

## 11. 调研依据

Jugg 文档与代码：

- `docs/ai_knowledge/00_overview.md`
- `docs/ai_knowledge/02_compile_core.md`
- `docs/ai_knowledge/03_deploy_core.md`
- `docs/ai_knowledge/03_runtime_jvmti.md`
- `docs/ai_knowledge/06_testing.md`
- `docs/ai_knowledge/98_code_map.md`
- `docs/ai_knowledge/99_index.md`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileStateTracker.kt`

本机 Flutter 3.47.2 源码：

- `/Users/wormchen/development/flutter_3.47.2/flutter/packages/flutter_tools/lib/src/compile.dart`
- `/Users/wormchen/development/flutter_3.47.2/flutter/packages/flutter_tools/lib/src/devfs.dart`
- `/Users/wormchen/development/flutter_3.47.2/flutter/packages/flutter_tools/lib/src/run_hot.dart`
- `/Users/wormchen/development/flutter_3.47.2/flutter/packages/flutter_tools/lib/src/commands/attach.dart`
- `/Users/wormchen/development/flutter_3.47.2/flutter/packages/flutter_tools/lib/src/commands/daemon.dart`
- `/Users/wormchen/development/flutter_3.47.2/flutter/packages/flutter_tools/doc/daemon.md`

## 12. 实施门禁

后续只有在确认以下事项后才进入实现：

1. 接受“首轮 Hot Restart、后续 Hot Reload”的用户体验。
2. 接受 MVP 只覆盖单设备、单 module、Dart-only Debug 变更。
3. POC 能稳定通过连续 reload、进程重建和失败回退验证。
4. 明确 Flutter 构建参数的数据来源和会话冲突处理方式。
5. 评审确认文件状态提交语义，不会破坏既有 compile/deploy 恢复机制。
