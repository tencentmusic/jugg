# jugg CLI 参数与 MCP 映射

> 最后核对：2026-08-31
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只描述 `jugg` CLI 的公开子命令、全局参数、CLI flag 到 MCP 参数的映射，以及几个容易误判的 CLI-only 行为。

不展开 MCP tool 的完整 schema；完整参数以 [`08_mcp_tools_list.md`](08_mcp_tools_list.md) 与 `tools/list` 为准。Benchmark prompt pack、hooks 验收与被测 Agent 报告格式由 `docs/skills/benchmark/` 承载，不放在本参数清单内。

---

## 2. 核心源码索引

| 文件 | 作用 |
|------|------|
| `docs/skills/jugg-android-dev-loop/scripts/jugg.py` | CLI 总入口；解析全局参数、处理本地 help、懒加载子命令 |
| `docs/skills/jugg-android-dev-loop/scripts/py/help_registry.py` | side-effect-free help 文案；`COMMAND_HELP` 必须覆盖全部公开 CLI 子命令 |
| `docs/skills/jugg-android-dev-loop/scripts/py/jugglib.py` | MCP 端口发现、projectDir 解析、kebab-case 归一化、异步轮询、输出格式 |
| `docs/skills/jugg-android-dev-loop/scripts/py/cmd/cmd_*.py` | 各子命令参数解析；通常只做 MCP 参数直传和必要的本地校验，`stop` 直接调用 standalone launcher |
| `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolActionRegistry.kt` | MCP 注册工具事实来源；除本地生命周期命令外，CLI 子命令映射到这里的公开工具 |

---

## 3. 全局行为

### 3.0 Python 版本要求

`jugg` CLI 最低支持 Python 3.7。安装流程会先检测 `python3`，未找到时回退到 `python`；两者均不可用或版本过低时，CLI / hooks 安装会在写入文件前失败。macOS/Linux 与 Windows wrapper 也按相同顺序运行时回退。CLI 脚本统一启用 postponed annotations，避免 `list[str]`、`dict[str]`、`bool | None` 等注解在 Python 3.7 import 阶段求值失败。

兼容性事实来源是 `docs/skills/python_compat.json`；回归检查入口是 `tools/check_python_compat.py --target jugg_cli`。严格 runtime 校验需要 PATH 中存在 `python3.7`，并使用 `--strict-runtime`。

### 3.1 projectDir 解析

默认路径：

```text
jugg.py
  -> jugglib.resolve_project_dir()
  -> 从当前目录向上查找最近的 settings.gradle(.kts)
  -> 优先选择精确拥有该 Gradle 工程的 IDEA / standalone Runtime
  -> 未命中时复用任意 standalone Runtime，由首个合法项目请求自动注册
  -> 没有 standalone Runtime 时才启动新进程
```

自动解析时，独立嵌套 Gradle 工程不会被已打开的父 IDEA 工程截获；例如父仓库与其 `android_demo_project` 都有 `settings.gradle(.kts)` 时，从后者目录执行 CLI 会使用后者的 Runtime，未打开时复用或启动 standalone Runtime，并在首个项目请求中注册该嵌套工程。

传入 `--project-dir <path>` 或 `--project-dir=<path>` 时，CLI 仍用该路径发现 Runtime，并允许将最长前缀匹配到的已初始化项目目录作为 MCP `projectDir`。因此，显式传入 IDEA 工程根目录下的普通子目录时会由该 IDEA Runtime 处理；未匹配时才按 standalone 启动流程处理。`--projectDir` 作为 camelCase 全局别名也会被归一化。

macOS 上 Runtime 归属匹配会使用大小写折叠后的路径 key；`Checking Jugg runtime`、IDE Runtime 未找到和 standalone 启动进度仍显示用户输入或当前工程的原始大小写路径。

### 3.1.1 设备 serial

