# jugg CLI 参数清单

> 最后核对：2026-05-10
> 一致性规则：文档与代码冲突时，以代码为准。

> **相关文档**：MCP 工具完整参数清单见 [`08_mcp_tools_list.md`](08_mcp_tools_list.md)

---

## 1. jugg CLI 概述

> 脚本位置：`docs/skills/jugg-android-dev-loop/scripts/`  
> 入口：`jugg.py`（跨平台）/ `jugg`（shell 包装）/ `jugg.cmd`（Windows）  
> 实现层：`scripts/py/jugglib.py` + `scripts/py/cmd/cmd_*.py`

`jugg` CLI 是一层把 MCP tools 包装为 POSIX 子命令的工具。参数透传到对应 MCP tool，但有以下统一行为优化：

### 1.1 统一优化行为

| 优化点 | 说明 |
|--------|------|
| **自动推断 projectDir** | 所有命令均不需要传 `--project-dir`，通过调用 `list-projects` 后与 `$PWD` 做最长前缀匹配自动确定 |
| **端口自动发现** | 先读本地缓存文件，不命中则扫描 `12320..12329`，命中后写缓存 |
| **异步编译自动轮询** | `compile`/`deploy`/`gradle-build` 自动调用 `get-compile-status` 到终态；返回 `running` 后立即再次请求，并附带 `waitTimeoutMs=3000` 进行短阻塞等待，减少尾部等待窗口 |
| **`--console=json` 模式** | 通过全局参数 `--console=json` 输出原始 structuredContent JSON 供脚本消费；默认以 `key: value` 格式输出 |

**端口/session 缓存文件位置**（可被环境变量覆盖）：

| 文件 | 默认路径 | 环境变量 |
|------|----------|----------|
| 端口缓存 | `~/.cache/jugg/port`（Linux/macOS）/ `%LOCALAPPDATA%/jugg/port`（Windows） | `JUGG_PORT_CACHE` |
| 缓存根目录 | `~/.cache/jugg/` | `JUGG_CACHE_DIR` |

### 1.2 CLI flag 双格式兼容

CLI flag 同时接受 **kebab-case**（POSIX 惯例，文档示例默认格式）和 **camelCase**（= MCP 参数名）：

```bash
# 以下两种写法完全等价：
jugg layout-dump --include-gone          # kebab-case（默认示例格式）
jugg layout-dump --includeGone           # camelCase（= MCP param name）
```

内部实现：`jugglib.normalize_args()` 在解析前将 kebab-case 转为 camelCase，解析器只匹配 camelCase。转换规则为纯机械的 kebab→camelCase，无特例：`to_camel_case(flag) === MCP_key`。

### 1.3 CLI 参数设计强制约束（1:1 透传原则）

> **⚠️ AI 必读：新增或修改 CLI 参数时，必须遵守以下规则，违反即为错误设计。**

**核心约束：CLI 参数与 MCP 参数必须严格 1:1 对应，CLI 不得创造 MCP 不存在的参数语义。**

| 规则 | 正确做法 | 错误做法（禁止） |
|------|----------|-----------------|
| boolean 参数只有一个 flag | `--always-restart-app false`（带值透传） | `--no-always-restart-app`（CLI 自造反向 flag，MCP 无对应） |
| flag 名 = MCP 参数名的 kebab-case | `--always-restart-app` → `alwaysRestartApp` | 自造与 MCP key 无法机械互转的别名 |
| 默认值由 MCP 端决定，CLI 省略即不传 | 省略 `--always-restart-app` → MCP 用自身默认值 | CLI 硬编码默认值覆盖 MCP 默认值 |
| 参数透传不做语义转换 | 值原样发给 MCP | CLI 把两个 flag 合并/拆分成不同的 MCP 参数 |

**判断方法**：新增 CLI flag 后，检查 `jugglib.normalize_args()` + `build_params()` 的结果能否与 MCP 参数表完全对齐。若需要在 CLI 侧做任何"翻译"逻辑，即违反本约束。

---

## 2. 子命令列表与 CLI 参数

