# L1 冒烟用例

> 覆盖五类工具各通一次，验证基本可用性。约 5 条，快速执行。

---

**SMOKE-1: SSH 信息请求**
调用 `ssh-info`，传入 `projectDir`、`reason="smoke test"`、`userConsent=true`。用户在 IDE 弹窗点击同意，验证返回 `status` 为 `OK`，`data` 中包含 SSH 连接信息字段。

**SMOKE-2: 设备列表**
调用 `devices`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`data` 中包含设备信息列表（含 `selected` 标记）。

**SMOKE-3: 截图**
在有设备连接的情况下，调用 `screenshot`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`artifacts` 数组中包含一个类型为 `screenshot` 的产物，`path` 字段指向实际存在的图片文件。

**SMOKE-4: 重启应用**
调用 `restart`，仅传入 `projectDir`，验证返回 `status` 为 `OK`，`message` 包含 "restart_app executed successfully"。

**SMOKE-5: 仅编译**
修改项目中一个 Kotlin/Java 文件（如加一行注释），调用 `compile`，传入 `projectDir`，验证返回 `status` 为 `OK`，编译成功且不部署到设备。
