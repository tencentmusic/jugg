# 自定义 APK 签名脚本方案

## 目标

系统应用或厂商工程可能无法使用本地 keystore 完成 APK 签名，需要把待签名 APK 发送到服务器处理。Jugg Run Configuration 增加可选的自定义 APK 签名脚本，用项目脚本替换 Jugg 在增量改写 APK 后执行的默认 `apksigner sign`，同时保留 APK 校验、原子替换、安装恢复和后续增量部署状态。

## 范围边界

- 首版只替换 Jugg 对增量改写 APK 的重新签名步骤，即 `ApkFileModifier.insertAndResign()` 中 zipalign 之后的默认签名。
- 生效场景包括 Manifest、native library、embedded dex 等产物写回 APK，以及 Embedded APK 路径。
- 未经 Jugg 改写的 Gradle 完整构建 APK不额外执行签名脚本；Gradle 自身的签名流程保持不变。
- CLI `BuildIncrementalApkCommand` 和手工导出增量 APK暂不增加脚本参数，继续使用默认签名。
- 不设计远端签名服务协议、认证、轮询或重试，这些由项目脚本负责。

## 用户行为

- Jugg Run Configuration 增加 `Enable custom APK sign script` 开关。
- 未勾选时只显示开关，现有签名行为完全不变。
- 勾选后在下方展开单行高度的 `Custom APK Sign Script` 输入面板，示例占位为 `e.g. ./scripts/sign-system-apk.sh`。
- 启用但脚本内容为空时，Run Configuration 校验直接失败。
- 脚本内容属于敏感配置；日志和请求对象的安全字符串只能显示 `(configured)` / `(not_configured)`，不得打印原始命令。

## 脚本调用契约

Jugg 在本地工程根目录执行配置的命令，并把待签名 APK 的绝对路径作为最后一个位置参数传入：

```text
<configured command> "<absolute path of aligned temporary apk>"
```

例如用户配置：

```bash
./scripts/sign-system-apk.sh --server production
```

实际语义为：

```bash
./scripts/sign-system-apk.sh --server production "/path/to/.app.apk.tmp_aligned"
```

约束：

- APK 路径按当前宿主 shell 规则安全转义，路径包含空格、括号或 Unicode 字符时仍作为单个参数传递。
- macOS/Linux 使用 Bash，Windows 使用 `cmd.exe`，沿用 `CmdExecutor` / `SimpleSshCommand` 现有执行模型。
- 脚本继承 `ICompileContext.cmdCompileEnv`，在远程编译场景下也运行于已经拉取 APK 的本地 IDE 主机。
- 脚本必须原地覆盖传入的临时 APK；若服务返回到其它文件，脚本需在退出前自行替换传入路径。
- 退出码 `0` 只表示脚本执行完成；Jugg 随后仍使用 `apksigner verify` 校验 APK。
- stdout/stderr 转发到 Jugg Run 窗口；用户取消 Run 时终止脚本进程。
- 不对已知失败自动重试；服务器上传、等待、下载及其重试策略由业务脚本负责。

## 默认测试脚本

`android_demo_project` 增加：

```text
android_demo_project/scripts/sign-system-apk.sh
```

该脚本用于本地功能测试，接收 `$1` 作为待签名 APK 路径，并使用 demo 工程现有默认签名配置完成原地签名。要求：

- 参数缺失或 APK 不存在时返回非零退出码并打印明确错误。
- 从工程自身可用配置或现有测试资源获取默认 keystore，不复制或新增敏感密钥。
- 使用 Android SDK `apksigner`，不依赖用户交互。
- 支持从 `android_demo_project` 工程根目录执行：

```bash
./scripts/sign-system-apk.sh "/absolute/path/to/app.apk"
```

- 脚本需有可执行权限，并保持实现最小，只服务于该功能的默认签名验证。

## 核心调用链

```text
Jugg Run Configuration
  -> JuggGradleCompileOptions
  -> DeployOptions.customApkSignScript
  -> JuggDeployerHelper
  -> IncrementalDeployHelper.updateApk()
  -> ApkFileModifier.insertAndResign()
       -> updateFiles()
       -> alignApk()
       -> custom script OR default apksigner sign
       -> apksigner verify
       -> replaceOldApk()
  -> recover/install updated APK
```

签名脚本只需传到 APK 更新边界，不进入 `LaunchContext`、`DeployStateRecover` 或 installer：recover/retry 重新安装的是已经签名的 APK，部署 retry 也沿用当前 `isRetry` 逻辑跳过再次改写和签名。

## 实现设计

### 运行配置

- `JuggRunConfigurationOptions` 在现有字段末尾追加 `enableCustomApkSignScript` 与 `customApkSignScript`，保持属性持久化顺序兼容。
- `JuggGradleCompileOptions` 增加同名字段，负责启用时的非空校验与安全字符串脱敏。
- `JuggRunConfigurationOptionsExt` 完成持久化对象到编译选项的转换。
- `JuggRunSettingsComponent` 参考 custom install script 的 UI 结构增加开关、单行输入框、显隐、读取与写回。
- `JuggRunningTask` 仅在开关启用时把脚本写入 `DeployOptions`。

### 签名编排

