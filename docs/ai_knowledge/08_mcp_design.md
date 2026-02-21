# Jugg 技术文档 - MCP 设计（Phase 1）

---

## 一、目标与边界

### 1.1 目标

- 在 Jugg 内新增独立 MCP 能力，和历史 `RpcLocalServer`/`IJuggManagerCaller.call` 解耦。
- 通过新增 `invokeMcp` 形成新的分层调用入口，设计风格与 `call` 一致，但实现独立。
- Phase 1 先打通：`list_projects` + `restart_app`。

### 1.2 明确边界

- 本文只定义架构与协议，不涉及代码实现细节。
- MCP 第一版采用 **HTTP 传输 + 标准 MCP JSON-RPC 语义**。
- 所有 MCP 命令都要求 `projectDir` 必填（包括工具调用）。

---

## 二、核心约束

### 2.1 全局参数约束

- `projectDir` 是所有 MCP 命令的必填参数。
- 若缺失或为空，直接返回参数错误，不进入业务执行。

### 2.2 项目范围约束

- `list_projects` 只返回 IDE 已初始化项目。
- 数据来源固定为 `com.sickworm.intellij.jugg.loader.JuggInitializer#instanceSet`。

### 2.3 设备选择约束（后续阶段同样沿用）

- 未传 `serial` 时：自动选择 selected device。
- `serial` 非法或不匹配设备时：不失败，自动回落 selected device。
- 响应 `message` 中显式提示回落细节（例如多设备自动选择、非法 serial 已回落）。

### 2.4 通用设备选择规则（DeviceSelectionResolver）

适用范围：
- 所有涉及设备执行的 MCP 工具（`restart_app`、`compile_and_deploy`、`clean_reinstall_apk`、`screenshot`、`record`、`layout_dump` 等）。

输入：
- `serial`（可选）
- 当前 `projectDir` 对应的 `IDeployTargetManager`

选择流程（统一顺序）：
1. 获取 selected device（`getSelectedDevices()`）与 connected devices（`getConnectedDevices()`）。
2. 若传入 `serial` 且在 connected devices 中匹配：使用该设备。
3. 若 `serial` 缺失：使用 selected device。
4. 若 `serial` 非法/未匹配：回落 selected device。
5. 若设备列表为空，或 selected device 不可用（为空或离线），且该命令要求必须有设备：返回 `MCP_NO_DEVICE`。

输出：
- `resolvedDevice`: 实际执行设备（serial/name）。
- `selectionReason`: 选择原因（`BY_SERIAL`/`FALLBACK_SELECTED_MISSING_SERIAL`/`FALLBACK_SELECTED_INVALID_SERIAL`）。
- `messageDetail`: 需要拼入响应 `message` 的可读说明。

`message` 规范建议：
- 成功且按 serial 命中：`"Device selected by serial: <serial>."`
- 缺失 serial 回落：`"Serial not provided; selected device '<serial>' is used."`
- serial 非法回落：`"Serial '<input>' is invalid; fallback to selected device '<serial>'."`

说明：
- 回落是兼容策略，不视为错误；应保留 `status=OK`。
- 仅当无可用设备（含 selected device 不可用）时返回错误，避免在多设备环境下频繁失败。

### 2.5 通用错误与 message 组装规则（Error + Message Policy）

目标：
- 保证所有 MCP 工具返回结构与文案风格一致，便于 AI 调用侧稳定解析。

统一返回结构：
- `status`: `OK` / `ERROR`
- `message`: 人类可读摘要 + 关键 detail（英文）
- `data`: 业务结果对象
- `artifacts`: 产物路径数组（无则空数组）
- `errorCode`: 错误码（成功为 `null`）

状态与错误码规则：
1. 执行成功（包括 serial 回落成功）
   - `status=OK`
   - `errorCode=null`
2. 参数缺失/非法（含 `projectDir` 缺失）
   - `status=ERROR`
   - `errorCode=MCP_INVALID_PARAMS`
3. 业务资源不可用（项目未初始化、无设备或 selected device 不可用）
   - `status=ERROR`
   - `errorCode` 使用具体业务码（如 `MCP_PROJECT_NOT_INITIALIZED`、`MCP_NO_DEVICE`）
4. 未知异常
   - `status=ERROR`
   - `errorCode=MCP_INTERNAL_ERROR`

`message` 组装模板：
- 成功主句：`"<tool> executed successfully."`
- 成功附加 detail（可选）：
  - `"Serial not provided; selected device '<serial>' is used."`
  - `"Serial '<input>' is invalid; fallback to selected device '<serial>'."`
- 失败主句：`"<tool> failed."`
- 失败附加 detail（建议）：
  - `"Reason: projectDir is required."`
  - `"Reason: project is not initialized."`
  - `"Reason: no connected device is available."`

