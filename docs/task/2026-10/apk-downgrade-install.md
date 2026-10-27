# APK downgrade installation

Purpose: Let manual IDE users preserve application data when installing an older APK through Jugg.
Decision: Approved on 2026-10-27. Add an IDE downgrade choice; the final user decision restores CLI/MCP automatic uninstall and reinstall.
Impact: Adds a manual IDE install choice while preserving the original CLI/MCP data-clearing reinstall behavior; no new CLI option is proposed.

## 状态

- 创建日期：2026-10-27。
- 当前阶段：已按批准范围完成实施、定向验证及只读实现审查。
- 实施开始时分支仍为 `develop/4.0`，已有四个本地领先提交；保留其内容。
- 工作区：`jugg_f2`，分支 `develop/4.0`；开始调研时工作区干净，已有三个本地领先提交。

## 已确认的现状

1. IDE 和 standalone 安装参数均未调用 `setAllowDowngrade()`。
2. 安装器收到 `INSTALL_FAILED_VERSION_DOWNGRADE` 后通过 Boolean prompt 询问是否卸载。同意后卸载旧应用并重新安装，应用数据被删除。
3. IDE 手动 Run/Debug 通过 `IdeService.prompt()` 询问；MCP 模式和 standalone 的 UI handler 会自动同意安装提示。
4. CLI 是 MCP 客户端，可以连接 IDEA 或 standalone。`deploy` 当前仅有 `alwaysRestartApp` 命令参数；不存在降级安装或卸载策略选择参数。
5. Android Studio 版本适配器有多条安装入口，但共享两组安装参数构建逻辑：Chipmunk 系列和 Quail 系列。Standalone 有单独的参数构建逻辑。

## 拟定行为

### IDE 手动 Run / Debug

仅在安装器报告版本降级时，将原来的卸载确认扩展为三个动作：

| 动作 | 行为 | 数据影响 |
| --- | --- | --- |
| 允许降级安装 | 带等价于 `adb install -d` 的选项重新安装一次 | 不主动清除应用数据 |
| 卸载重装 | 继续现有安装器的卸载、安装流程 | 明确提示会删除应用数据 |
| 取消 | 本次安装失败结束 | 保留已有应用及数据 |

弹窗正文及按钮使用项目现有英文 UI 风格，对应 `Allow downgrade`、`Uninstall and reinstall`、`Cancel`。关闭弹窗按取消处理。

选择降级后，如果设备仍拒绝安装，报告最终失败，不因同一版本降级原因自动转为卸载重装，也不反复弹窗。

### CLI / MCP

- 按用户最终决定，IDEA Runtime 和 standalone Runtime 遇到版本降级安装失败时均默认自动卸载重装，删除应用数据。
- 默认不启用 `-d`；保留数据的允许降级选项仅供手动 IDE Run/Debug 选择。
- 本次不增加 CLI 参数或全局持久化设置。用户原文中的“目前是否支持选择”作为现状问题回答：当前不支持。

### 范围边界

- 适用于 Jugg 默认安装器，包括全量构建后的安装和增量流程触发的 APK 安装/恢复。
- 其他安装错误沿用现有处理，例如不同签名的确认流程。
- 自定义 APK 安装脚本保持由脚本决定安装方式。
- `clean-reinstall` 仍履行其显式清数据的语义。
- Android 对 `-d` 有系统限制；官方 adb 手册标注该选项适用于 debuggable packages。Jugg 负责传递选项并保留系统返回的最终失败。

## 最小实现设计

