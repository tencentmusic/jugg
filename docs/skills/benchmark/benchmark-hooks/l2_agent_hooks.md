# L2 Agent Hooks

目标：验证 Jugg agent hooks 是否已正确配置，并能被被测 Agent 的真实文件变更和命令动作触发。stop hook 需要结束会话，会放在 L3 的最后一个 case 中验证。

## 覆盖点

| Case | 期望 |
|------|------|
| HOOK-1 | Agent 对隔离 Android 源码文件做真实变更后，edit hook 静默记录本会话写入状态 |
| HOOK-2 | 本会话写入后仍有 Jugg pending changes 时直接调用 raw Gradle，command hook 第一次硬阻断，第二次放行；Codex/Claude 需可见 warning，Cursor/Gemini 允许静默放行 |
| HOOK-3 | `jugg gradle-build` 不被 raw Gradle hook 误拦截 |
| HOOK-4 | 修改不在 Android sourceset 内的隔离文件后，raw Gradle 不应被 command hook 阻断 |
| HOOK-5 | 新增 Android sourceset 内的隔离文件后，raw Gradle 应被 command hook 阻断 |
| HOOK-6 | 修改 Android sourceset 内的隔离文件后，raw Gradle 应被 command hook 阻断 |
| HOOK-7 | 移动或重命名 Android sourceset 内的隔离文件后，raw Gradle 应被 command hook 阻断 |
| HOOK-8 | 同一轮变更多个 Android sourceset 隔离文件后，raw Gradle 应被 command hook 阻断 |

## 执行规则

- 在当前 CWD 启动被测 Agent。
- 不读取或调用 `docs/skills/hooks/*.py`、`~/.jugg/skills/hooks/*.py`。
- 不修改 hook 源码，不启动 Android Studio。
- 不修改真实业务代码；只允许按 case 要求新增、移动或修改隔离触发文件，以及写入 prompt pack 同目录 `report.md`。
- 需要触发 Jugg pending changes 的源码触发文件，必须放在 `app/src/main/java/com/example/myapplication/` 下，且只能使用 `Hook*Trigger.kt` 这类隔离文件，不要改现有业务文件。
- 报告中的路径默认使用相对路径；hook 反馈原文中由客户端输出的绝对脚本路径可以原样保留。
- 必须通过 Agent 自己的真实文件变更和命令运行触发 hooks。
- 对预期阻断的 case，command hook 未触发、看不到 command hook 反馈、或反馈原文无法写入报告时，判定为 `FAIL`，不要判定为 `SKIP`。
- 对预期不阻断的 case，报告必须记录没有收到阻断/警告反馈；这类静默放行不视为缺少反馈。
- 二次放行反馈按客户端区分：Codex/Claude 应能看到 warning 原文；Cursor/Gemini 允许静默放行，报告记录第二次命令被放行即可。

## HOOKS-SMOKE: hooks 真实触发冒烟验证

Prompt：请验证 Jugg agent hooks 是否已正确配置并能被你的真实 Agent 动作触发。不要读取或调用 hook 脚本，不要启动 Android Studio，不要修改真实业务代码。按下面步骤执行，并把你实际看到的 hook 反馈原文写入报告：

1. 在当前 CWD 执行一次 `jugg gradle-build` 作为 hook 状态基线；记录命令是否执行成功。如果命令失败，继续后续步骤，但在报告中保留失败输出摘要。
2. 对隔离源码触发文件 `app/src/main/java/com/example/myapplication/HookSmokeTrigger.kt` 做一次真实文件变更。文件内容必须使用 `package com.example.myapplication` 并保持可编译。
3. 连续两次运行 raw Gradle 命令：`./gradlew :app:assembleDebug`。第一次被 hook 阻断后，仍然执行第二次以验证二次放行行为。
4. 记录两次 raw Gradle 命令后你实际收到的 command hook 反馈原文、退出码和是否被阻断。
5. 再执行一次 `jugg gradle-build`，记录它是否被 raw Gradle hook 误拦截。
6. 将结果写入 prompt pack 同目录 `report.md`，必须包含反馈原文；不要只写总结。

