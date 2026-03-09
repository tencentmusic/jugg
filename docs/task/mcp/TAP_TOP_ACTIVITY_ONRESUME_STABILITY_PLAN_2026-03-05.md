# Tap topActivity onResume 稳定性落地（2026-03-05）

## 背景
- `tap` 仅依赖 App ready 检查，在 Activity 切换到布局可交互前可能触发点击失败。

## 目标
- 在 `tap` 真正执行前，增加 `topActivity` 稳定性门禁。
- 判定规则：连续 2 次检查均满足“同一 `topActivity` 且 state 为 `onResume/RESUMED`”，两次检查间隔固定 1 秒。
- 稳定性检查最多等待 5 秒，超时后不阻断 `tap`，仅在最终失败时追加不稳定提示。

## 方案
1. 在 `TapMcpToolAction` 内部增加 `dumpsys activity activities` 采样逻辑。
2. 解析优先级：`topResumedActivity` -> `mResumedActivity` -> `mFocusedActivity`。
3. 如果 state 字段缺失且命中 `topResumedActivity/mResumedActivity`，按 `onResume` 处理。
4. 未达到稳定条件时继续执行 `tap`，若最终失败则在 `message` 与 `data` 附带 `topActivity/topActivityState/checks`。

## 测试
- 新增通过用例：稳定两次后执行点击。
- 新增失败用例：状态不稳定时拒绝点击。
- 运行：`./gradlew :main:test --tests com.sickworm.intellij.jugg.mcp.actions.TapMcpToolActionTest`