- `DeployOptions` 增加 `customApkSignScript`，并在 `toSafeString()` 中脱敏。
- `JuggDeployerHelper` 在普通 APK 更新和 Embedded APK 更新两处调用 `IncrementalDeployHelper.updateApk()` 时传入脚本与当前 `CompileUiHandler`。
- `IncrementalDeployHelper` 只在未配置自定义脚本时要求 `context.signingConfig` 有效；启用脚本时不得因为本地 keystore 缺失而失败。
- 新增具体类 `CustomApkSignScriptRunner`，负责命令拼接、APK 参数转义、工作目录、环境、输出转发、取消和退出码处理；不为单一实现新增 signer 接口。
- `ApkFileModifier` 接收可空的 runner。zipalign 后二选一执行自定义脚本或现有 `resignApk()`，之后统一执行 `verifyApk()` 和原子替换。
- 自定义脚本失败时不得回退到默认本地签名，避免产生签名身份错误的 APK。

## 多 APK、多设备与失败边界

- 保持当前 `IncrementalDeployHelper.updateApk()` 粒度：每个 APK 分别修改、对齐、签名和验证。
- 保持当前按设备执行 APK 更新的部署结构，因此多设备 Run 可能对相同 APK重复调用签名脚本；首版不增加跨设备签名缓存。
- 部署 retry 使用已更新 APK，不重复签名；用户重新发起 Run 或另一个设备进入 APK 更新时，脚本需允许再次执行。
- 脚本非零退出、取消、输出 APK 验证失败或临时文件异常时，本次 APK 更新失败，不进入安装；当前 APK 的原文件保持不变。
- 多 APK 中后续 APK失败时，不安装部分成功的 APK集合；沿用现有逐 APK 发布行为，不额外重构为跨 APK事务。
- APK 更新失败继续沿用当前 `isCanFallback=true` 契约，让 Run 层决定是否进入 Gradle fallback；但同一次更新绝不改用默认签名。

## 变更清单

### 生产代码

- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfigurationOptions.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggGradleCompileOptions.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunConfigurationOptionsExt.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunSettingsComponent.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployHelperBean.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalDeployHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkFileModifier.kt`
- 新增 `main/src/main/java/com/sickworm/intellij/jugg/apk/CustomApkSignScriptRunner.kt`
- 新增 `android_demo_project/scripts/sign-system-apk.sh`

### 测试

- `idea/src/test/java/com/sickworm/intellij/jugg/compiler/manifest/ApkFileModifierTest.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/ide/bean/JuggGradleCompileOptionsTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggRunSettingsComponentTest.kt`
- 复用合适的 `JuggDeployerHelper*Test` owner 覆盖签名失败后的部署边界，不新增只验证字段透传的测试。
- 复用现有 `TopLevelFlowTest` 验证默认 install 和 incremental deploy 主链不回归。

### 知识库与 Wiki

- `docs/ai_knowledge/00_overview.md`
- `docs/ai_knowledge/03_deploy_core.md`
- `docs/ai_knowledge/03_deploy_complete.md`
- `docs/ai_knowledge/03_deploy_system_app.md`
- `docs/ai_knowledge/05_utilities.md`
- `docs/ai_knowledge/98_code_map.md`
- `docs/ai_knowledge/99_index.md`
- 新增中英文 `custom-apk-sign-script.md` Wiki 页面。
- 更新中英文部署能力索引、VitePress 侧边栏和 APK 更新与安装页面；同步检查 custom install 页面中“不内置签名”的旧表述。

## 验证计划

### 失败证据与测试价值

- Feature 首先用失败测试证明：当前配置没有签名脚本入口；本地签名配置无效时不能通过外部脚本签名；APK 路径未作为参数传入。
- 自定义签名及 APK 原子发布属于稳定、可观察的外部协议，测试价值通过，owner 为 `ApkFileModifierTest`，层级 L1。
- 签名失败不得进入安装属于部署编排边界，使用现有 deploy helper owner，层级 L2。
- 普通字段透传、默认值和私有方法不单独创建测试。

### 自动化验证

- 自定义脚本收到包含空格、括号或 Unicode 的临时 APK 路径，并把它视为单个参数。
- 本地 `SigningConfig` 无效时，自定义脚本仍能签名；未启用时仍明确失败或使用原默认签名。
- 脚本失败或 `apksigner verify` 失败时，原 APK 内容不变。
- 启用但脚本为空时配置检查失败；安全字符串不包含脚本或 token。
- UI 开关、输入面板显隐和 Run Configuration 持久化正确。
- APK 更新签名失败时不进入安装，保持现有 fallback 资格。
- 使用 demo 脚本完成一次真实 APK 修改、签名和校验。
- 执行至少一条现有 L3 install + incremental deploy Flow，证明默认路径不回归。

### 最终检查

- 运行选定的定向测试，不执行无 `--tests` 过滤的全量 `:main:test` / `:idea:test`。
- 执行 `./gradlew :idea:compileKotlin`。
- 检查脚本可执行权限及 shellcheck 等价的基本语法。
- 检查本次新增或修改日志符合 Jugg 日志格式，并确保脚本内容不进入日志。
- Wiki 执行中英文镜像、链接和 production build 验证。

## 不在范围内

- 对每次 Gradle完整构建 APK增加安装前签名 hook。
- 新增远端签名服务客户端、HTTP协议、凭据配置或自动轮询。
- 新增 signer SPI、接口体系、脚本类型枚举或多套签名配置。
- 给 CLI增加新的签名脚本参数。
- 优化现有多设备重复改写/签名或跨 APK原子事务。