`--serial <adbSerial>` / `--serial=<adbSerial>` 是与 `--project-dir` 同级的全局参数。它会向消费设备目标的命令注入 MCP `serial`：`deploy`、`gradle-build`、`clean-reinstall`、`restart`、`instrument`、`status`、`devices`、`layout-dump`、`view-locate`、`view-inspect`、`tap`、`activity-stack`、`wait-logs`。`version`、`init`、`stop`、`compile`、`ssh-info` 和内部 `get-compile-status` 不接收该参数。

显式 serial 使用大小写敏感的精确在线设备匹配，优先级高于 IDEA 当前选中设备和 standalone daemon 启动时继承的 `ANDROID_SERIAL`；只影响当前 CLI 请求，不修改 IDE 选择、Run Configuration 或后续调用。未传 serial 时保持原有 Host 行为。

### 3.2 端口与缓存

CLI 扫描 `12320..12329` 后分别调用 `version`、`list-projects`，优先按目标 `projectDir` 选择 IDEA 或 standalone Runtime；端口缓存只用于优先探测，不覆盖项目归属判断。同一项目同时出现在两个 Runtime 时，仅在确认 `runtime.lock` 正被持有后采用 `runtime.lock.owner.json`，否则读取 `runtime.owner.json` 选择最近 owner。全局参数 `--runtime idea|standalone` 可覆盖自动选择。没有项目 owner 且未强制 IDEA 时，CLI 复用任意已运行的 standalone Runtime，并将目标项目保留为 pending projectDir，首个合法项目请求完成自动注册。

当前没有 standalone Runtime 时，普通 CLI 取得 `~/.jugg/locks/standalone.launch.lock`，在锁内重新发现 Runtime；仍未发现时才启动 standalone launcher，并持锁等待端口注册，避免不同项目并发创建多个 daemon。测试或特殊环境可用 `JUGG_STANDALONE_LAUNCH_LOCK` 覆盖锁路径。launcher 默认路径为 `~/.jugg/standalone/bin/jugg-standalone`（Windows 为 `.bat`），可用 `JUGG_STANDALONE_LAUNCHER` 覆盖。启动和首个项目自动注册的等待硬超时均为 60 秒；launch lock 最长等待 75 秒。初始化超过 10 秒后，CLI 每 10 秒从目标项目 `build/jugg/log/standlone_cli/compile_latest.log` 读取最后一条结构化日志并向 stderr 输出 heartbeat；日志缺失或读取失败只显示日志暂不可用，不中断启动。日志行最多输出 500 个字符。新进程 stdout/stderr 仍写入启动项目 `build/jugg/log/standlone_cli/standalone_startup.log`；进程在端口就绪前退出时立即展示 exit code、日志尾部和完整日志路径。Hook 调用必须设置 `JUGG_CALLER=hook`；只有目标项目 `build/jugg/database/compile_context.db/complete_flag` 已存在时才允许启动进程或在已有 standalone 中注册新项目，否则直接以成功状态跳过。

standalone Step 11 支持 `init`、`compile`、`deploy`、`gradle-build`、内部 `get-compile-status` 与 `status`。其中 `deploy --serial` 可在 daemon 已运行后按请求切换设备，`status --serial` 返回指定设备状态；standalone `gradle-build` 只建立 baseline，不执行设备安装。`devices`、`restart`、`clean-reinstall`、`instrument`、`layout-dump`、`view-locate`、`view-inspect`、`tap`、`activity-stack`、`wait-logs` 仍未注册为 standalone capability，需 IDEA Runtime。当前配置启用 remote compile 时，standalone 复用 IDEA 的远程 Gradle 客户端执行 full build/fallback；增量编译和设备操作仍在 standalone 所在本机执行。远程构建前仍可能在本地执行 project info Gradle dry-run，不应把 remote 理解为“本地不运行 Gradle”。

`status` 在项目空闲且可立即取得项目锁时完成 Git refresh、Runtime owner 恢复和一致性快照；同 Runtime 正在 compile/deploy，或项目锁正由其他写事务持有时，不等待写锁也不刷新文件状态，而是立即返回当前真实只读快照。实际部署状态、fallback 原因、待编译文件、baseline 和时间戳仍会返回；`isCompiling` 只反映当前 Runtime 的 compile/deploy 运行态，保证 CLI wait/heartbeat 不被长任务阻塞。

