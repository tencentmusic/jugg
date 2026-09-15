# Flutter JIT Overlay 缓存刷新修改方案

> 创建日期：2026-09-13
> 状态：已实现并完成验证（2026-09-13），实施结果与差异见 §11。
> 用户可见结果：Flutter Debug/JIT 的 Dart 代码通过 overlay 更新后，Jugg 自动失效 Flutter 解压缓存并完整重启 App，不重打包、不重签名、不安装 APK。

## 1. 问题与验证结论

Flutter Debug/JIT 将 Dart 代码放在 `assets/flutter_assets/kernel_blob.bin`。Jugg 当前能够完成 Flutter 局部编译和 overlay 更新，但 Flutter Android embedding 会把以下文件解压到 App 私有目录后直接使用：

- `app_flutter/flutter_assets/kernel_blob.bin`
- `app_flutter/flutter_assets/vm_snapshot_data`
- `app_flutter/flutter_assets/isolate_snapshot_data`

`ResourceExtractor` 通过 `app_flutter/res_timestamp-<versionCode>-<lastUpdateTime>` 判断是否需要重新解压。Overlay 更新不会改变 APK 的 `lastUpdateTime`，所以仅重启 App 时 timestamp 仍匹配，Flutter 继续加载旧的 `app_flutter` 文件。

2026-09-13 已在 `/Users/wormchen/IdeaProjects/demo/jugg_flutter_native_demo` 和 `emulator-5554` 完成定向验证：

| 位置 | 验证前 marker | MD5 |
|---|---|---|
| Base APK `kernel_blob.bin` | `JUGG_FLUTTER_DART_MARKER_V4` | `62244bb4e81f2e25648692a93f5d5c72` |
| Jugg overlay `kernel_blob.bin` | `JUGG_FLUTTER_DART_MARKER_V99` | `73218e03f1fd285b0e0511ff5204b8cb` |
| `app_flutter` `kernel_blob.bin` | `JUGG_FLUTTER_DART_MARKER_V4` | `62244bb4e81f2e25648692a93f5d5c72` |

只删除目标包直属的 `app_flutter/res_timestamp-*` 后执行 force-stop 和启动：

- `app_flutter` 的 kernel 更新为 V99，MD5 与 overlay 完全一致。
- Flutter UI 展示 `JUGG_FLUTTER_DART_MARKER_V99`。
- Flutter 自动重建 timestamp。
- 日志确认 BootstrapApplication/Jugg startup agent、JVMTI agent 均早于 `ResourceExtractor` 解压。

因此已排除“Flutter 解压早于 Jugg overlay 可见”的时序风险。无需增加 JIT mini-bundle 上传，也无需直接改写 `app_flutter` 中的大文件。

## 2. 目标与非目标

### 2.1 本次目标

- 只在本轮实际编译并部署 Flutter JIT runtime asset 时触发缓存失效。
- 等全部 overlay 分片成功后，删除对应应用的 `app_flutter/res_timestamp-*`。
- 完整重启 App，让 Flutter 从已生效的 AssetManager overlay 重新解压 runtime 文件。
- 支持 Retry、多 APK、多设备、普通 Apply Changes、Direct Overlay 和现有 Compat Deploy 编排。
- 删除失败时明确结束为部署失败，不允许报告成功后继续运行旧 Dart。

### 2.2 明确不做

- 不重打包、重签名或安装 Debug APK。
- 不上传第二份 `kernel_blob.bin`，不直接复制或覆盖 `app_flutter/flutter_assets`。
- 不实现 Flutter Tool VM Service Hot Reload 协议。
- 不承诺只重启 Activity 生效；Flutter JIT runtime 变化必须完整重启进程。
- 不改变 Flutter Profile/Release AOT 的 `libapp.so` 更新流程。
- 不修改 Flutter SDK、Flutter module 或宿主 App 代码。
- 不新增配置开关、持久化字段、数据库迁移或单实现接口。
- 不顺带重构普通资源、Compose、native library 或现有 overlay 传输逻辑。

