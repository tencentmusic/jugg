# L3 无设备场景

> 本文件用例在执行前，必须先通过 CLI 关闭所有 AVD 并拔掉真机，确保 `adb devices` 返回空列表。
> 执行完毕后再重新启动 AVD 恢复环境。

---

## 前置操作

执行 `adb emu kill`（关闭所有模拟器），确认 `adb devices` 输出为空。

---

## 十、无设备场景 - 全量工具验证

### 不需要设备的工具（应正常返回）

**NODEV-1: 无设备 - list_projects**
调用 `list_projects`，验证返回 `status` 为 `OK`，正常返回项目列表。无设备不影响此工具。

**NODEV-2: 无设备 - device_list**
调用 `device_list`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`data` 中设备列表为空数组。

**NODEV-3: 无设备 - compile_only**
修改一个源码文件，调用 `compile_only`，传入 `projectDir`，验证返回 `status` 为 `OK`。仅编译不需要设备。

**NODEV-4: 无设备 - get_compile_status**
使用 NODEV-3 中如果有返回的 `jobId` 调用 `get_compile_status`，验证正常返回编译状态。如果 NODEV-3 同步完成无 `jobId`，则传入一个假 `jobId`，验证返回合理的错误。

### 需要设备的工具（应返回 MCP_NO_DEVICE 或编译后部署失败）

**NODEV-5: 无设备 - compile_and_deploy**
调用 `compile_and_deploy`，传入有效 `projectDir`，验证工具能正常执行编译阶段。由于无设备，部署阶段会失败，最终返回 `status` 为 `ERROR`，但 `errorCode` 不一定是 `MCP_NO_DEVICE`（可能是 `MCP_INTERNAL_ERROR`，因为编译后部署失败）。关键验证点：工具不应在编译前就因无设备而拒绝执行。

**NODEV-6: 无设备 - force_gradle_compile**
调用 `force_gradle_compile`，传入有效 `projectDir`，验证工具能正常执行 Gradle 编译。由于无设备，部署阶段会失败，最终返回 `status` 为 `ERROR`，但 `errorCode` 不一定是 `MCP_NO_DEVICE`（可能是 `MCP_INTERNAL_ERROR`，因为编译后部署失败）。关键验证点：工具不应在编译前就因无设备而拒绝执行。

**NODEV-7: 无设备 - clean_reinstall_apk**
调用 `clean_reinstall_apk`，传入有效 `projectDir`，验证工具能正常执行编译阶段。由于无设备，重装阶段会失败，最终返回 `status` 为 `ERROR`，但 `errorCode` 不一定是 `MCP_NO_DEVICE`（可能是 `MCP_INTERNAL_ERROR`）。关键验证点：工具不应在编译前就因无设备而拒绝执行。

**NODEV-8: 无设备 - restart_app**
调用 `restart_app`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**NODEV-9: 无设备 - screenshot**
调用 `screenshot`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**NODEV-10: 无设备 - start_record**
调用 `start_record`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**NODEV-11: 无设备 - layout_dump**
调用 `layout_dump`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**NODEV-11a: 无设备 - layout_verify**
调用 `layout_verify`，传入有效 `projectDir` 和 `checks: [{target: {resourceId: "any_id"}, type: "property", property: "exists"}]`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`（自动快照/实时都需设备）。

**NODEV-11b: 无设备 - view_inspect**
调用 `view_inspect`（即 `eval_view`），传入有效 `projectDir`、`target: {resourceId: "any_id"}`、`expressions: ["getText()"]`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**NODEV-12: 无设备 - activity_stack**
调用 `activity_stack`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**NODEV-13: 无设备 - crash_report**
调用 `crash_report`，传入有效 `projectDir`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**NODEV-14: 无设备 - tap（坐标模式）**
调用 `tap`，传入有效 `projectDir`、`x=100`、`y=100`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**NODEV-15: 无设备 - tap（百分比模式）**
调用 `tap`，传入有效 `projectDir`、`xPercent=50`、`yPercent=50`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

**NODEV-16: 无设备 - tap（元素模式）**
调用 `tap`，传入有效 `projectDir`、`text="Login"`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_NO_DEVICE`。

---

## 后置操作

重新启动 AVD（`emulator -avd <avd_name> &`），等待 `adb devices` 显示设备 online 后，继续后续用例。