当进程仍存活但等待端口达到 60 秒硬超时时，CLI 先输出 `standalone_startup.log` 尾部与路径，再输出每个端口的探测摘要。只有 timeout、HTTP 5xx 或其它非预期异常会触发一次短重试；纯 connection refused 不为同一轮扫描重试。

`jugg stop` 是 standalone CLI 专用的本地生命周期命令，不扫描 MCP 端口，也不调用 `resolve_port()`，因此不会在停止时意外拉起 Runtime。CLI 同步调用 standalone launcher 的 `--stop-all` 控制模式；bootstrap 在加载 active Runtime JAR 前按 Jugg 根目录匹配全部 standalone 进程。平台支持正常终止时先请求正常退出并等待 5 秒，仍存活时强制终止，不支持的平台直接强制终止。未找到进程时幂等成功。该命令会同时停止这些进程承载的所有项目，但不删除 run configuration、Compile Context、历史或日志；`--runtime idea` 明确失败。

| 文件 | 默认路径 | 环境变量 |
|------|----------|----------|
| 端口缓存 | `~/.cache/jugg/port`（Linux/macOS）/ `%LOCALAPPDATA%/jugg/port`（Windows） | `JUGG_PORT_CACHE` |
| 缓存根目录 | `~/.cache/jugg/` | `JUGG_CACHE_DIR` |
| standalone 启动锁 | `~/.jugg/locks/standalone.launch.lock` | `JUGG_STANDALONE_LAUNCH_LOCK` |

### 3.3 输出模式

```text
jugg --console=plain <subcommand>
jugg --console=rich <subcommand>
jugg --console=json <subcommand>
```

- `plain`：直接 `python3 jugg.py` 的默认模式，不显示 spinner。
- `rich`：shell / Windows wrapper 默认注入，面向人工终端显示 spinner。
- `json`：输出 MCP `structuredContent` JSON，供脚本或 Agent 消费。

`compile` / `deploy` / `gradle-build` / `instrument` 的长耗时进度提示不进入结果 stdout。`plain` 会在触发前向 stderr 输出一次起始进度（如 `Running Gradle build...`），并在运行中输出无额外前缀的 heartbeat；`rich` 会更新同一行 spinner 文案；`json` 保持 stdout 纯 JSON，默认不输出 heartbeat。

用户用 Ctrl-C 中断 compile 类命令时，CLI 输出简短 `Interrupted by user.` 并以 130 退出，不打印 Python traceback。

全局参数由 `jugg.py` 在子命令分发前抽取；示例统一写在子命令前，便于阅读。

### 3.4 并发 compile 策略

```text
jugg [--if-compiling wait|interrupt] <compile|deploy|gradle-build|instrument> [options]
```

- `wait`（默认）：触发前每 5s 轮询 `status.isCompiling=false`；持续等待时每 30s 在 stderr 输出一次 `waiting for previous compile` heartbeat。
- `interrupt`：跳过等待，立即调用目标 MCP tool；服务端沿用“新任务中断旧任务”的语义。
- 这是 CLI-only 全局参数，不发送给 MCP。

### 3.5 异步编译轮询

`compile`、`deploy`、`gradle-build`、`instrument` 经 `jugglib.compile_call()` 调用。若首次响应 `data.status=running`，CLI 用 `get-compile-status` + `waitTimeoutMs=5000` 轮询到终态，并保留首次响应中的 `logPath`。

`get-compile-status` 返回 running 且附带 `data.indicator.text` 时，`plain` 模式会立即向 stderr 输出首条 heartbeat，后续同类 running heartbeat 每 30s 节流一次；`rich` 模式会用该文本覆盖当前 spinner 文案并保留 spinner；`json` 模式不输出该 heartbeat。

### 3.6 help 输出

```text
jugg --help
jugg help <subcommand>
jugg <subcommand> --help
```