| 子命令 | 对应 MCP tool | 说明 |
|--------|--------------|------|
| `version` | `version` | 显示 CLI 版本和插件版本（无需 projectDir） |
| `compile` | `compile` | 增量编译（自动轮询） |
| `deploy` | `deploy` | 编译并部署（自动轮询） |
| `gradle-build` | `gradle-build` | Gradle 构建（自动轮询） |
| `clean-reinstall` | `clean-reinstall` | 清数据并重装 APK |
| `restart` | `restart` | 重启 App |
| `instrument` | `instrument` | 运行 androidTest（参数风格接近 `am instrument`） |
| `status` | `status` | 查看当前部署状态与未编译文件摘要 |
| `layout-dump` | `layout-dump` | 导出 UI 层级 HTML |
| `view-locate` | `view-locate` | 查找元素位置 |
| `view-inspect` | `view-inspect` | 反射查询 View 属性 |
| `tap` | `tap` | 触控操作 |
| `devices` | `devices` | 列出设备 |
| `activity-stack` | `activity-stack` | 查看 Activity 栈 |
| `ssh-info` | `ssh-info` | 申请 SSH 排障信息 |
| `wait-logs` | `wait-logs` | 阻塞等待 App 日志直到 marker/crash/超时 |

---

#### JSON 输出
```
jugg --console=json <subcommand> [options]
```

`--console` 是全局参数，必须放在子命令前。

---

#### `version`
```
jugg version
```
**行为说明**：直接调用 MCP `version` 工具（无需 projectDir），同时展示内置 CLI 版本和插件版本。

默认输出示例：
```
cli version: 1.0.1
plugin version: 1.2.3
```

当各工程版本不一致时，额外输出每个工程的版本 map：
```
cli version: 1.0.1
plugin version: 1.2.3
  (versions differ across projects)
  /path/to/projectA: 1.2.3
  /path/to/projectB: 1.2.0
```

`--console=json` 模式返回：`{"cliVersion": "...", "plugin": <MCP structuredContent>}`

---

#### `compile`
```
jugg compile
```
**行为优化**：自动轮询到 `isFinal=true`。

---

#### `deploy`
```
jugg deploy [--always-restart-app <true|false>]
```
**行为优化**：自动轮询到 `isFinal=true`，启动时打印 `Deploying...`。

| CLI flag (kebab-case) | CLI flag (camelCase = MCP 参数名) | MCP 参数 | 说明 |
|-----------------------|----------------------------------|----------|------|
| `--always-restart-app <true\|false>` | `--alwaysRestartApp <true\|false>` | `alwaysRestartApp` | 默认 `true`，部署后强制重启 App；传 `false` 允许 HOT RELOAD |

---

#### `gradle-build`
```
jugg gradle-build
```
**行为优化**：自动轮询到 `isFinal=true`，启动时打印 `Running Gradle build...`。

---

#### `clean-reinstall`
```
jugg clean-reinstall
```

---

#### `restart`
```
jugg restart
```

---

#### `instrument`
```
jugg instrument --source-path <src/androidTest/.../FooTest.kt>
                [--class <Fqcn>] [--method <method>] [--runner <runnerFqn>]
                [--extras <k=v;k2=v2>]
```

| CLI flag (kebab-case) | CLI flag (camelCase = MCP 参数名) | MCP 参数 | 说明 |
|-----------------------|----------------------------------|----------|------|
| `--source-path <value>` | `--sourcePath <value>` | `sourcePath` | androidTest 源文件路径，用于解析 module 与 test APK |
| `--class <value>` | `--class <value>` | `class` | 文件内测试类，单 class 文件可省略 |
| `--method <value>` | `--method <value>` | `method` | 测试方法，需已唯一确定 class |
| `--runner <value>` | `--runner <value>` | `runner` | instrumentation runner override |
| `--extras <k=v;k2=v2>` | `--extras <k=v;k2=v2>` | `extras` | 批量 extras |

---

#### `layout-dump`
```
jugg layout-dump [--root-layout <nodeId>] [--include-gone] [--all-windows]
```

| CLI flag (kebab-case) | CLI flag (camelCase = MCP 参数名) | MCP 参数 | 说明 |
|-----------------------|----------------------------------|----------|------|
| `--root-layout <id>` | `--rootLayout <id>` | `rootLayout` | 只导出指定节点子树 |
| `--include-gone` | `--includeGone` | `includeGone` | 包含 GONE 节点 |
| `--all-windows` | `--allWindows` | `allWindows` | 导出所有窗口 |