字段填充约束：
- 即使失败，`data` 也应返回对象（至少 `{}`），避免调用方判空分支过多。
- `artifacts` 始终返回数组类型。
- `message` 不包含堆栈；堆栈放日志系统。

向后扩展建议：
- Phase 2/3 新工具直接复用本节规则，不再新增工具级 message 风格。
- 若未来引入国际化，先保证错误码稳定，再切换 message 文案语言。

---

## 三、分层架构设计

### 3.1 逻辑分层

```
MCP HTTP Endpoint
  → McpJsonRpcDispatcher
    → McpToolRegistry
      → McpInvoker (入口：invokeMcp)
        → McpAction (按工具拆分)
          → IJuggManagerCaller / JuggManager / DeployTargetManager
```

### 3.2 角色职责

- `McpJsonRpcDispatcher`
  - 解析 JSON-RPC 请求。
  - 校验协议字段（`jsonrpc`、`id`、`method`、`params`）。
  - 映射 `tools/list` 与 `tools/call`。

- `McpToolRegistry`
  - 维护工具元数据（名称、描述、参数 schema、是否需要设备）。
  - 对外提供工具清单。

- `McpInvoker`
  - 新增统一入口：`invokeMcp(request)`。
  - 做全局前置校验（包含 `projectDir` 必填）。
  - 将工具调用路由至对应 `McpAction`。

- `McpAction`（`RestartAppAction`、`ListProjectsAction` 等）
  - 聚焦单一工具逻辑。
  - 负责业务参数解析与调用底层能力。

- `McpResultMapper`
  - 统一把执行结果转换为响应结构：`status/message/data/artifacts/errorCode`。

---

## 四、协议与数据模型

### 4.1 JSON-RPC 方法映射

- `tools/list`：返回可用工具清单。
- `tools/call`：执行指定工具。

### 4.2 `tools/list` 返回示例（语义）

```json
{
  "tools": [
    {
      "name": "list_projects",
      "description": "List initialized projects",
      "inputSchema": {
        "type": "object",
        "properties": {
          "projectDir": { "type": "string" }
        },
        "required": ["projectDir"]
      }
    },
    {
      "name": "restart_app",
      "description": "Restart app",
      "inputSchema": {
        "type": "object",
        "properties": {
          "projectDir": { "type": "string" },
          "serial": {
            "type": "string",
            "description": "Optional. If absent or invalid, fallback to selected device."
          }
        },
        "required": ["projectDir"]
      }
    }
  ]
}
```

### 4.3 统一业务响应模型

```json
{
  "status": "OK|ERROR",
  "message": "string",
  "data": {},
  "artifacts": [
    {
      "type": "screenshot|record|layout_dump|log|other",
      "path": "string"
    }
  ],
  "errorCode": "string|null"
}
```

说明：
- `artifacts` 在 Phase 1 通常为空数组。
- `errorCode` 仅错误时必填；成功可为 `null`。

---

## 五、错误码设计（Phase 1）

- `MCP_INVALID_JSON_RPC`：JSON-RPC 格式错误。
- `MCP_METHOD_NOT_SUPPORTED`：不支持的方法。
- `MCP_TOOL_NOT_FOUND`：工具不存在。
- `MCP_INVALID_PARAMS`：参数校验失败（含 `projectDir` 缺失）。
- `MCP_PROJECT_NOT_INITIALIZED`：`projectDir` 未在 `JuggInitializer#instanceSet` 中。
- `MCP_NO_DEVICE`：设备列表为空，或 selected device 不可用，且该命令要求必须有已连接设备。
- `MCP_INTERNAL_ERROR`：未分类内部异常。

---

## 六、Phase 1 工具设计

### 6.1 `list_projects`

输入：
- `projectDir`（必填）

执行：
- 读取 `JuggInitializer#instanceSet`。
- 输出当前已初始化项目目录集合（可附带是否当前项目标记）。

输出 `data` 建议：

```json
{
  "projects": [
    {
      "projectDir": "/abs/path",
      "initialized": true
    }
  ]
}
```

### 6.2 `restart_app`

输入：
- `projectDir`（必填）
- `serial`（可选）

执行：
- 获取 `projectDir` 对应 `JuggManager`。
- 通过既有设备管理能力执行 app 重启。
- 无 `serial` 或 `serial` 非法时，统一回落 selected device 并在 `message` 返回 detail。

输出 `data` 建议：

```json
{
  "deviceSerial": "emulator-5554",
  "packageName": "com.example.app",
  "restarted": true
}
```

---

## 七、Phase 1 调用时序

### 7.1 `tools/call` → `restart_app`

```
Client
  → MCP HTTP Endpoint
  → McpJsonRpcDispatcher (validate)
  → McpInvoker.invokeMcp (global validate projectDir)
  → RestartAppAction.execute
  → JuggManager / DeployTargetManager
  → McpResultMapper
  → JSON-RPC Response
```

