# L3 无设备场景

目标：验证 Agent 在无 Android 设备时能区分“仍可执行”“应失败”“应跳过”的 CLI，不把所有问题都归因于 CLI 不可用。

执行条件：MCP 端口可用，但 `devices` 返回空列表。

## NODEV-1: 无设备列表

Prompt：当前没有连接设备，请列出设备并给出结论。

期望：
- 选择 `devices`。
- 空设备列表是有效结果。

## NODEV-2: 状态检查

Prompt：没有设备时查看 Jugg 状态。

期望：
- 选择 `status`。
- 记录 `hasDevice=false` 或等价信息。

## NODEV-3: 仅编译

Prompt：无设备环境下只验证源码能否编译。

期望：
- 选择 `compile`。
- 不因为无设备直接跳过编译。

## NODEV-4: Gradle 编译

Prompt：无设备环境下执行完整 Gradle 编译验证。

期望：
- 选择 `gradle-build`。
- 如果真实输出失败，按编译错误记录；不要预设一定因无设备失败。

## NODEV-5: deploy

Prompt：无设备环境下尝试部署。

期望：
- 选择 `deploy`。
- 编译可能先执行；最终部署阶段可失败。
- 记录失败位置，不把它当成 parser 失败。

## NODEV-6: clean-reinstall

Prompt：无设备环境下清数据重装。

期望：
- 若 prompt 没有明确允许清数据，直接 `SKIP: destructive`。
- 若明确允许，选择 `clean-reinstall` 并记录无设备失败。
- 不使用过期 `reinstall`。

## NODEV-7: restart

Prompt：无设备环境下重启 app。

期望：
- 选择 `restart`。
- 记录无设备或 app 不可用错误。

## NODEV-8: UI 观察类命令

Prompt：无设备环境下导出布局、定位元素、读取属性。

期望：
- `layout-dump`、`view-locate`、`view-inspect` 都应失败或 skip。
- Agent 不能改用截图、录屏或 adb。

## NODEV-9: tap

Prompt：无设备环境下点击屏幕中心。

期望：
- 由于无设备，应记录失败或 `SKIP: no device`。
- 不执行过期 `--xp` / `--yp` 参数。

## NODEV-10: instrument

Prompt：无设备环境下运行一个存在的 androidTest source。

期望：
- 选择 `instrument --source-path <relative androidTest file>`。
- 若测试 APK 可编译但运行阶段失败，应记录无设备/运行失败。
- 不猜 package，不使用 `-e` 或 `--clazz` 等历史 alias。

## NODEV-11: wait-logs

Prompt：无设备环境下等待 `[JUGG_BENCH] MAIN_ACTIVITY_READY` 日志。

期望：
- 选择 `wait-logs --marker ... --timeout-ms ...`。
- 记录无设备或 timeout/crash/marker 结果；不能 hang。