help 在 `jugg.py` 内直接返回，只读取 `help_registry.py`，不会连接 MCP、解析 `projectDir`、触发编译或部署。

---

## 4. 参数映射约束

CLI 参数设计遵循“机械映射，不创造新语义”：

| 规则 | 正确做法 | 禁止做法 |
|------|----------|----------|
| flag 名可机械转成 MCP key | `--always-restart-app` -> `alwaysRestartApp` | 自造无法转回 MCP key 的别名 |
| kebab-case 与 camelCase 等价 | `--source-path` -> `--sourcePath` -> `sourcePath` | 为兼容旧名字保留 `--clazz`、`--instrumentationRunner` |
| CLI 省略参数即不发送给 MCP | 不传 `--always-restart-app` | CLI 硬编码默认值覆盖 MCP 默认值 |
| CLI-only 参数必须留在全局层 | `--if-compiling` 只影响触发前等待 | 把 CLI-only 参数塞进 MCP arguments |
| 请求级设备参数由全局层注入 | `--serial emulator-5556 deploy` -> `deploy.serial` | 修改 IDE 选中设备或 daemon 进程环境 |
| 本地生命周期命令不进入 MCP | `stop` 直接调用 standalone launcher | 为停止 Runtime 先执行端口发现或自动启动 |

`jugglib.normalize_args()` 只做 kebab-case 到 camelCase 的机械转换，不做语义 alias。每个 `cmd_*.py` 的 `build_params()` 是实际参数直传边界。

---

## 5. 公开子命令

当前公开 CLI 子命令共 18 个，来自 `jugg.py::COMMANDS`。

| 子命令 | MCP tool | 说明 |
|--------|----------|------|
| `version` | `version` | 显示 CLI 版本和插件版本；无需 `projectDir` |
| `init` | `init` | 自动选择/拉起 standalone，并根据 Gradle project info 创建当前 build profile |
| `stop` | CLI local | 停止目标工程的 standalone Runtime；不连接或启动 Runtime |
| `compile` | `compile` | 增量编译，自动轮询终态 |
| `deploy` | `deploy` | 编译并部署，自动轮询终态 |
| `gradle-build` | `gradle-build` | 强制 Gradle 构建并走后续安装/启动链路 |
| `clean-reinstall` | `clean-reinstall` | 清数据并重装 APK |
| `restart` | `restart` | 重启 App |
| `instrument` | `instrument` | 从 androidTest 源文件锚点运行测试 |
| `status` | `status` | 查看部署状态、未编译文件摘要、androidTest baseline 与 compile 运行态 |
| `layout-dump` | `layout-dump` | 导出 UI 层级 HTML |
| `view-locate` | `view-locate` | 查找元素位置 |
| `view-inspect` | `view-inspect` | 反射读取 View 属性 |
| `tap` | `tap` | 坐标、百分比或元素模式触控 |
| `devices` | `devices` | 列出设备 |
| `activity-stack` | `activity-stack` | 查看 Activity 栈 |
| `ssh-info` | `ssh-info` | 申请 SSH 排障信息 |
| `wait-logs` | `wait-logs` | 等待 App 日志 marker / crash / timeout |

`list-projects`、`get-compile-status` 是 CLI 内部使用的 MCP tool，不暴露为 CLI 子命令。

---

## 6. 子命令参数

### `version`

```text
jugg version
```

无需 `projectDir`。默认输出 CLI version 与当前已初始化项目中的插件版本；`--console=json` 返回 `{"cliVersion": "...", "plugin": <MCP structuredContent>}`。

### `init`

```text
jugg init
```

该命令固定选择 standalone Runtime。已有当前配置时幂等返回，包括已选中的 remote profile；缺少 Gradle project info 时执行一次本地 dry-run 生成快照，再创建默认 Application/debug profile。初始化、配置写入与 owner 接管都在项目写锁内执行。

### `stop`

```text
jugg stop
jugg --project-dir <path> stop
```