1. 在现有 `JuggInstallSession` 所在文件定义安装提示动作，并保存本次安装会话是否允许降级。不引入新的服务、配置存储或公共接口。
2. `CompileUiHandler.confirmDeployPrompt` 确认边界返回明确的提示动作。IDE 对版本降级提供三个按钮；CLI/MCP 对版本降级自动同意卸载重装。其他 prompt 的原有 Boolean 语义在适配边界保持不变。
3. Android Studio 安装器的 `UIService.prompt()` 仍返回 Boolean：卸载动作返回 true；取消或请求降级返回 false。请求降级同时记录会话状态，使原安装器不执行卸载。
4. 共享安装器只在本次用户选择使安装条件从“不允许降级”变为“允许降级”时重新安装一次。复用原 APK、安装模式、缓存提交和现有离线恢复流程。
5. CLI/MCP 在 `confirmDeployPrompt` 中默认同意卸载重装，不预设降级选项。手动 IDE 选择允许降级后，Quail 和 standalone 通过 `setAllowDowngrade()` 传递会话选项，legacy 兼容层通过现有 `setUserInstallOptions("-d")` 传递等价参数，保持旧版 API stub 不变。
6. 降级失败识别集中在现有会话/提示边界，核对真实安装器的状态和提示文本。避免依赖旧版 IDE handler 固定的“签名不同”日志来判断错误原因。

## 预计修改的现有文件

以下路径均相对于仓库根目录。

### 会话与共享安装

