# L2 Unit: SSH / 连通性 / 设备

> 覆盖 `request_remote_ssh_info`、`list_projects`、`device_list` 三个不依赖设备的工具。
> **必须最先执行**，因为 SSH 用例需要用户在场响应 IDE 弹窗。

---

## 一、远程 SSH 信息（最先执行，需要用户交互）

> 本章必须在用户在场时执行。`request_remote_ssh_info` 会触发 IDE 弹窗二次确认，需要用户点击。
> 两条路径（同意 / 不同意）都要测试。建议先测"用户同意"，再测"用户不同意"。

**SSH-1: 请求 SSH 信息 - 用户同意**
调用 `request_remote_ssh_info`，传入 `projectDir`、`reason="testing mcp ssh tool"`、`userConsent=true`。IDE 侧会弹出二次确认弹窗，**用户点击"同意/确认"**。验证返回 `status` 为 `OK`，`data` 中包含 SSH 连接信息（如 host、port、username 等字段）。

**SSH-2: 请求 SSH 信息 - 用户拒绝**
再次调用 `request_remote_ssh_info`，传入 `projectDir`、`reason="testing mcp ssh tool rejection"`、`userConsent=true`。IDE 侧弹出二次确认弹窗，**用户点击"拒绝/取消"**。验证返回 `status` 为 `ERROR`，不返回任何 SSH 连接信息。

**SSH-3: 请求 SSH 信息 - userConsent=false**
调用 `request_remote_ssh_info`，传入 `projectDir`、`reason="test"`、`userConsent=false`。验证不弹出 IDE 弹窗，直接返回 `status` 为 `ERROR`，不返回 SSH 信息（agent 未获得用户授权即直接拒绝）。

**SSH-4: 请求 SSH 信息 - 缺少 reason**
调用 `request_remote_ssh_info`，不传 `reason` 参数，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_INVALID_PARAMS`。

> 以上交互完成后，用户可以离开电脑，后续用例全部由 agent 自动执行。

---

## 二、基础连通性

**CONN-1: 获取项目列表**
调用 `list_projects`，验证返回值中 `status` 为 `OK`，`data.projects` 是一个数组，数组中每个元素包含 `projectDir`（字符串）和 `initialized`（布尔值）字段。

**CONN-2: list_projects 无参数**
`list_projects` 不需要任何参数（无 `projectDir` 要求），直接调用应当成功返回，不应报 `MCP_INVALID_PARAMS`。

---

## 三、设备相关工具

**DEV-1: 获取设备列表**
调用 `device_list`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`data` 中包含设备信息列表，且有 `selected` 标记标识当前选中的设备。

**DEV-2: device_list - 项目未初始化**
调用 `device_list`，传入一个不存在/未初始化的 `projectDir`（如 `/tmp/not_a_project`），验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_PROJECT_NOT_INITIALIZED`。
