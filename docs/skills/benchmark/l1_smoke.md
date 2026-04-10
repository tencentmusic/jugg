# L1 冒烟用例

> 覆盖五类命令各通一次，验证基本可用性。约 5 条，快速执行。

---

**SMOKE-1: SSH 信息请求**
通过 jugg-android-dev-loop 执行 `jugg ssh-info`，传入 `projectDir`、`reason="smoke test"`、`userConsent=true`。用户在 IDE 弹窗点击同意，验证返回 JSON 中 `status` 为 `OK`，`data` 中包含 SSH 连接信息字段。

**SMOKE-2: 设备列表**
通过 jugg-android-dev-loop 执行 `jugg devices`，传入有效 `projectDir`，验证返回 JSON 中 `status` 为 `OK`，`data` 中包含设备信息列表（含 `selected` 标记）。

**SMOKE-3: 截图**
在有设备连接的情况下，通过 jugg-android-dev-loop 执行 `jugg screenshot`，传入有效 `projectDir`，验证返回 JSON 中 `status` 为 `OK`，`artifacts` 数组中包含一个类型为 `image` 的产物，`path` 字段指向实际存在的图片文件。

**SMOKE-4: 重启应用**
通过 jugg-android-dev-loop 执行 `jugg restart`，仅传入 `projectDir`，验证返回 JSON 中 `status` 为 `OK`，`message` 包含 "restart_app executed successfully"。

**SMOKE-5: 仅编译**
修改项目中一个 Kotlin/Java 文件（如加一行注释），通过 jugg-android-dev-loop 执行 `jugg compile`，传入 `projectDir`，验证返回 JSON 中 `status` 为 `OK`，编译成功且不部署到设备。