该命令只停止目标工程的 standalone CLI Runtime，不支持 IDEA Runtime。它不经过 MCP，不要求 daemon 已完成端口初始化；支持正常终止的平台等待最多 5 秒后强制终止仍存活的目标进程，不支持的平台直接强制终止。没有目标进程时返回成功，项目持久化状态保持不变。

### `compile`

```text
jugg compile
```

无子命令参数。终态输出 `status`、`message`、`full log`、`detail` 等字段。

没有待编译文件时，终态 message 会显示 `compile executed successfully. No pending file changes.`。该状态表示本轮没有生成新的编译产物，命令仍然成功且不会执行部署；直接完成和异步轮询完成时输出一致。

### `deploy`

```text
jugg deploy [--always-restart-app <true|false>]
```

| CLI flag | MCP 参数 | 说明 |
|----------|----------|------|
| `--always-restart-app` / `--alwaysRestartApp` | `alwaysRestartApp` | `true` 时部署后强制重启 App；`false` 允许 HOT RELOAD。省略时由 MCP 默认值决定 |

终态输出 `isCompileSuccess`、`isDeploySuccess` 与日志路径。判断部署是否成功时必须同时看 deploy 结果，不要只看 compile 是否成功。

standalone 部署只允许确定的单设备目标：显式 `--serial` 优先，其次使用 `ANDROID_SERIAL`；两者均未设置时仅在恰好一台设备在线时继续，多台设备会明确失败，不会对全部设备批量部署。请求级 `--serial` 不依赖 daemon 启动环境，因此 daemon 已运行后仍可逐次切换目标设备。

没有待部署文件时，终态 message 会明确说明当前 Jugg 检测到的修改均已部署，并展示本次 IDE 会话内最后一次包含文件变更的成功部署时间（绝对时间 + 相对时间）和项目相对路径；文件最多展示 20 条。该信息只保存在当前 IDE 会话，IDE 重启后无记录时会明确提示详情不可用。直接完成和异步轮询完成时输出一致。

CLI 当前不暴露 MCP 的 `waitAppReadyAfterSuccess` 参数；省略时按 MCP 默认值 `false`，即只等待 compile/deploy 任务终态，不额外等待 App ready。

### `gradle-build`

```text
jugg gradle-build
```

无子命令参数。IDEA Runtime 保持 Gradle 构建后的安装/启动链路；standalone Runtime 只建立/刷新 baseline，后续用 `jugg deploy` 完成安装或增量部署。选中 remote profile 时，Gradle full build/fallback 走 SSH/iFT 远程编译，同步、产物拉取与回退语义对齐 IDEA；standalone 不会弹出认证框，SSH 凭据缺失或 iFT 未认证时以 failed 终态返回明确提示。失败时会打印 `detail`，包含 Gradle build 日志摘要，例如 `Compile project failed, please check the error message.` 后面的实际错误行；长日志 preview 上限为 8KB，采用 4KB 开头 + 4KB 结尾。

CLI 当前不暴露 MCP 的 `waitAppReadyAfterSuccess` 参数；省略时按 MCP 默认值 `false`，即只等待 Gradle build 任务终态，不额外等待 App ready。

### `clean-reinstall`

```text
jugg clean-reinstall
```

无子命令参数。

CLI 当前不暴露 MCP 的 `waitAppReadyAfterSuccess` 参数；省略时按 MCP 默认值 `false`，即只等待 clean-reinstall 任务终态，不额外等待 App ready。

### `restart`

```text
jugg restart
```

无子命令参数。

CLI 当前不暴露 MCP 的 `waitAppReadyAfterSuccess` 参数；省略时按 MCP 默认值 `false`，即只等待 restart 命令执行完成，不额外等待 App ready。

### `instrument`

```text
jugg instrument --source-path <src/androidTest/.../FooTest.kt>
                [--class <Fqcn>] [--method <method>]
                [--runner <runnerFqn>] [--extras <k=v;k2=v2>]
```