| 文件 | 职责 |
| --- | --- |
| `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployCompatTypes.kt` | 提示动作、会话降级选项和 Boolean 安装器适配 |
| `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IApplyChangesExecutor.kt` | 会话创建的提示回调使用明确动作类型 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileUiHandler.kt` | 完整安装确认返回明确动作，无 UI 默认取消 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/IDeployHost.kt` | 移除安装确认职责，保留设备部署能力 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/LaunchContextFactory.kt` | 直接绑定 CompileUiHandler 确认回调 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt` | 用户选择降级后的单次重试和最终错误传播 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/utils/AdbLogWrapper.kt` | 在每次实际安装前清理上一尝试的错误，避免首次降级错误遮盖最终异常 |

### IDE 与 standalone Host

| 文件 | 职责 |
| --- | --- |
| `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandler.kt` | IDE 三动作提示、其他安装确认及 RPC 自动卸载决策 |
| `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/IdeaDeployEnvironment.kt` | 移除已迁入 handler 的确认实现 |
| `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt` | 提示回调类型透传 |
| `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneCompileUiHandler.kt` | 非交互安装确认返回 UNINSTALL |
| `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneDeployEnvironment.kt` | 移除确认策略与纯转发 |

### 安装器兼容层

| 文件 | 职责 |
| --- | --- |
| `deploy_compat/v_chipmunk/src/main/java/com/sickworm/intellij/jugg/deploy/run/ChipmunkAsDeployerCompat.kt` | 会话回调类型及共享安装参数支持允许降级 |
| `deploy_compat/v_giraffe/src/main/java/com/sickworm/intellij/jugg/deploy/run/GiraffeAsDeployerCompat.kt` | 将会话选项传给参数构建逻辑 |
| `deploy_compat/v_iguana/src/main/java/com/sickworm/intellij/jugg/deploy/run/IguanaAsDeployerCompat.kt` | 同上 |
| `deploy_compat/v_narwhal/src/main/java/com/sickworm/intellij/jugg/deploy/run/NarwhalAsDeployerCompat.kt` | 同上 |
| `deploy_compat/v_narwhal_feature/src/main/java/com/sickworm/intellij/jugg/deploy/run/NarwhalAsDeployerFeatureCompat.kt` | 同上 |
| `deploy_compat/v_quail/src/main/java/com/sickworm/intellij/jugg/deploy/run/QuailAsDeployerCompat.kt` | 会话回调类型及新版安装参数支持允许降级 |
| `deploy_compat/standalone_deployer/src/main/java/com/sickworm/intellij/jugg/deploy/run/StandaloneApplyChangesExecutor.java` | Standalone 回调类型及真实安装参数支持允许降级 |

继承以上实现的 Hedgehog、Meerkat、Otter、Panda 和 Rabbit 不重复增加策略代码。预计不修改第三方 `ApkInstaller.java` 或 stub API JAR。

### 测试与现有夹具

| 文件 | 职责 |
| --- | --- |
| `idea/src/test/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandlerTest.kt` | 降级弹窗各动作、关闭/取消、普通安装确认和 RPC 无弹窗卸载决策 |
| `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployerInstallTest.kt` | 降级重试、取消、最终失败、成功后的缓存提交及普通安装回归 |
| `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/LaunchContextFactoryTest.kt` | 更新提示回调夹具，保留现有上下文及部署条件回归 |
| `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/TestDeployEnvironment.kt` | 适配 Host 提示动作契约 |
| `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/deployflow/DeployFlowAsDeployerCompatBoundary.kt` | 适配会话回调契约 |
| `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelperDeployFlowTest.kt` | 必要的完整部署 Flow 回归，确认失败时不推进成功状态 |
| `deploy_compat/standalone_deployer/src/test/java/com/sickworm/intellij/jugg/deploy/run/StandaloneApplyChangesExecutorTest.kt` | 适配回调夹具，在真实参数边界验证 `-d` |

其他构造 `JuggInstallSession` 的现有测试只做必要的签名适配，不增加镜像测试。实施前检索全部调用者，若出现超出上述安装边界的实质改动，重新审阅范围。

### 文档与新增文件

- 新增文件仅本任务记录：`docs/task/2026-10/apk-downgrade-install.md`；无需新增生产类文件。
- 更新 `docs/ai/03_deploy_core.md`，记录 IDE/CLI 默认行为、确认边界和有界重试。
- 更新 `docs/ai/08_cli_tools_list.md`，说明 CLI 安装低版本时默认卸载重装和目前无选择参数。
- 更新 `docs/wiki/concepts/apk-update-and-install.md` 与 `docs/wiki/zh/concepts/apk-update-and-install.md`。
- 更新 `docs/wiki/guide/cli.md` 与 `docs/wiki/zh/guide/cli.md`。
- 本方案不变更 CLI 脚本、参数或安装技能资源，因此不提升 CLI/skill 版本。

## 验证方案

1. 在现有安装测试所有者处先建立失败证据：选择允许降级后应成功、原应用不被卸载；当前代码没有该行为。
2. L2 保护稳定行为：IDE 三种选择；允许降级至多追加一次安装；第二次失败保留最终异常；允许降级后的安装带 `-d`；同版本/升级仍成功；失败时不提交部署缓存。
3. 运行现有部署 Flow 回归，补充有价值的状态断言，不使用源码字符串测试替代行为证据。
4. 编译受影响 compat、standalone 和 IDEA 代码，核对实际可用的 `InstallOptions.Builder.setAllowDowngrade()` 签名及 stub/真实 API 兼容要求。
5. 真机/模拟器矩阵：先安装高 `versionCode` APK，写入数据标记；再用低版本 APK 分别验证 IDE 降级、IDE 卸载、IDE 取消、CLI→IDEA、CLI→standalone。检查已安装版本、数据标记和最终任务状态。无法执行的环境项明确记录为未验证。
6. 文档按英文先行同步中文，执行相关一致性检查和 `git diff --check`。
7. 实施验证后，按技能要求委派只读实现审查，处理有效问题后提交本任务改动。

## 参考证据

- `deploy_compat/standalone_deployer/src/main/java/com/android/tools/deployer/install/ApkInstaller.java`：降级错误对应卸载确认。
- `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileUiHandler.kt`：MCP 模式当前自动同意。
- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneCompileUiHandler.kt`：standalone 当前自动同意。
- `docs/skills/jugg-android-dev-loop/scripts/py/cmd/cmd_deploy.py`、`cmd_gradle_build.py` 和 `py/help_registry.py`：当前没有相关 CLI 选项。
- Android 官方 adb 手册：<https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md>。

## 实施与验证记录

### 已实现

- 使用现有 `CommonConfirmDialog` 提供 IDE 三种选择；关闭和取消均结束本次安装。
- 使用会话级 `allowDowngrade` 保存本轮选择；IDE 与 CLI/MCP 均由具体 `CompileUiHandler.confirmDeployPrompt` 决定动作，CLI/MCP 按最终决定恢复自动同意卸载重装；无全局设置或 CLI 参数。
- 安装器原有卸载路径保持不变。允许降级返回 Boolean false，阻止本次卸载；共享安装入口在选项改变后重试一次。
- 已允许降级后再次收到降级提示，直接返回 false，不重复询问或自动卸载。
- 新尝试前清理旧错误，第二次失败保留最终异常；只有成功安装才提交部署缓存。
- 其余新增改动均是现有安装边界的签名适配。额外适配 `AsDeployerCompatDispatcherTest`、`DirectOverlaySwapTransportTest` 和 `StandaloneDeployerDeviceFlowTest` 的会话回调，未新增独立测试文件。

