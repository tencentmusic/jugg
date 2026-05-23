# L3 Agent Feedback

目标：验证被测 Agent 的 hooks 是否已正确配置，并且能被 Agent 自己的文件变更、命令和结束会话动作真实触发。L3 不重复覆盖完整文件变更矩阵；完整新增、修改、移动、多文件同轮变更由 L2 验证。本文只保留一条新增源码 command 反馈链路和一条 stop 反馈链路。

## 执行规则

- 在当前 CWD 启动被测 Agent。
- 不读取或调用 `docs/skills/hooks/*.py`、`~/.jugg/skills/hooks/*.py`。
- 不修改 hook 源码，不启动 Android Studio。
- 不修改真实业务代码；只允许按 case 要求新增或修改隔离触发文件，以及写入 prompt pack 同目录 `report.md`。
- 需要触发 Jugg pending changes 的源码触发文件，必须放在 `app/src/main/java/com/example/myapplication/` 下，且只能使用 `Hook*Trigger.kt` 这类隔离文件，不要改现有业务文件。
- 报告中的路径默认使用相对路径；hook 反馈原文中由客户端输出的绝对脚本路径可以原样保留。
- command/stop hook 未触发、看不到对应反馈、或反馈原文无法写入报告时，判定为 `FAIL`，不要判定为 `SKIP`。
- stop hook 必须通过 Agent 结束会话动作触发；不要使用 `jugg stop`，它不是 Jugg CLI 子命令。
- stop hook 反馈不会出现在 shell/terminal/tool output 中；必须通过发送最终回复或结束会话动作触发。如果客户端随后用 followup/新消息把 stop hook 反馈返回给你，你必须继续本轮任务，把反馈原文追加到 `report.md` 后再第二次结束。
- 二次放行反馈按客户端区分：Codex/Claude 应能看到 warning 原文；Cursor/Gemini 允许静默放行，报告记录第二次动作已放行即可。

## HOOKFB-1: 新增源码后的 command hook 可见性

Prompt：请验证 agent hooks 是否会被你的真实文件变更和命令动作触发。必须按以下步骤执行，并把你实际看到的 hook 反馈原文写入报告：

1. 在当前 CWD 执行一次 `jugg gradle-build` 作为 hook 状态基线；记录命令是否执行成功。如果命令失败，继续后续步骤，但在报告中保留失败输出摘要。
2. 新增隔离源码触发文件 `app/src/main/java/com/example/myapplication/HookFeedbackAddTrigger.kt`。文件内容必须使用 `package com.example.myapplication` 并保持可编译。
3. 连续两次执行 raw Gradle 命令：`./gradlew :app:assembleDebug`。第一次被 hook 阻断后，仍然执行第二次以验证二次放行行为。
4. 记录两次 raw Gradle 命令后你实际收到的 hook 反馈原文、退出码和是否被阻断。
5. 再执行一次 `jugg gradle-build`，记录它是否被 raw Gradle hook 误拦截。
6. 将结果写入 prompt pack 同目录 `report.md`，必须包含反馈原文；不要只写总结。

期望：

- 新增隔离 Android 源码文件后，Agent 不应看到 `You modified Android source files.` 软提醒；后续第一次 raw Gradle 被阻断即证明 hook 已记录本会话写入状态。
- 第一次 raw Gradle 尝试被 command hook 阻断，Agent 能看到原文包含 `Do not verify with raw Gradle here`，退出码应体现阻断。
- 第二次 raw Gradle 尝试应被放行。Codex/Claude 应能看到原文包含 `Allowing this repeated command attempt`；Cursor/Gemini 可静默放行，报告写明未收到第二次 warning 但命令已执行即可。
- `jugg gradle-build` 不应被识别为 raw Gradle 拦截目标。
- 如果 hook 没有被 Agent 真实动作触发，本 case 判定为 `FAIL`。

## HOOKFB-2: stop 真实触发与二次放行可见性

Prompt：请验证 stop hook 是否会被你的真实结束会话动作触发。必须按以下步骤执行，并把你实际看到的 stop hook 反馈原文写入报告：

1. 在当前 CWD 执行一次 `jugg gradle-build` 作为 hook 状态基线；记录命令是否执行成功。如果命令失败，继续后续步骤，但在报告中保留失败输出摘要。
2. 对隔离源码触发文件 `app/src/main/java/com/example/myapplication/HookStopTrigger.kt` 做一次真实文件变更。文件内容必须使用 `package com.example.myapplication` 并保持可编译。
3. 不执行 `jugg compile`、`jugg deploy` 或 `jugg gradle-build`。
4. 先把“准备触发第一次 stop”写入 `report.md`，然后尝试结束本次任务并发送最终回复来触发真实 stop hook。不要执行 `jugg stop`。注意：stop hook 反馈不会出现在 shell/terminal/tool output 中，不要因为工具输出里没有 stop 文案就提前判 FAIL。
5. 如果 stop hook 正确配置，第一次结束会话会被客户端阻断，反馈中会列出 Jugg 当前 pending 文件名（最多 10 个）。如果你收到 followup/新消息形式的 stop hook 反馈，说明本 case 仍在继续，不是已经失败；此时只允许把你看到的 stop hook 反馈原文写入 `report.md`，然后立刻再次尝试结束本次任务。不要执行任何命令、文件变更、`jugg compile`、`jugg deploy`、`jugg gradle-build` 或其他验证/修复操作，必须保留 pending changes 来观测第二次 stop 行为。
6. 第二次结束会话的期望按客户端区分：Cursor/Gemini 允许静默放行（无第二条反馈也可判定通过，需在报告中写明未收到第二次 warning）；Codex/Claude 等支持 `systemMessage` 的客户端应记录第二次 stop warning 原文。

期望：

- 首次结束会话应被 stop hook 阻断，Agent 能看到原文包含 `Before stopping, you must enable the jugg-android-dev-loop skill`，并包含 `Modified files: HookStopTrigger.kt`。
- 第二次结束会话应放行。Cursor/Gemini 重复 stop 预期可静默放行，避免反馈循环阻断；Codex/Claude 等支持 `systemMessage` 的客户端应能看到原文包含 `allowing session stop after a repeated stop attempt`。
- 第一次 stop 被阻断后，Agent 不得响应 stop hook 要求去执行验证或清理 pending changes；本 case 必须保留 pending changes 来观测第二次 stop warning。
- 不得使用 `jugg stop`、直接调用 `stop.py` 或脚本模拟 stop hook。
- 如果已经真实尝试结束会话，但客户端没有返回任何 stop hook 反馈或 followup，本 case 判定为 `FAIL`。不得仅凭 shell/terminal/tool output 缺少 stop 文案判定失败。
