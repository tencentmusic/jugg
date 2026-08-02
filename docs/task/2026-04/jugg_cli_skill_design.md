# Jugg CLI Skill 设计方案

> 创建日期：2026-04-07

---

## 1. 背景与目标

Jugg 插件通过 HTTP + JSON-RPC 2.0 暴露 MCP 工具能力，端口范围 `12320..12329`，无鉴权，无状态。

目标：将所有 MCP 工具封装为 Bash CLI Skill，使用户无需配置 MCP 即可通过 `jugg <subcommand>` 调用所有能力。MCP 与 CLI 并存，互不影响。

Skill 位置：`jugg_f1/.claude/skills/jugg-cli/`（后续可单独分发）。

---

## 2. 公共基础设施

### 2.1 目录结构

```
jugg-cli/
├── SKILL.md
└── bin/
    ├── jugg       # 主入口（分发子命令）
    └── _lib.sh    # 公共库（端口探测、请求封装、projectDir 解析、输出处理）
```

### 2.2 端口探测与缓存

- 缓存文件：`~/.cache/jugg/port`
- 逻辑：先读缓存，发一次 ping 验证是否仍有效；无效则扫描 `12320..12329`，找到第一个响应的写入缓存
- 每个端口探测超时：1s

### 2.3 projectDir 自动解析（无需用户传参）

所有命令不接受 `-p` 参数，完全基于 `$PWD` 自动识别：

1. 内部调用 `list_projects` 获取 IDE 已初始化的项目列表
2. 将每个 `projectDir` 与 `$PWD` 做**前缀匹配**
3. 无匹配 → 报错退出（提示：当前目录不在任何 Jugg 项目下）
4. 有匹配 → 取**最长前缀匹配**（最精确的项目）

### 2.4 请求封装

`_lib.sh` 提供统一的 `jugg_call <tool_name> <params_json>` 函数：
- 拼装 JSON-RPC 2.0 请求体（`tools/call` 方法）
- POST 到 `http://localhost:<port>/jugg-mcp`
- 检查 HTTP 状态码
- 检查响应体 `status` 字段，非 `OK` 时打印 `message` 并以非零状态码退出

### 2.5 输出约定

- 默认：`key: value` 纯文本格式，对 LLM 和人类均可读
- `--json`：原始响应 JSON（所有子命令均支持，供人类调试，**不在 SKILL.md 中暴露**）

示例（默认输出）：
```
status: OK
file: /path/to/screenshot.jpg

status: OK
found: true
bounds: [16, 278, 67, 324]
size: 51x46dp
confidence: 0.92

status: ERROR
message: No matching UI element found
```

**SKILL.md 不提及 `--json` 参数**，LLM 始终使用默认文本输出。

### 2.6 录屏状态管理

- `record-start`：调用前检查 `~/.cache/jugg/record_session` 是否存在，存在则报错（"已有录屏任务进行中，请先执行 `jugg record-stop`"）；成功后将 `sessionId` 写入该文件
- `record-stop`：从 `~/.cache/jugg/record_session` 读取 `sessionId`，不存在则报错；成功后删除缓存文件
- `sessionId` 对用户和 LLM 完全不可见

> 注：所有子命令均支持 `--json` flag 输出原始响应，供人类调试使用，不在 SKILL.md 中暴露。以下命令签名省略 `[--json]`。

### 3.1 `screenshot`

```
jugg screenshot
```

- 输出：优化后的图片文件路径（`data.file`）

---

### 3.2 `crash-report`

```
jugg crash-report
```

- 输出：崩溃摘要 + 日志文件路径（`allErrorLogPath` artifact）

---

### 3.3 `compile`

```
jugg compile
```

- 对应 MCP `compile-only`，同步等待结果
- 输出：编译状态

---

### 3.4 `deploy`

```
jugg deploy
```

- 对应 MCP `compile-and-deploy`
- 同步轮询到终态，按响应中的 `pollIntervalSuggestedMs` 间隔轮询，实时打印进度
- 输出：最终编译部署状态

---

### 3.5 `gradle-build`

```
jugg gradle-build
```

- 对应 MCP `force-gradle-compile`
- 轮询逻辑同 `deploy`

---

### 3.6 `reinstall`

```
jugg reinstall
```

- 对应 MCP `clean-reinstall-apk`

---

### 3.7 `restart`

```
jugg restart [--tap <step>...]
```

对应 MCP `restart-app`，支持可选 `--tap` 串行导航步骤。

#### tap 步骤语法

每个 `--tap` 为一个步骤字符串，格式：`<action>:<selector>`，`action` 默认 `tap`。

| 模式 | 示例 |
|------|------|
| 元素（text） | `tap:text=登录` |
| 元素（id） | `tap:id=btn-confirm` |
| 元素（desc） | `tap:desc=关闭按钮` |
| 百分比坐标 | `tap:50%,80%` |
| 绝对坐标 | `tap:540,960` |
| 长按 | `long-press:text=登录` |
| 滑动（百分比） | `swipe:50%,80%,50%,20%` |
| 滑动（坐标） | `swipe:540,960,540,200` |

示例：