### 自动化证据

- Red：新增安装行为用例后，在接入共享降级重试前执行 `JuggDeployerInstallTest`，25 个测试中 2 个失败，分别证明“选择降级不重试”和“第二次失败没有实际发生”的缺口。
- Green：使用 JDK 17、`ANDROID_HOME=/Users/wormchen/Library/Android/sdk` 运行以下定向任务，全部通过，无跳过：

```bash
./gradlew :idea:test \
  --tests 'com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployerInstallTest' \
  --tests 'com.sickworm.intellij.jugg.deploy.run.LaunchContextFactoryTest' \
  --tests 'com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelperDeployFlowTest' \
  :cmd_line:compileKotlin \
  :deploy_compat:standalone_deployer:test \
  --tests 'com.sickworm.intellij.jugg.deploy.run.StandaloneApplyChangesExecutorTest'
```

| 所有者 | 结果 | 证据范围 |
| --- | --- | --- |
| `JuggDeployerInstallTest` | 26 通过 | 降级、取消、显式卸载确认、一次重试、最终异常、缓存与原安装路径 |
| `LaunchContextFactoryTest` | 3 通过 | 上下文、绑定 executor 和现有部署条件回归 |
| `JuggDeployerHelperDeployFlowTest` | 31 通过 | 现有完整部署与恢复流程回归 |
| `StandaloneApplyChangesExecutorTest` | 6 通过 | 使用真实 standalone 安装器及物理 ADB mock，验证 `-d` 确实传到 ADB、成功降级及拒绝降级均不卸载 |

- 首轮 Flow 运行因测试进程缺少 `ANDROID_HOME` 在初始化阶段失败；补齐实际 SDK 路径后完整重跑通过，未修改测试逻辑绕过环境要求。
- Kotlin 与 Java 回调泛型签名通过各兼容模块、IDEA、standalone、CLI 编译验证。
- 使用 `javap` 核对本机两代真实 SDK 的 Builder 方法和降级提示；提示文本与集中匹配逻辑一致。legacy 编译字节码明确调用 `setUserInstallOptions("-d")`。仓库旧 stub 未收录 `setAllowDowngrade()`，该事实不推断为所有旧版真实 SDK 缺少该方法。
- 使用各 Android Studio 随附 JBR 编译并运行真实 SDK 参数探针：legacy `setUserInstallOptions("-d")` 与 Quail `setAllowDowngrade()` 均输出 `[-t, -d]`。最初用 JDK 17 编译探针时，因真实 SDK 分别要求 Java 21/25 而失败；更换为各自随附 JBR 后通过，未修改项目 JDK 配置。
- 未执行完整双工作区 `verify_stub_api.sh` 字节码对比；本任务未生成或修改任何 stub JAR，兼容性证据限于上述编译、真实 SDK 方法与调用字节码核对。

### 文档验证

- 英文安装机制与 CLI 指南先更新，随后同步对应中文镜像；维护知识库同步会话边界和失败处理。
- Wiki production build 通过。
- Wiki validator 通过，包含两语言安装页面的 HTML 路由和新增章节文本检查。
- `git diff --check` 通过。

### 确认边界统一调整

- 用户复核后批准删除 Factory 中按 `isRpcMode` 预设 `allowDowngrade` 的赋值，IDE 与 CLI/MCP 统一通过 `confirmDeployPrompt` 决定动作。当时 CLI 自动选择允许降级；该动作已由后续“CLI 恢复卸载重装”决定覆盖。
- 删除只断言 Factory 预设字段的旧测试：它绑定已移除的实现，降级重试、取消、最终失败及缓存行为已有 `JuggDeployerInstallTest` 保护，无需增加镜像测试。
- 调整后以相同 JDK 17 与 Android SDK 运行 `JuggDeployerInstallTest`（26）、`LaunchContextFactoryTest`（3）及 `JuggDeployerHelperDeployFlowTest`（31），共 60 个测试全部通过，无跳过。
- 当时同步维护知识库中的首次尝试描述，并核对两语言 Wiki；后续按最终 CLI 决定再次同步相关页面。