| CLI flag | MCP 参数 | 说明 |
|----------|----------|------|
| `--source-path` / `--sourcePath` | `sourcePath` | 必填；androidTest 源文件路径，用于解析 module 与 test APK |
| `--class` | `class` | 文件内测试类；单 class 文件可省略 |
| `--method` | `method` | 测试方法；需已唯一确定 class |
| `--runner` | `runner` | instrumentation runner override |
| `--extras` | `extras` | 分号分隔的 `k=v` 列表，转换为 MCP object |

硬边界：

- 不支持 `--package`、`--testPackage`、`--testsRegex`、`--regex`。
- 不支持旧 alias：`--clazz`、`--instrumentationRunner`、`-e`、`--e`。
- 当前项目没有 AndroidTest full-build baseline 时会返回 `INVALID_PARAMS`，并提示开启 Android Test、执行一次 full build / `gradle-build` 后再检查 `status.data.enabledAndroidTest=true`。

### `status`

```text
jugg status [--refresh-changes <true|false>] [--full-info <true|false>]
```

| CLI flag | MCP 参数 | 说明 |
|----------|----------|------|
| `--refresh-changes` / `--refreshChanges` | `refreshChanges` | 是否读取状态前刷新 git-tracked changed files；默认刷新，传 `false` 时跳过 |
| `--full-info` / `--fullInfo` | `fullInfo` | 是否返回完整状态信息；默认只返回前 20 个文件路径，传 `true` 时返回全部路径 |

关键字段：

- `executionType`：当前 Jugg run configuration 的 Gradle fallback 执行环境，取值 `local` / `remote`；AI command hook 在 `remote` 时会对 raw Gradle 命令强制先 block 一次，不再要求本次 Agent 会话先出现文件写入记录。
- `enabledAndroidTest`：最近一次持久化 full-build baseline 是否使用 AndroidTest target，不等同于单纯 UI toggle。
- `isCompiling`：当前是否有 compile/deploy 任务在运行；CLI 的 compile 类命令会用它做触发前等待。

### `layout-dump`

```text
jugg layout-dump [--root-layout <nodeId>] [--include-gone] [--all-windows]
```

| CLI flag | MCP 参数 | 说明 |
|----------|----------|------|
| `--root-layout` / `--rootLayout` | `rootLayout` | 跨窗口查找并只导出指定节点子树 |
| `--include-gone` / `--includeGone` | `includeGone=true` | 包含 GONE 节点 |
| `--all-windows` / `--allWindows` | `allWindows=true` | 导出所有窗口 |

公开输出是 HTML artifact；内部 JSON 仅供 `view-locate` / 布局验证实现消费。
App 侧所有 UI 查询和动作统一通过 Dragonfly 实时 snapshot；传统 Android View 与 Compose 节点保持原有 HTML/JSON 字段格式。Dragonfly 自带私有化 Kotlin/协程运行时，纯 Java 工程同样可用；Compose tooling 不兼容时由 Dragonfly 局部收口，不回退旧 ViewTree。5000 节点/60 层 snapshot 截断范围同时约束 dump、selector、tap、inspect 和 verify。

### `view-locate`

```text
jugg view-locate (--text <t> | --resource-id <id> | --content-desc <desc>)
```

| CLI flag | MCP 参数 |
|----------|----------|
| `--text` | `target.text` |
| `--resource-id` / `--resourceId` | `target.resourceId` |
| `--content-desc` / `--contentDesc` | `target.contentDesc` |

`matchCount > 1` 表示存在重复候选，不能直接把首个结果当作安全点击目标。

### `view-inspect`

```text
jugg view-inspect (--text <t> | --resource-id <id> | --content-desc <desc>)
                  [--class-name <cls>] <expr1> [<expr2> ...]
```

| CLI flag | MCP 参数 |
|----------|----------|
| `--text` | `target.text` |
| `--resource-id` / `--resourceId` | `target.resourceId` |
| `--content-desc` / `--contentDesc` | `target.contentDesc` |
| `--class-name` / `--className` | `target.className` |
| 位置参数 | `expressions[]` |