## 3. 方案对比

| 方案 | 正确性 | 代价 | 结论 |
|---|---|---|---|
| Overlay 成功后删除 Flutter timestamp，再完整重启 | 已由 V4/V99 设备证据验证；由 Flutter 自身完成完整解压 | 修改范围小，只增加一次私有目录操作 | **采用** |
| 生成 JIT mini-bundle，上传并原子覆盖 `app_flutter` | 可行，但需要重复传输约 42 MB kernel，并自行维护目录、权限和回滚 | 实现与测试明显更复杂 | 排除 |
| 从 `code_cache/.overlay` 复制到 `app_flutter` | 少一次传输 | 耦合 overlay 内部目录布局和 Compat Deploy 表达 | 排除 |
| 更新 Debug APK、签名并安装 | 正确 | APK I/O、签名和安装耗时高 | 仅保留给现有 AOT/native 路径 |

## 4. 数据与行为所有权

### 4.1 精确识别 Flutter JIT runtime 变化

行为 owner 为 `DeployDataPlanner`。识别必须发生在 `DeployDataGenerator` 扩展首次 full-resource overlay 之前，防止 APK 基线中的旧 kernel 造成误触发。

从本轮 `stagingOutputs` 与对应 `deployItems` 中筛选：

1. `CompileOutput.Type.Asset`。
2. `relativeModule.externalBuildInfos` 包含 `ExternalBuildType.Flutter`。
3. 标准化后的部署路径是以下任意一个：
   - `assets/flutter_assets/kernel_blob.bin`
   - `assets/flutter_assets/vm_snapshot_data`
   - `assets/flutter_assets/isolate_snapshot_data`

在 `JuggDeployData` 增加进程内瞬态字段：

```kotlin
val flutterJitRuntimeFiles: List<DeployItem> = emptyList()
```

使用 `DeployItem` 而不是全局 Boolean，原因是它已经携带 APK 归属和 `targetApkPaths`，可以复用 `belongsToAny()` 完成多 APK、测试 APK 和多 applicationId 隔离。列表复用现有 `DeployItem`/`ByteArray` 引用，不重新读取或复制 kernel 内容。

该字段：

- 不进入部署数据库或持久化历史。
- Retry 时由仍在 staging 的输出重新构建。
- Warm-up 与 Install 数据保持空列表。
- Full Gradle reinstall 后由新的 `lastUpdateTime` 驱动 Flutter 正常解压，不依赖该字段恢复。

### 4.2 重启语义

`JuggDeployData.isNeedRestartApp` 增加：

```text
flutterJitRuntimeFiles 非空
```

结果：

- 本轮用户可见部署类型为 Hot Fix。
- 不安排 Apply Changes 的 Activity-only restart。
- 全部部署步骤结束后走现有 `restartApp` / `restartAppForDebug`。
- 当前进程即使仍运行旧 kernel，也会在完成部署后被完整停止。

### 4.3 Flutter timestamp 失效 owner

新增具体类：

```text
main/src/main/java/com/sickworm/intellij/jugg/deploy/flutter/FlutterJitCacheInvalidator.kt
```

职责保持单一：通过现有 `AppSandboxExecutor` 删除一个应用私有目录直属的 `app_flutter/res_timestamp-*`，并确认删除完成。

约束：

- Shell 命令只包含固定相对目录和固定前缀，不接受用户输入路径。
- 不删除 `app_flutter`、`flutter_assets`、kernel、overlay 或应用其他数据。
- Timestamp 不存在视为幂等成功：首次启动、前次请求已完成删除或传输重试都可以继续。
- Timestamp 删除后必须检查目录中不再存在匹配文件。
- 使用现有 RUN_AS、DIRECT_SHELL、ROOT_DIRECT、SU_ROOT 能力；`UNAVAILABLE` 明确失败。
- 不新增异常层级；复用现有部署失败传播和日志契约。
- 关键流程使用 `JuggLogger` 的 `info`，失败使用 `warn`，细节使用 `debug`，不使用 `error`。