### CLI 恢复卸载重装

- 用户最终要求 CLI 不默认允许降级，恢复原有自动卸载重装。静态证据为两个 Host 对版本降级提示均返回 `ALLOW_DOWNGRADE`；修改 IDEA 的 RPC 分支为 `UNINSTALL`，删除 standalone 的降级特例并复用原有自动确认。
- 手动 IDE 的三个选项及允许降级后的有界重试保持不变。Factory 不预设降级选项。
- 测试价值判断：本次仅恢复 Host 的动作选择，不为简单返回值和透传新增测试；复用 `JuggDeployerInstallTest` 的显式卸载 Boolean 契约、允许降级重试和失败状态断言，以及现有部署 Flow 回归。CLI Host 通过定向编译核对。
- 已同步两份维护知识库和两组英文/中文 Wiki 页面，明确 CLI 版本较低时自动卸载重装并删除应用数据。
- 最终验证：以相同 JDK 17 和 Android SDK 运行 `JuggDeployerInstallTest`（26）及 `JuggDeployerHelperDeployFlowTest`（31），57 个测试全部通过、无跳过；`:cmd_line:compileKotlin` 通过。Wiki production build、镜像/链接/四条 HTML 路由及新正文检查、`git diff --check` 均通过。

### 确认交互归拢到 CompileUiHandler

- 用户批准将完整安装确认归拢到 `CompileUiHandler.confirmDeployPrompt(message): PromptAction`，替代只表达自动同意的 Boolean 方法。删除 `IDeployHost` 和两个 Environment 的确认方法，Factory 直接绑定 handler。
- `JuggCompileUiHandler` 承接原降级三选项及普通安装确认；RPC 和 standalone 均返回 `UNINSTALL`。Session 的 Boolean 适配、会话状态及安装重试保持原职责。
- 核对 `DEFAULT`、运行包装器及实际 handler 调用者：普通 IDE/CLI 使用具体 handler，包装器自动委托；无 UI 的默认实现明确返回 `CANCEL`。RPC 日志记录实际提示与卸载清数据决定，移除固定“签名不同”的误导文本；新日志均为简单单行调用。
- 重构前运行现有 handler、安装、Factory 与部署 Flow，68 个测试通过。已有安装/Flow 测试保护重试及缓存结果；新增三个 handler 测试保护独立的用户选择与无弹窗决策，使用 Mockito 隔离实际对话框和 IDE Application，不新增生产测试缝隙或透传测试。
- 最终使用 JDK 17 和相同 Android SDK 运行 `JuggCompileUiHandlerTest`（11）、`JuggDeployerInstallTest`（26）、`JuggDeployerHelperDeployFlowTest`（31）和 `LaunchContextFactoryTest`（3），共 71 个测试全部通过、无跳过；`:cmd_line:compileKotlin`、`:custom_compilers:compileKotlin` 和依赖编译通过。
- 更新维护知识库的交互边界、部署调用链及代码导航。普通 IDE 和 CLI 用户行为保持既定说明，已检查 Wiki，无需调整用户页面。旧 Boolean 方法及 Host 确认方法调用点清理完成，`git diff --check` 通过。

### 原实施审查（CLI 最终调整前）

- 按 `feature-development` 技能委派只读审查，审阅适用 AGENTS.md、批准范围、生产改动、测试和文档；未发现可行动问题。
- 审查范围涵盖三种 IDE 动作、两种 Runtime 默认 `-d`、安装器 Boolean 契约、有界重试、最终异常和缓存提交。

### 未完成的环境验证

- `adb devices -l` 未发现连接设备，因此未执行高低版本真实 APK + 应用数据标记矩阵，也未在正在运行的 IDE 中人工点击新弹窗。
- 自动化结果证明安装参数、重试、卸载确认和部署状态处理；设备是否接受 `-d` 仍须在对应 Android/应用环境验证。