```bash
jugg restart --tap "tap:text=登录" --tap "tap:id=btn-confirm"
jugg restart --tap "swipe:50%,80%,50%,20%"
```

---

### 3.8 `record-start`

```
jugg record-start
```

- 对应 MCP `start-record`
- 调用前检查并发锁，存在则报错；成功后将 `sessionId` 写入缓存（对用户不可见）
- 输出：提示"录屏已开始"

---

### 3.9 `record-stop`

```
jugg record-stop
```

- 对应 MCP `stop-record`
- 从缓存读取 `sessionId`，不存在则报错；成功后删除缓存文件
- 输出：mp4 文件路径

---

### 3.10 `layout-dump`

```
jugg layout-dump [--root <id>] [--include-gone] [--all-windows]
```

- 对应 MCP `layout-dump`
- 输出：HTML 文件路径（`data.file`）+ 节点数摘要（`message`）

---

### 3.11 `view-locate`

```
jugg view-locate [--text <text>] [--id <resourceId>] [--desc <contentDesc>]
```

- 对应 MCP `view-locate`
- 三个 selector 至少传一个
- 输出：`bounds`、`position`、`size`、`className`、`confidence`

---

### 3.12 `view-inspect`

```
jugg view-inspect \
  [--text <text>] [--id <resourceId>] [--desc <contentDesc>] [--class <className>] \
  <expr> [<expr>...]
```

- 对应 MCP `view-inspect`
- selector 至少传一个，AND 逻辑
- `expr` 为位置参数，可传多个，如 `getTextSize()` `isEnabled()`
- 输出：每个表达式的值与类型

---

### 3.13 `tap`

```
jugg tap [OPTIONS]
```

对应 MCP `tap`，三种模式互斥，优先级：坐标 > 百分比 > 元素。

#### 元素模式
```bash
jugg tap --text <text> [--class <className>] [--action tap|long-press]
jugg tap --id <resourceId> [--class <className>] [--action tap|long-press]
jugg tap --desc <contentDesc> [--class <className>] [--action tap|long-press]
```

#### 百分比模式
```bash
jugg tap --xp <0-100> --yp <0-100> [--action tap|long-press]
jugg tap --xp <0-100> --yp <0-100> --end-xp <0-100> --end-yp <0-100> --action swipe [--duration <ms>]
```

#### 坐标模式
```bash
jugg tap --x <px> --y <px> [--action tap|long-press]
jugg tap --x <px> --y <px> --end-x <px> --end-y <px> --action swipe [--duration <ms>]
```

`--action` 默认 `tap`。

---

### 3.14 `devices`

```
jugg devices
```

- 对应 MCP `device-list`
- 输出：设备列表，标记 selected

---

### 3.15 `activity-stack`

```
jugg activity-stack
```

- 对应 MCP `activity-stack`
- 输出：Activity 栈

---

### 3.16 `ssh-info`

```
jugg ssh-info --reason <reason>
```

- 对应 MCP `request-remote-ssh-info`
- CLI 调用本身视为用户同意，固定传 `userConsent=true`

---

## 4. 子命令汇总

| 子命令 | 对应 MCP 工具 | 说明 |
|--------|--------------|------|
| `screenshot` | `screenshot` | 截图 |
| `crash-report` | `crash-report` | 崩溃报告 |
| `compile` | `compile-only` | 仅编译 |
| `deploy` | `compile-and-deploy` | 编译并部署（同步到终态） |
| `gradle-build` | `force-gradle-compile` | Gradle 强制构建（同步到终态） |
| `reinstall` | `clean-reinstall-apk` | 清数据重装 APK |
| `restart` | `restart-app` | 重启 App（支持 tap 导航步骤） |
| `record-start` | `start-record` | 开始录屏（防并发） |
| `record-stop` | `stop-record` | 停止录屏，输出 mp4 路径 |
| `layout-dump` | `layout-dump` | 导出 UI 层级 HTML |
| `view-locate` | `view-locate` | 查找 UI 元素位置 |
| `view-inspect` | `view-inspect` | 查询 View 属性 |
| `tap` | `tap` | 触控操作（三模式） |
| `devices` | `device-list` | 列出设备 |
| `activity-stack` | `activity-stack` | Activity 栈 |
| `ssh-info` | `request-remote-ssh-info` | 申请 SSH 排障信息 |

共 16 个子命令。`list-projects` 仅内部使用，不对外暴露。

---

## 5. 未覆盖工具说明

**本期不实现：**
- `figma-layout-verify`：推迟到后续版本

**已注册但不封装：**
- `start-app`、`start-activity`、`emulator-list`、`start-emulator`：MCP 侧当前未注册到默认工具列表，待注册后按相同模式补充

**废弃工具：**
- `layout-verify`：已被 `view-locate` + `figma-layout-verify` 替代，不封装

---

## 6. 实现优先级

1. `_lib.sh`：端口探测 + projectDir 解析 + 请求封装（框架验证，用 `screenshot` 测试）
2. `deploy`：验证异步轮询逻辑
3. `layout-dump` + `view-locate` + `view-inspect`：验证参数解析
4. `tap` + `restart`：验证 tap 步骤解析
5. `record-start` + `record-stop`：验证缓存状态逻辑
6. 其余简单子命令批量补全