### 4.4 部署插入点

修改 `JuggDeployerHelper.executeDeployRunTask()`：

```text
创建共享 LaunchContext
  -> 执行全部 overlay slices
  -> 所有 slices 成功
  -> 按 applicationId 过滤 flutterJitRuntimeFiles
  -> 通过共享 AppSandboxExecutor 删除 timestamp
  -> 推送必要 agent
  -> 按既有逻辑完整重启 App
  -> 检查 JVMTI / 提交部署历史
```

必须放在全部 slice 成功之后：

- 分片中途失败时不破坏当前 Flutter 缓存。
- Asset overlay、kernel 和同轮其他 Flutter assets 已经同时提交。
- Timestamp 删除后不会存在“重启却仍读旧缓存”的成功窗口。

建议放在 `push_agent` 之前，失效失败时无需继续执行后续部署工作。

## 5. 失败、Retry 与恢复

| 场景 | 行为 |
|---|---|
| 没有 Flutter JIT runtime 变化 | 完全保持当前部署流程，不访问 `app_flutter` |
| Timestamp 已不存在 | 视为成功，仍完整重启，让 Flutter 完成或重新执行解压 |
| `app_flutter` 尚未创建 | 视为无 timestamp；重启后 Flutter 从 overlay 正常首次解压 |
| Sandbox 无法访问 | 本轮部署失败，保留明确原因；禁止静默成功 |
| 删除命令或删除后校验失败 | 本轮部署失败，不继续重启并报告成功 |
| Overlay slice 失败 | 不执行 timestamp 失效，沿用现有分片失败恢复 |
| Timestamp 失效前 overlay 已提交、随后失效失败 | 设备 overlay 可能已更新但旧 App 仍可运行；下一轮沿用现有 overlay-id mismatch/recover 流程恢复，不伪造历史成功 |
| 完整 APK 安装 | `lastUpdateTime` 变化，Flutter 自行刷新，不执行额外删除 |

不增加“失败后自动重打包 APK”的新分支。当前 Debug app 正常为 debuggable，RUN_AS 是主路径；其他既有 direct/root 模式继续作为 AppSandboxExecutor 的降级能力。

## 6. 修改文件

### 6.1 生产代码

| 文件 | 修改内容 |
|---|---|
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt` | 增加 `flutterJitRuntimeFiles`；纳入 `filterForApks()`、日志和 `isNeedRestartApp`；不改变 `isEmpty` 的现有定义 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployDataPlanner.kt` | 从本轮真实 staging Flutter runtime outputs 构建字段；禁止从最终 full overlay 推断 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/flutter/FlutterJitCacheInvalidator.kt`（新增） | 使用 AppSandboxExecutor 精确、幂等地删除并校验 timestamp |
| `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | 全部分片成功后按应用执行 invalidation，再进入 agent push 与完整重启 |

无需修改：

- `ExternalBuildCompiler`：它已经正确生成并筛选变化的 Flutter assets。
- `DeployDataGenerator`：runtime 文件仍然是普通 Asset overlay。
- `DirectAppSandboxDeployTransport` / `DirectOverlayWriter`：timestamp 失效属于传输完成后的公共部署步骤。
- APK updater、zipalign、apksigner：Debug/JIT 不进入这些路径。

### 6.2 测试代码