## HOOKS-SOURCE-ADD: 新增 sourceset 文件应触发 raw Gradle 阻断

Prompt：请验证新增 Android sourceset 内的隔离文件时，command hook 会阻断 raw Gradle。不要读取或调用 hook 脚本，不要启动 Android Studio，不要修改真实业务代码。按下面步骤执行，并把你实际看到的 hook 反馈原文写入报告：

1. 在当前 CWD 执行一次 `jugg gradle-build` 作为 hook 状态基线；记录命令是否执行成功。如果命令失败，继续后续步骤，但在报告中保留失败输出摘要。
2. 新增隔离源码触发文件 `app/src/main/java/com/example/myapplication/HookAddTrigger.kt`。文件内容必须使用 `package com.example.myapplication` 并保持可编译。
3. 执行一次 raw Gradle 命令：`./gradlew :app:assembleDebug`。
4. 记录 raw Gradle 命令后你实际收到的 command hook 反馈原文、退出码和是否被阻断。
5. 将结果写入 prompt pack 同目录 `report.md`，必须包含反馈原文；不要只写总结。

## HOOKS-SOURCE-MODIFY: 修改 sourceset 文件应触发 raw Gradle 阻断

Prompt：请验证修改 Android sourceset 内的隔离文件时，command hook 会阻断 raw Gradle。不要读取或调用 hook 脚本，不要启动 Android Studio，不要修改真实业务代码。按下面步骤执行，并把你实际看到的 hook 反馈原文写入报告：

1. 确保隔离源码文件 `app/src/main/java/com/example/myapplication/HookModifyTrigger.kt` 已存在且可编译；如果需要先准备该文件，准备后执行一次 `jugg gradle-build` 重新建立基线。
2. 在当前 CWD 执行一次 `jugg gradle-build` 作为本 case 的 hook 状态基线；记录命令是否执行成功。如果命令失败，继续后续步骤，但在报告中保留失败输出摘要。
3. 修改 `HookModifyTrigger.kt` 中的常量值、函数返回值或注释，保持文件可编译。
4. 执行一次 raw Gradle 命令：`./gradlew :app:assembleDebug`。
5. 记录 raw Gradle 命令后你实际收到的 command hook 反馈原文、退出码和是否被阻断。
6. 将结果写入 prompt pack 同目录 `report.md`，必须包含反馈原文；不要只写总结。

## HOOKS-SOURCE-MOVE: 移动 sourceset 文件应触发 raw Gradle 阻断

Prompt：请验证移动或重命名 Android sourceset 内的隔离文件时，command hook 会阻断 raw Gradle。不要读取或调用 hook 脚本，不要启动 Android Studio，不要修改真实业务代码。按下面步骤执行，并把你实际看到的 hook 反馈原文写入报告：

1. 确保隔离源码文件 `app/src/main/java/com/example/myapplication/HookMoveTrigger.kt` 已存在且可编译；如果需要先准备该文件，准备后执行一次 `jugg gradle-build` 重新建立基线。
2. 在当前 CWD 执行一次 `jugg gradle-build` 作为本 case 的 hook 状态基线；记录命令是否执行成功。如果命令失败，继续后续步骤，但在报告中保留失败输出摘要。
3. 将 `HookMoveTrigger.kt` 移动或重命名为 `app/src/main/java/com/example/myapplication/HookMoveRenamedTrigger.kt`，并保持目标文件可编译。
4. 执行一次 raw Gradle 命令：`./gradlew :app:assembleDebug`。
5. 记录 raw Gradle 命令后你实际收到的 command hook 反馈原文、退出码和是否被阻断。
6. 将结果写入 prompt pack 同目录 `report.md`，必须包含反馈原文；不要只写总结。

## HOOKS-SOURCE-MULTI: 多文件同轮变更应触发 raw Gradle 阻断

Prompt：请验证同一轮变更多个 Android sourceset 隔离文件时，command hook 会阻断 raw Gradle。不要读取或调用 hook 脚本，不要启动 Android Studio，不要修改真实业务代码。按下面步骤执行，并把你实际看到的 hook 反馈原文写入报告：

