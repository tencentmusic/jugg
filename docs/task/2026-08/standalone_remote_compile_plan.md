# Standalone 支持 Remote Compile 开发方案

## 状态

已完成实施、定向验证与只读实现审查；L3 真实远程流程等待维护者注入环境后手动执行。

## 目标

让 standalone Runtime 可以执行当前已选的 remote compile profile。`init`、`compile`、`deploy` 与 `gradle-build` 应复用 IDEA 已有的远程 Gradle 编译链路，而不是要求用户切换到 local profile 或重建 profile。与 IDEA 一致，remote 只描述 Gradle full build / fallback 的执行位置；Jugg 增量编译仍在 standalone 本机执行。

## 已确认事实

- `CliRunConfiguration` 是 IDEA 与 standalone 共用的项目级 profile，已包含 SSH、同步模式、iFT/rsync 路径、代理与环境变量等远程编译字段。
- standalone 的 `StandaloneProjectInitializer` 和 `StandaloneConfigurationRunner` 当前会在 `isRemoteCompile=true` 时直接失败；`StandaloneForceGradleCompileHelper.resolveExecutionType()` 还固定返回 `local`。
- `JuggCompilerHelper.gradleCompile()` 已根据 `JuggGradleCompileOptions.isRemoteCompile` 选择 `RemoteGradleCompileClient`。该 client 已承担 SSH 登录、项目同步、远端 Gradle、APK/classpath/diff 回拉和取消，不应在 standalone 重写。
- IDEA remote compile 在 Gradle project info 缺失、compile command 变化或 build file 变化时仍可能执行本地 Gradle dry-run；standalone 保持同一能力和环境要求。
- standalone 分发包的 runtime classpath 已包含 `:main` 及其 JSch 依赖；`rsync` 与 iFT 仍是调用机器需要自行具备的外部工具。
- standalone 的 `PlatformApi` 没有 UI，密码或 iFT 交互输入当前会返回 `null`。若直接解除 remote 拒绝，缺失凭据会被错误地表述为“User canceled”。
- `request_remote_ssh_info` 会返回密码，IDE Runtime 依赖桌面确认后才允许返回；standalone 不应注册或实现该凭据导出能力。

## 行为决策

### 支持范围

1. 当前 profile 为 remote 时，`init` 成功且保持幂等，不改写 profile、不切换到 local profile。
2. `compile`、`deploy`、`gradle-build` 将该 profile 转成原有 `JuggGradleCompileOptions`；需要 Gradle full build / fallback 时由 `JuggCompilerHelper` 选择远程 client，增量编译路径保持本机执行。
3. `status` 与异步 job 的 `executionType` 在当前 profile 为 remote 时返回 `remote`。
4. 支持已有配置中的 rsync、rsync-simple 与 iFT 路径；不新增 standalone 专用的 SSH/同步参数、profile 编辑命令或第二份配置。

### 非交互与安全边界

- standalone 复用 IDEA 的 SSH 密码、密钥路径与 `~/.ssh` 密钥发现逻辑；需要交互补录 SSH 密码、密钥口令、iFT 用户名或 iFT 密码时，立即以明确的结构化失败结束。iFT 仅在外部客户端已经登录时可用。
- 不尝试桌面对话框或 stdin 提示，不向 MCP/CLI 响应或额外文件输出新凭据。profile 中已有密码继续沿用现有 owner-only JSON 持久化契约。
- 远端环境变量仍完整传给远端；日志只显示 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`GRADLE_USER_HOME` 的值。`PATH` 只显示已配置，其他变量仅显示名称和数量，所有值均隐藏。远端原始 command 不写入日志，只保留 command 类型、长度与 hash；发送敏感 command 前必须确认 PTY echo 已关闭，无法关闭时直接失败。
- `request_remote_ssh_info` 继续不向 standalone capability 暴露，`requestRemoteSshInfo()` 保持拒绝，避免 MCP 调用导出 profile 中的密码。
- SSH 登录、同步、远端 Gradle 或产物回拉失败沿用 `RemoteGradleCompileClient` 的既有错误和取消语义；不得降级为本地构建并伪造远端成功。

## 实施步骤

### 1. 先建立失败回归

修改 `cmd_line/src/test/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneRuntimeTest.kt` 中当前断言“remote profile 被拒绝”的用例：

- 保存并选中完整的 remote profile；调用 `init` 后断言成功、当前 profile ID 未变化且仍为 remote。
- 调用 `status`，断言 `executionType=remote`。
- 确认 `compile` 进入正常异步 job 编排，而不再返回 remote-unsupported。该断言不连接真实 SSH，只验证可观察的 standalone 编排结果，避免把网络环境纳入单元测试。

该用例保护的是 standalone 对共用 profile 的用户可见契约，适合作为 `cmd_line` 的 L2 编排回归。应先确认它在旧代码上失败，再修改生产代码。

### 2. 解除 standalone 的策略性拒绝