| 层级 | 文件 | 修改前失败证据 / 修改后断言 |
|---|---|---|
| L1 | `main/src/test/java/com/sickworm/intellij/jugg/deploy/DeployDataPlannerTest.kt` | 修改前没有 JIT 标记；修改后真实 Flutter kernel staging output 被记录，普通资源或 full overlay 中的旧 kernel 不触发 |
| L1 | `main/src/test/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployDataTest.kt` | 修改前 Flutter asset 只要求 Activity restart；修改后 JIT runtime 文件要求 App restart，并按目标 APK 过滤 |
| L1/L2 | `main/src/test/java/com/sickworm/intellij/jugg/deploy/flutter/FlutterJitCacheInvalidatorTest.kt`（新增） | 验证存在/不存在 timestamp、删除校验、sandbox unavailable 和异常输出；不增加测试专用生产 seam |
| L2 Flow | `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelperDeployFlowTest.kt` | 修改前 overlay 后不会失效 Flutter 缓存；修改后顺序为 overlay 成功 → invalidation → restart，invalidation 失败返回失败且不提交成功结果 |
| L2 支撑 | 现有 `deployflow/VirtualDeployDevice.kt`、`VirtualDeployShellExecutor.kt`（仅在需要时） | 只补充执行固定 timestamp 命令所需的最小文件状态模拟，不扩展通用 shell 框架 |
| L3 | `/Users/wormchen/IdeaProjects/demo/jugg_flutter_native_demo` | Base APK 保持旧 marker，Jugg 部署新 marker；确认 APK 未重签名/重装、timestamp 被自动刷新、`app_flutter` hash 等于 overlay、UI 展示新 marker |

自动化测试保护的是可观察行为：触发范围、App restart、删除结果、失败传播和部署顺序。Shell 字符串拼接等纯实现细节不单独建立脆弱断言。

## 7. TDD 与实施顺序

1. 重新核对工作树，只修改本方案文件，保留用户现有改动。
2. 在 `DeployDataPlannerTest` 建立失败测试：真实 Flutter kernel staging output 应产生 JIT runtime 标记；普通 full overlay 不得误触发。
3. 在 `JuggDeployDataTest` 建立失败测试：JIT runtime 变化要求完整 App restart，且多 APK 过滤正确。
4. 新增 invalidator 行为测试，先确认当前缺少 timestamp 删除能力。
5. 最小修改 `JuggDeployData` 和 `DeployDataPlanner`，跑对应 L1 测试。
6. 实现 `FlutterJitCacheInvalidator`，只处理固定 timestamp 目录和结果校验。
7. 扩展 `JuggDeployerHelperDeployFlowTest`，确认修改前缺少 post-overlay invalidation，再接入 Helper。
8. 执行定向测试与 `:idea:compileKotlin`。
9. 在 Flutter demo 做 L3：将 Dart marker 更新为新值，执行 Jugg 增量部署，核对 base/overlay/app_flutter/UI 和 APK `lastUpdateTime`。
10. 同步 ai_knowledge 与 Wiki，检查中英文一致性、日志格式及 `git diff --check`。
11. 只提交本次改动，建议提交标题：`[bugfix] fix stale Flutter Dart code after debug deployment`。

## 8. 验证命令与验收标准

