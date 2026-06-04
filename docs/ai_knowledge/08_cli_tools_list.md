# jugg CLI 参数与 MCP 映射

> 最后核对：2026-05-23
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
| `docs/skills/jugg-android-dev-loop/scripts/py/cmd/cmd_*.py` | 各子命令参数解析；只做 MCP 参数直传和必要的本地校验 |
| `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolActionRegistry.kt` | MCP 注册工具事实来源；CLI 子命令必须映射到这里的公开工具 |

---

## 3. 全局行为

### 3.1 projectDir 解析

默认路径：

```text
jugg.py
  -> jugglib.resolve_project_dir()
  -> list-projects
  -> 用当前工作目录和已初始化项目做最长前缀匹配
  -> 将匹配结果作为 MCP projectDir
```

传入 `--project-dir <path>` 或 `--project-dir=<path>` 时，直接把该路径作为 MCP `projectDir`，跳过 `list-projects` 与 `$PWD` 匹配。`--projectDir` 作为 camelCase 全局别名也会被归一化。

### 3.2 端口与缓存

CLI 先读端口缓存，不命中再扫描 `12320..12329`。
当扫描失败时，CLI 会输出每个端口的探测摘要，便于区分“IDE 插件未监听/项目未打开”和 timeout、HTTP 5xx 等异常态。只有出现 timeout、HTTP 5xx 或其它非预期异常时才会短重试；所有端口都是 connection refused 时不重试。

| 文件 | 默认路径 | 环境变量 |
|------|----------|----------|
| 端口缓存 | `~/.cache/jugg/port`（Linux/macOS）/ `%LOCALAPPDATA%/jugg/port`（Windows） | `JUGG_PORT_CACHE` |
| 缓存根目录 | `~/.cache/jugg/` | `JUGG_CACHE_DIR` |

### 3.3 输出模式

```text
jugg --console=plain <subcommand>
jugg --console=rich <subcommand>
jugg --console=json <subcommand>
```

- `plain`：直接 `python3 jugg.py` 的默认模式，不显示 spinner。
- `rich`：shell / Windows wrapper 默认注入，面向人工终端显示 spinner。
- `json`：输出 MCP `structuredContent` JSON，供脚本或 Agent 消费。

全局参数由 `jugg.py` 在子命令分发前抽取；示例统一写在子命令前，便于阅读。

### 3.4 并发 compile 策略

```text
jugg [--if-compiling wait|interrupt] <compile|deploy|gradle-build|instrument> [options]
```

- `wait`（默认）：触发前轮询 `status.isCompiling=false`。
- `interrupt`：跳过等待，立即调用目标 MCP tool；服务端沿用“新任务中断旧任务”的语义。
- 这是 CLI-only 全局参数，不发送给 MCP。

### 3.5 异步编译轮询

`compile`、`deploy`、`gradle-build`、`instrument` 经 `jugglib.compile_call()` 调用。若首次响应 `data.status=running`，CLI 用 `get-compile-status` + `waitTimeoutMs=3000` 轮询到终态，并保留首次响应中的 `logPath`。

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

`jugglib.normalize_args()` 只做 kebab-case 到 camelCase 的机械转换，不做语义 alias。每个 `cmd_*.py` 的 `build_params()` 是实际参数直传边界。

---

## 5. 公开子命令

当前公开 CLI 子命令共 16 个，来自 `jugg.py::COMMANDS`。

| 子命令 | MCP tool | 说明 |
|--------|----------|------|
| `version` | `version` | 显示 CLI 版本和插件版本；无需 `projectDir` |
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

### `compile`

```text
jugg compile
```

无子命令参数。终态输出 `status`、`message`、`full log`、`detail` 等字段。

### `deploy`

```text
jugg deploy [--always-restart-app <true|false>]
```

| CLI flag | MCP 参数 | 说明 |
|----------|----------|------|
| `--always-restart-app` / `--alwaysRestartApp` | `alwaysRestartApp` | `true` 时部署后强制重启 App；`false` 允许 HOT RELOAD。省略时由 MCP 默认值决定 |

终态输出 `isCompileSuccess`、`isDeploySuccess` 与日志路径。判断部署是否成功时必须同时看 deploy 结果，不要只看 compile 是否成功。

CLI 当前不暴露 MCP 的 `waitAppReadyAfterSuccess` 参数；省略时按 MCP 默认值 `false`，即只等待 compile/deploy 任务终态，不额外等待 App ready。

### `gradle-build`

```text
jugg gradle-build
```

无子命令参数。该命令会走 Gradle 构建后的安装/启动链路；无设备或启动失败时可能出现 `isCompileSuccess=true` 且 `isDeploySuccess=false`。失败时会打印 `detail`，包含 Gradle build 日志摘要，例如 `Compile project failed, please check the error message.` 后面的实际错误行；长日志 preview 上限为 8KB，采用 4KB 开头 + 4KB 结尾。

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
jugg status [--refresh-changes <true|false>]
```

| CLI flag | MCP 参数 | 说明 |
|----------|----------|------|
| `--refresh-changes` / `--refreshChanges` | `refreshChanges` | 是否读取状态前刷新 git-tracked changed files；默认不刷新 |

关键字段：

- `enabledAndroidTest`：最近一次持久化 full-build baseline 是否使用 AndroidTest target，不等同于单纯 UI toggle。
- `isCompiling`：当前是否有 compile/deploy 任务在运行；CLI 的 compile 类命令会用它做触发前等待。

### `layout-dump`

```text
jugg layout-dump [--root-layout <nodeId>] [--include-gone] [--all-windows]
```

| CLI flag | MCP 参数 | 说明 |
|----------|----------|------|
| `--root-layout` / `--rootLayout` | `rootLayout` | 只导出指定节点子树 |
| `--include-gone` / `--includeGone` | `includeGone=true` | 包含 GONE 节点 |
| `--all-windows` / `--allWindows` | `allWindows=true` | 导出所有窗口 |

公开输出是 HTML artifact；内部 JSON 仅供 `view-locate` / 布局验证实现消费。

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