- 在 `StandaloneProjectInitializer.initialize()` 中，已有 current profile（local 或 remote）都返回幂等成功结果。仅 current profile 缺失时才读取 Gradle project info 并生成默认 local profile。
- 在 `StandaloneConfigurationRunner.runChain()` 删除 remote profile 的提前拒绝，保留现有 `initialize -> loadCurrent -> toCompileOptions -> compilerHelper.compile` 调用链。
- 删除不再使用的 `REMOTE_COMPILE_UNSUPPORTED` 常量及相应测试断言；不要修改 `CliRunConfigurationStore`、profile ID 或远程字段序列化。

### 3. 修正运行类型与凭据交互错误

- `StandaloneForceGradleCompileHelper.resolveExecutionType()` 按 `services.configurationStore.loadCurrent()?.isRemoteCompile` 返回 `remote` 或 `local`，与 IDEA 实现一致。
- 向 `StandaloneForceGradleCompileHelper` 直接注入 `CliRunConfigurationStore`，不新增 provider、接口或额外状态。
- 将 standalone 的 `showUserAndPasswordInputDialog()` 改为抛出带 standalone/non-interactive 原因的远程登录异常，而非返回 `null`。这样 `RemoteGradleCompileClient` 遇到需要 SSH 或 iFT 交互时会立即失败并给出正确的配置指导，不会误报用户取消。
- `JuggGradleCompileTask` 在 `finally` 中停止计时 job，并恢复 terminal listener 与 cancel listener；异常路径额外 dispose 编译客户端，保证认证失败不会残留 SSH/iFT 资源。
- `RemoteGradleCompileClient` 删除包含环境变量和 Gradle command 的原始 command 日志，改为输出上述白名单摘要与安全 command 元数据。SSH shell ready 后先执行并验证 `stty -echo`，防止 PTY 回显原始 command；关闭失败时不继续远程构建。
- 不改变 IDEA 的 UI 输入路径，也不为测试新增 lambda/provider seam。

### 4. 更新行为文档

实现后同步更新：

- `docs/ai_knowledge/08_cli_tools_list.md`：删除“remote profile 明确失败”的描述，记录 standalone 的非交互 remote profile 前提。
- `docs/ai_knowledge/08_mcp_tools_list.md`：更新 `init` 的 remote 行为，以及 `executionType` 在 standalone 中可为 `remote`。
- `docs/ai_knowledge/04_engineering_project.md`：补充 standalone 复用远程 Gradle client、外部 rsync/iFT 工具与非交互认证边界。
- `docs/ai_knowledge/09_plugin_runtime_debug.md`：增加 standalone remote 失败时的日志路径与排查顺序。
- 将验证 TODO-1 标为已解决，并链接最终验证证据。

## 验证计划

| 层级 | 场景 | 证据 |
|---|---|---|
| L2 | current remote profile 的 `init` 与 `status` | 定向 `StandaloneRuntimeTest`，旧实现先失败，修复后通过 |
| L2 | remote 任务不被 runner 提前拒绝 | 同一 standalone runtime 编排测试，断言 job 不是 remote-unsupported |
| L1/L2 | 非白名单环境变量与原始 command 不进入日志 | 定向测试白名单摘要与 PTY echo 关闭握手，并静态确认原始 command logger 已删除 |
| L2 | 认证异常后的任务资源清理 | 定向 `JuggGradleCompileTask` 测试，确认 failed/not-canceled、client dispose 与 timer/listener 恢复 |
| 编译/产物 | standalone 分发仍包含远程 client 与 JSch | `./gradlew :cmd_line:standaloneBundle`，检查实际 Bundle runtime classpath/启动 |
| L3 手工 | 已配置 remote profile 的 `gradle-build` | 新增 `StandaloneRemoteCompileFlowTest`；仅在 `JUGG_REMOTE_CONFIG_FILE` 与 `JUGG_STANDALONE_REMOTE_PROJECT_DIR` 都已注入时执行，否则使用 JUnit Assume 跳过；总等待上限 30 分钟，结束后恢复原 profile；确认远端 Gradle、APK/classpath 回拉及 `get-compile-status=success` |
| L3 手工失败 | 缺失 SSH/iFT 交互凭据 | 确认立即返回非交互配置错误，不弹窗、不等待 stdin、不输出密码 |
| 回归 | local profile | 执行既有 standalone local `init`、`gradle-build`，确认 `executionType=local` |

不新增真实 SSH 网络测试：其可用性依赖远端主机、密钥、rsync/iFT 与 Android 工具链，无法形成稳定、隔离的自动化断言。真实远程链路由上述 L3 手工矩阵覆盖。

## Review 结论

1. 产品范围是否接受“仅非交互 remote compile”；如需终端交互输入，应另行设计不污染 JSON/MCP 协议的授权与秘密传递机制。
2. L3 由维护者通过 `JUGG_REMOTE_CONFIG_FILE` 与 `JUGG_STANDALONE_REMOTE_PROJECT_DIR` 注入受控远端环境；未注入时默认跳过，不进入普通 CI 外部依赖。
3. 与 IDEA remote compile 一致，`deploy` 的设备连接在 standalone 所在机器，远端仅负责构建。