关键检查点：
- JSON-RPC 合法性。
- `projectDir` 必填且已初始化。
- 设备可用性与默认选择策略。

---

## 八、目录与产物规划

### 8.1 产物根目录

- `JuggPathManager.juggRootDir/mcp_fetch`

### 8.2 子目录约定（提前冻结，Phase 3 启用）

- `screenshot/`
- `record/`
- `layout_dump/`

---

## 九、与历史 RPC 的关系

- `IJuggManagerCaller.call` 已移除；统一通过 MCP 入口与 `JuggManager.runFirstConfiguration` 直连运行能力。
- MCP 通过 `invokeMcp` 建立新入口，不与历史命令枚举强耦合。
- 可复用底层能力（`JuggManager`、`IDeployTargetManager`），但不复用历史 RPC 协议结构。

---

## 十、Phase 1 验收标准

- 可通过标准 JSON-RPC 的 `tools/list` 获取工具定义。
- 可通过 `tools/call` 调用 `list_projects` 与 `restart_app`。
- 缺失 `projectDir` 时返回 `MCP_INVALID_PARAMS`。
- 非初始化项目返回 `MCP_PROJECT_NOT_INITIALIZED`。
- 成功响应结构统一为 `status/message/data/artifacts/errorCode`。

---

## 十一、Phase 1 API 合约示例（JSON-RPC）

说明：
- 以下示例聚焦 JSON-RPC 载荷，HTTP 路径/端口由 MCP 服务实现决定。
- `message` 与 `description` 使用英文，便于客户端统一处理。

### 11.1 `tools/list` 请求与响应

请求：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/list",
  "params": {}
}
```

响应：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {
        "name": "list_projects",
        "description": "List initialized projects",
        "inputSchema": {
          "type": "object",
          "properties": {
            "projectDir": { "type": "string" }
          },
          "required": ["projectDir"]
        }
      },
      {
        "name": "restart_app",
        "description": "Restart app",
        "inputSchema": {
          "type": "object",
          "properties": {
            "projectDir": { "type": "string" },
            "serial": {
              "type": "string",
              "description": "Optional. If absent or invalid, fallback to selected device."
            }
          },
          "required": ["projectDir"]
        }
      }
    ]
  }
}
```

### 11.2 `tools/call`：`list_projects` 成功

请求：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "list_projects",
    "arguments": {
      "projectDir": "/Users/me/workspace/demo"
    }
  }
}
```

响应：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "status": "OK",
    "message": "list_projects executed successfully.",
    "data": {
      "projects": [
        {
          "projectDir": "/Users/me/workspace/demo",
          "initialized": true
        }
      ]
    },
    "artifacts": [],
    "errorCode": null
  }
}
```

### 11.3 `tools/call`：`restart_app`（未传 `serial`，回落成功）

请求：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "restart_app",
    "arguments": {
      "projectDir": "/Users/me/workspace/demo"
    }
  }
}
```

响应：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "status": "OK",
    "message": "restart_app executed successfully. Serial not provided; selected device 'emulator-5554' is used.",
    "data": {
      "deviceSerial": "emulator-5554",
      "packageName": "com.example.app",
      "restarted": true
    },
    "artifacts": [],
    "errorCode": null
  }
}
```

### 11.4 `tools/call`：`restart_app`（`serial` 非法，回落成功）

请求：

```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "restart_app",
    "arguments": {
      "projectDir": "/Users/me/workspace/demo",
      "serial": "unknown-device"
    }
  }
}
```

响应：

```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "result": {
    "status": "OK",
    "message": "restart_app executed successfully. Serial 'unknown-device' is invalid; fallback to selected device 'emulator-5554'.",
    "data": {
      "deviceSerial": "emulator-5554",
      "packageName": "com.example.app",
      "restarted": true
    },
    "artifacts": [],
    "errorCode": null
  }
}
```

### 11.5 典型错误：缺失 `projectDir`

请求：

```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "tools/call",
  "params": {
    "name": "restart_app",
    "arguments": {}
  }
}
```

响应：

```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "result": {
    "status": "ERROR",
    "message": "restart_app failed. Reason: projectDir is required.",
    "data": {},
    "artifacts": [],
    "errorCode": "MCP_INVALID_PARAMS"
  }
}
```

### 11.6 典型错误：项目未初始化

请求：

```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "tools/call",
  "params": {
    "name": "restart_app",
    "arguments": {
      "projectDir": "/Users/me/workspace/not_initialized"
    }
  }
}
```

---


响应：

```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "result": {
    "status": "ERROR",
    "message": "restart_app failed. Reason: project is not initialized.",
    "data": {},
    "artifacts": [],
    "errorCode": "MCP_PROJECT_NOT_INITIALIZED"
  }
}
```