实施阶段按最终测试方法名定向执行，禁止无过滤全量测试：

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.deploy.DeployDataPlannerTest'
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.deploy.run.JuggDeployDataTest'
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.deploy.flutter.FlutterJitCacheInvalidatorTest'
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelperDeployFlowTest'
./gradlew :idea:compileKotlin
```

最终必须同时满足：

- 修改 Dart 后产出并部署新的 `kernel_blob.bin`。
- Base APK 内容、签名和安装时间保持不变。
- Jugg 自动删除旧 Flutter timestamp，无需人工 adb 操作。
- App 被完整重启，不只是 Activity restart。
- 重启后 `app_flutter` kernel hash 与 overlay 一致。
- UI 或确定性运行日志展示新 Dart marker。
- 普通 Android asset、普通 Flutter asset、C/C++ 和 Profile/Release AOT 路径行为不变。
- Timestamp 失效失败时部署结果明确失败，不更新部署历史为成功。

## 9. 文档同步

实现完成后同步：

- `docs/ai_knowledge/02_compile_core.md`：Flutter JIT runtime output 的识别与 staging 语义。
- `docs/ai_knowledge/03_deploy_core.md`：overlay 后 timestamp invalidation 和 App restart 顺序。
- `docs/ai_knowledge/98_code_map.md`：新增 `FlutterJitCacheInvalidator` owner。
- `docs/wiki/zh/concepts/incremental-compile/assets-native.md` 及英文镜像。
- `docs/wiki/zh/capabilities/compile/so-update.md` 及英文镜像：明确 Debug/JIT 不更新 `.so`、不重签名 APK；Profile/Release 保持 AOT 流程。
- 检查 `docs/wiki/zh/guide/compile.md` 及英文镜像中 Dart 修改的用户可见结果是否需要同步。

## 10. 残余风险

- Compat Deploy 下 Flutter `ResourceExtractor` 读取 resource APK overlay 的行为尚未使用 V4/V99 场景独立验证，实施时应通过现有 Compat L2 Flow 或补充一次设备验证确认；不因此提前增加第二套部署实现。
- Timestamp 删除发生在 overlay 已提交之后，两者无法形成单一文件系统事务。失败时必须保持“部署失败、历史不提交”，交给现有 Recover 对齐 overlay 状态。
- Flutter 若未来改变 `app_flutter` 或 `res_timestamp-*` 契约，需要结合对应 embedding 版本更新适配；首版只支持当前已由源码和设备验证的标准 Flutter Android embedding 路径。

## 11. 实施结果与验证证据（2026-09-13）

### 11.1 落地内容

| 文件 | 修改 |
|---|---|
| `main/.../deploy/run/JuggDeployData.kt` | 新增瞬态字段 `flutterJitRuntimeFiles`；纳入 `filterForApks()`、完整日志和 `isNeedRestartApp`；`isEmpty` 定义未变 |
| `main/.../deploy/DeployDataPlanner.kt` | 从本轮 staging 产物识别 Flutter JIT runtime 文件（Asset + Flutter 外部构建 + 固定部署路径），在 `DeployDataGenerator` 扩展 full overlay 之前完成 |
| `main/.../compiler/overlay/AssetOverlayCompiler.kt` | 输出保留来源模块（`CompileOutput.relativeModule`），见 §11.2 |
| `main/.../deploy/flutter/FlutterJitCacheInvalidator.kt`（新增） | 经 `AppSandboxExecutor` 删除并校验 `app_flutter/res_timestamp-*` |
| `idea/.../deploy/run/JuggDeployerHelper.kt` | 全部分片成功后、`push_agent` 与最终 restart 之前按 applicationId 执行失效 |
| `main/.../deploy/flutter/...Test.kt`（新增）、`DeployDataPlannerTest`、`JuggDeployDataTest`、`ExternalBuildFlowTest`、`idea/.../JuggDeployerHelperDeployFlowTest`、`deployflow/VirtualDeployDevice.kt` | 见 §11.3 |

### 11.2 与方案的差异（按当前代码事实调整）

1. **`relativeModule` 需要在 asset overlay 阶段保留**。方案假设 staging 产物仍能读到 `relativeModule.externalBuildInfos`，但当前 `AssetOverlayCompiler` 复制产物时不传递该字段，Flutter asset 进入 staging 后来源模块信息已丢失。因此在 `AssetOverlayCompiler` 输出上补回 `relativeModule = it.module`（仅一处元数据传递，不改变路径、类型或产物集合），并让 `DeployDataPlannerTest` 按真实产物形态构造 staging 输入、由 `ExternalBuildFlowTest` 保护该 compile→deploy 契约。
2. **失效脚本使用双引号**。命令内层用 `printf "..."` 而非 `'\n...\n'`，与 `DirectOverlayWriter` 现有脚本风格一致，同时避免 `AppSandboxExecutor.shellQuote` 在命令中产生 `'\''` 序列。
3. 字段名、owner 位置、插入点、失败语义均按方案实现，未新增接口、配置开关或持久化字段。

### 11.3 自动化验证（定向）

| 命令 | 结果 |
|---|---|
| `:main:test --tests DeployDataPlannerTest --tests JuggDeployDataTest --tests FlutterJitCacheInvalidatorTest --tests ExternalBuildFlowTest --tests ResourceCompileTest --tests DeployFilePathExtTest --tests DeployFileStateTrackerTest --tests AppSandboxExecutorTest --tests 'deploy.direct.*'` | 107 tests，0 failures |
| `:idea:test --tests JuggDeployerHelperDeployFlowTest` | 26 tests，0 failures |
| `:idea:compileKotlin` | BUILD SUCCESSFUL |
| `git diff --check` | 通过 |

修改前失败证据（同一批测试，生产代码未改时执行）：planner 返回空列表（`expected:<[assets/flutter_assets/kernel_blob.bin, ...]> but was:<[]>`）、`isNeedRestartApp=false`、`filterForApks` 未裁剪 `flutterJitRuntimeFiles`、helper 流程中 `flutterCacheInvalidationCount=0` 且 invalidation 失败不导致部署失败、`FlutterJitCacheInvalidator` 类不存在。

### 11.4 L3 设备验证（emulator-5554，`dev.jugg.demo.flutter_native.debug`）

真实设备上完整复现并验证了修复行为：

| 步骤 | 证据 |
|---|---|
| 基线 | base APK md5 `296db3ef2d48a708a377435a3d5f859b`，`lastUpdateTime=2026-09-13 09:04:43`，base kernel marker V4（md5 `62244bb4e81f2e25648692a93f5d5c72`） |
| Dart marker 临时改为 V100 后走 Jugg 增量部署 | `JuggDeployData (HOT_RELOAD)`，仅 `assets/flutter_assets/kernel_blob.bin` overlay；overlay kernel md5 `fa10fe25b52bf1c1c2ce6da398ac86a3`；App 重启后 `app_flutter` kernel 仍为旧值 `73218e03f1fd285b0e0511ff5204b8cb`（**问题现场复现**） |
| 部署后 base APK | md5、debug 签名指纹、`lastUpdateTime` 全部未变，Dart 更新未触碰 APK |
| 使用生产类失效缓存 | 真实 `AppSandboxExecutor`（RUN_AS）+ 真实 `FlutterJitCacheInvalidator` 在设备上执行：时间戳被删除，`flutter_assets` 与 kernel 内容不变，重复调用幂等成功 |
| 完整重启后 | `app_flutter/flutter_assets/kernel_blob.bin` md5 `fa10fe25b52bf1c1c2ce6da398ac86a3` 与 overlay 完全一致，timestamp 由 Flutter 自动重建，UI 显示 `JUGG_FLUTTER_DART_MARKER_V100` |
| 恢复现场 | demo `main.dart` 还原为 V99（md5 `907685ca00fdaef11556200a42bc1e5c`），再次部署 + 失效 + 重启后 UI 回到 V99，设备状态与用户工程一致 |

设备侧验证通过临时（未提交、验证后删除）的 `FlutterJitCacheInvalidatorDeviceTest` 驱动真实生产类完成；`JuggDeployerHelper` 的编排顺序、失败传播与 slice 失败不收口由 §11.3 的 L2 Flow 测试保护。

### 11.5 已知阻塞与残留

- 运行中的 Android Studio 安装的是已发布插件 `3.5.0-release`，无法在本轮改动下重新加载本地构建（插件热更新通道面向已发布 JAR，且安装本地构建需要重启 IDE 的人工步骤）。因此 L3 未走“插件内 `JuggDeployerHelper` 端到端”形态，改为设备上运行真实失效类 + L2 Flow 覆盖编排。
- 同一插件在本轮 L3 中先触发一次与本次改动无关的既有误报：`External build no longer produces assets/flutter_assets/... full Gradle build required`。原因是 `ExternalBuildCompiler` 的产物清单记录的是“上一轮发生变化的 asset 集合”，而本次只有 `kernel_blob.bin` 变化，集合变小被判定为“产物被删除”。本轮通过清除该陈旧清单文件（`build/jugg/build/compiled/external_build/external/flutter/flutter-artifacts.txt`）完成验证，未修改该逻辑（不属于本方案范围）。该缺陷建议单独跟进。