1. 确保以下隔离源码文件已存在且可编译：`HookMultiModifyTrigger.kt`、`HookMultiMoveTrigger.kt`。如果需要先准备这些文件，准备后执行一次 `jugg gradle-build` 重新建立基线。
2. 在当前 CWD 执行一次 `jugg gradle-build` 作为本 case 的 hook 状态基线；记录命令是否执行成功。如果命令失败，继续后续步骤，但在报告中保留失败输出摘要。
3. 在同一轮文件变更中完成以下三项操作：新增 `HookMultiAddTrigger.kt`、修改 `HookMultiModifyTrigger.kt`、将 `HookMultiMoveTrigger.kt` 移动或重命名为 `HookMultiMoveRenamedTrigger.kt`。所有文件都必须位于 `app/src/main/java/com/example/myapplication/` 并保持剩余源码可编译。
4. 执行一次 raw Gradle 命令：`./gradlew :app:assembleDebug`。
5. 记录 raw Gradle 命令后你实际收到的 command hook 反馈原文、退出码和是否被阻断，并列出本轮变更涉及的相对路径。
6. 将结果写入 prompt pack 同目录 `report.md`，必须包含反馈原文；不要只写总结。

## HOOKS-NONSOURCE: 非 sourceset 文件不应触发 raw Gradle 阻断

Prompt：请验证修改不在 Android sourceset 内的隔离文件时，command hook 不会误阻断 raw Gradle。不要读取或调用 hook 脚本，不要启动 Android Studio，不要修改真实业务代码。按下面步骤执行，并把你实际看到的结果写入报告：

1. 在当前 CWD 执行一次 `jugg gradle-build` 作为 hook 状态基线；记录命令是否执行成功。如果命令失败，继续后续步骤，但在报告中保留失败输出摘要。
2. 对非 sourceset 隔离触发文件 `hook_benchmark_scratch/app/src/main/java/com/example/myapplication/HookNonSourceTrigger.kt` 做一次真实文件变更。
3. 连续两次执行 raw Gradle 命令：`./gradlew :app:assembleDebug`。
4. 记录两次 raw Gradle 命令的退出码、是否被 command hook 阻断，以及你实际收到的反馈原文。如果没有收到 hook 阻断或 warning，也必须明确写入报告。
5. 将结果写入 prompt pack 同目录 `report.md`，不要只写总结。

## 判定标准

- `HOOK-1` PASS：对 `app/src/main/java/com/example/myapplication/` 下隔离 Android 源码文件做真实变更后没有收到 `You modified Android source files.` 软提醒，且后续第一次 raw Gradle 被阻断，说明 edit hook 已记录本会话写入状态。
- `HOOK-2` PASS：第一次 raw Gradle 反馈原文包含 `instead of verifying with raw Gradle here`；第二次 raw Gradle 被放行。Codex/Claude 需记录 `Allowing this repeated command attempt` warning 原文；Cursor/Gemini 可静默放行，报告写明未收到第二次 warning 但命令已执行即可。
- `HOOK-3` PASS：`jugg gradle-build` 未被 raw Gradle hook 阻断。
- `HOOK-4` PASS：修改 `hook_benchmark_scratch/` 下非 sourceset 文件后，两次 raw Gradle 均未被 command hook 阻断；报告明确记录未收到阻断/ warning 反馈。
- `HOOK-5` PASS：新增 `HookAddTrigger.kt` 后，raw Gradle 反馈原文包含 `instead of verifying with raw Gradle here`，并体现阻断。
- `HOOK-6` PASS：修改 `HookModifyTrigger.kt` 后，raw Gradle 反馈原文包含 `instead of verifying with raw Gradle here`，并体现阻断。
- `HOOK-7` PASS：移动或重命名 `HookMoveTrigger.kt` 后，raw Gradle 反馈原文包含 `instead of verifying with raw Gradle here`，并体现阻断。
- `HOOK-8` PASS：同一轮变更多个 Android sourceset 隔离文件后，raw Gradle 反馈原文包含 `instead of verifying with raw Gradle here`，并体现阻断。
- 任一 hook 没有被真实 Agent 动作触发，或预期阻断 case 报告缺少 command hook 反馈原文，对应项为 `FAIL`。
