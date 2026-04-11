# L2 SSH / 连通性 / 设备（~7 条）

> SSH 用例需用户在场响应 IDE 弹窗，建议优先执行。

---
### SSH-1: 请求 SSH 信息 - 用户同意

执行 `ssh-info --reason "testing ssh tool"`，等待用户点击同意。验证 `status=OK`，输出含 host/port/username。

---

### SSH-2: 请求 SSH 信息 - 用户拒绝

同上，等待用户点击拒绝。验证 `status=ERROR`，不返回 SSH 信息。

---

### SSH-3: 请求 SSH 信息 - 缺少 reason

执行 `ssh-info`（不传 `--reason`），验证 `status=ERROR`。

---

### DEV-1: 获取设备列表

执行 `devices`，验证 `status=OK`，输出含设备列表，有 `selected` 标记。

---

### DEV-2: devices - 在非项目目录执行

在不存在的目录（如 `/tmp/not_a_project`）执行 `cd /tmp/not_a_project && jugg-py devices`，验证 `status=ERROR`。

---

### DEV-3: devices - 多设备场景

连接多台设备后执行 `devices`，验证输出含多条设备记录，其中一台标记 `selected`。

---

### DEV-4: devices - 无设备

断开所有设备后执行 `devices`，验证 `status=OK`，设备列表为空。