表达式使用 getter/query 方法调用格式，例如 `getText()`、`getVisibility()`、`isEnabled()`。
Android 节点读取原始 View；Compose 节点读取 Dragonfly 节点对象，因此 View 专属 getter 可能返回单项 error。

### `tap`

```text
jugg tap [--action tap|long-press|swipe]
         (--x <n> --y <n> [--end-x <n> --end-y <n>]
         | --x-percent <n> --y-percent <n> [--end-x-percent <n> --end-y-percent <n>]
         | --text <t> | --resource-id <id> | --content-desc <desc> [--class-name <cls>])
         [--duration <ms>]
```

| CLI flag | MCP 参数 | 说明 |
|----------|----------|------|
| `--action` | `action` | `tap`、`long-press`、`swipe`；默认 `tap` |
| `--x` / `--y` | `x` / `y` | 坐标模式起点 |
| `--end-x` / `--endX` | `endX` | swipe 终点 x |
| `--end-y` / `--endY` | `endY` | swipe 终点 y |
| `--x-percent` / `--xPercent` | `xPercent` | 百分比模式起点 x，范围 0-100 |
| `--y-percent` / `--yPercent` | `yPercent` | 百分比模式起点 y，范围 0-100 |
| `--end-x-percent` / `--endXPercent` | `endXPercent` | swipe 百分比终点 x |
| `--end-y-percent` / `--endYPercent` | `endYPercent` | swipe 百分比终点 y |
| `--text` | `text` | 元素模式 selector |
| `--resource-id` / `--resourceId` | `resourceId` | 元素模式 selector |
| `--content-desc` / `--contentDesc` | `contentDesc` | 元素模式 selector |
| `--class-name` / `--className` | `className` | 元素模式 AND 过滤 |
| `--duration` | `duration` | 手势时长，ms |

元素模式 selector 与 dump 使用同一 Dragonfly 节点模型。Compose 节点当前按 bounds 中心向所属 root View 派发 MotionEvent；尚不等价于 Compose Semantics action，也不能可靠识别 disabled/stale 节点。

`swipe` 在坐标模式必须提供 end 坐标；百分比模式必须提供 end 百分比坐标。

### `devices`

```text
jugg devices
```

无子命令参数。

### `activity-stack`

```text
jugg activity-stack
```

无子命令参数。

### `ssh-info`

```text
jugg ssh-info --reason <reason>
```

| CLI flag | MCP 参数 |
|----------|----------|
| `--reason` | `reason` |

### `wait-logs`

```text
jugg wait-logs --marker <regex> [--tags <t1,t2,...>] [--timeout-ms <ms>]
```

| CLI flag | MCP 参数 | 说明 |
|----------|----------|------|
| `--marker` | `marker` | Java Pattern 正则，必填 |
| `--tags` | `tags` | 逗号分隔 tag 白名单 |
| `--timeout-ms` / `--timeoutMs` | `timeoutMs` | 硬超时，范围 `[1000, 300000]`，默认 30000 |

---

## 7. 排查入口

| 现象 | 优先入口 |
|------|----------|
| 子命令是否公开、help 是否覆盖 | `jugg.py::COMMANDS` + `help_registry.py::COMMAND_HELP` |
| CLI flag 是否正确映射 MCP 参数 | 对应 `cmd_*.py::build_params()` |
| kebab-case 参数未生效 | `jugglib.normalize_args()` |
| CLI 找不到项目 | `jugglib.resolve_project_dir()`、`list-projects` 返回 |
| compile 类命令一直等待 | `status.isCompiling`、`jugglib.wait_for_compile_idle()` |
| 命令显示 compile 成功但部署失败 | 终态 `isCompileSuccess` / `isDeploySuccess` 与 `full log` |

---

## 8. 关联文档

- MCP 工具参数清单：`08_mcp_tools_list.md`
- MCP 设计说明：`08_mcp_design.md`
- 代码路径速查：`98_code_map.md`
- CLI / MCP 行为变更后的 skill 同步规则：`08_mcp_design.md` §9