---

#### `view-locate`
```
jugg view-locate (--text <t> | --resource-id <id> | --content-desc <desc>)
```

| CLI flag (kebab-case) | CLI flag (camelCase = MCP 参数名) | MCP 参数 |
|-----------------------|----------------------------------|----------|
| `--text <t>` | — | `target.text` |
| `--resource-id <id>` | `--resourceId <id>` | `target.resourceId` |
| `--content-desc <desc>` | `--contentDesc <desc>` | `target.contentDesc` |

---

#### `view-inspect`
```
jugg view-inspect (--text <t> | --resource-id <id> | --content-desc <desc>) [--class-name <cls>]
                  <expr1> [<expr2> ...]
```

| CLI flag (kebab-case) | CLI flag (camelCase = MCP 参数名) | MCP 参数 |
|-----------------------|----------------------------------|----------|
| `--text <t>` | — | `target.text` |
| `--resource-id <id>` | `--resourceId <id>` | `target.resourceId` |
| `--content-desc <desc>` | `--contentDesc <desc>` | `target.contentDesc` |
| `--class-name <cls>` | `--className <cls>` | `target.className` |
| 位置参数（非 `--` 开头） | — | `expressions[]` |

---

#### `tap`
```
jugg tap [--action tap|long-press|swipe]
         ( --x <n> --y <n> [--end-x <n> --end-y <n>]                          # 坐标模式
         | --x-percent <n> --y-percent <n> [--end-x-percent <n> --end-y-percent <n>]  # 百分比模式
         | --text <t> | --resource-id <id> | --content-desc <desc> [--class-name <cls>] )  # 元素模式
         [--duration <ms>]
```

| CLI flag (kebab-case) | CLI flag (camelCase = MCP 参数名) | MCP 参数 | 说明 |
|-----------------------|----------------------------------|----------|------|
| `--action tap` | — | `action=tap` | 默认值 |
| `--action long-press` | — | `action=long-press` | |
| `--action swipe` | — | `action=swipe` | |
| `--x/--y` | — | `x/y` | 坐标模式 |
| `--end-x/--end-y` | `--endX/--endY` | `endX/endY` | swipe 终点 |
| `--x-percent/--y-percent` | `--xPercent/--yPercent` | `xPercent/yPercent` | 百分比模式（0-100） |
| `--end-x-percent/--end-y-percent` | `--endXPercent/--endYPercent` | `endXPercent/endYPercent` | swipe 百分比终点 |
| `--text` | — | `text` | 元素模式 |
| `--resource-id` | `--resourceId` | `resourceId` | 元素模式 |
| `--content-desc` | `--contentDesc` | `contentDesc` | 元素模式 |
| `--class-name` | `--className` | `className` | 元素 AND 过滤 |
| `--duration` | — | `duration` | ms |

---

#### `devices`
```
jugg devices
```

---

#### `activity-stack`
```
jugg activity-stack
```

---

#### `status`
```
jugg status
```

返回包含 `enabledAndroidTest`（基于最近 full build 基线）用于判定当时是否开启 Android Test 增量模式。

---

#### `ssh-info`
```
jugg ssh-info --reason <reason>
```

| CLI flag | MCP 参数 |
|----------|----------|
| `--reason <str>` | `reason` |

---

#### `wait-logs`
```
jugg wait-logs --marker <regex> [--tags <t1,t2,...>] [--timeout-ms <ms>]
```

| CLI flag (kebab-case) | CLI flag (camelCase = MCP 参数名) | MCP 参数 | 说明 |
|-----------------------|----------------------------------|----------|------|
| `--marker <regex>` | — | `marker` | Java Pattern 正则，必填 |
| `--tags <t1,t2>` | — | `tags` | 逗号分隔 tag 白名单 |
| `--timeout-ms <ms>` | `--timeoutMs <ms>` | `timeoutMs` | 硬超时，范围 [1000, 300000]，默认 30000 |

---

## 3. 关联文档

- MCP 工具参数清单：`08_mcp_tools_list.md`
- 设计说明：`08_mcp_design.md`
- 路径速查：`98_code_map.md`
