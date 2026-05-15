# L2 Agent Hooks

目标：验证 Jugg agent hooks 是否已正确配置，并能被被测 Agent 的真实编辑和命令动作触发。stop hook 需要结束会话，会放在 L3 的最后一个 case 中验证。

## 覆盖点

| Case | 期望 |
|------|------|
| HOOK-1 | 使用 Agent 文件编辑能力修改隔离 Android 源码文件后，edit hook 静默记录本会话写入状态 |
| HOOK-2 | 本会话写入后仍有 Jugg pending changes 时直接调用 raw Gradle，command hook 第一次硬阻断，第二次放行并给 warning |
| HOOK-3 | `jugg gradle-build` 不被 raw Gradle hook 误拦截 |
| HOOK-4 | 修改不在 Android sourceset 内的隔离文件后，raw Gradle 不应被 command hook 阻断 |

## 执行规则

- 在当前 CWD 启动被测 Agent。
- 不读取或调用 `docs/skills/hooks/*.py`、`~/.jugg/skills/hooks/*.py`。
- 不修改 hook 源码，不启动 Android Studio。
- 不修改真实业务代码；只允许修改本文件指定的隔离触发文件和 prompt pack 同目录 `report.md`。
- 需要触发 Jugg pending changes 的源码触发文件，必须放在 `app/src/main/java/com/example/myapplication/` 下，且只能使用新增/修改 `Hook*Trigger.kt` 这类隔离文件，不要改现有业务文件。
- 报告中的路径默认使用相对路径；hook 反馈原文中由客户端输出的绝对脚本路径可以原样保留。
- 必须通过 Agent 自己的文件编辑和命令执行动作触发 hooks。
- 对预期阻断的 case，command hook 未触发、看不到 command hook 反馈、或反馈原文无法写入报告时，判定为 `FAIL`，不要判定为 `SKIP`。
- 对预期不阻断的 case，报告必须记录没有收到阻断/警告反馈；这类静默放行不视为缺少反馈。

## HOOKS-SMOKE: hooks 真实触发冒烟验证

Prompt：请验证 Jugg agent hooks 是否已正确配置并能被你的真实 Agent 动作触发。不要读取或调用 hook 脚本，不要启动 Android Studio，不要修改真实业务代码。按下面步骤执行，并把你实际看到的 hook 反馈原文写入报告：

1. 在当前 CWD 执行一次 `jugg gradle-build` 作为 hook 状态基线；记录命令是否执行成功。如果命令失败，继续后续步骤，但在报告中保留失败输出摘要。
2. 使用你的文件编辑能力创建或修改隔离源码触发文件 `app/src/main/java/com/example/myapplication/HookSmokeTrigger.kt`。文件内容必须使用 `package com.example.myapplication` 并保持可编译。不要用 shell 脚本、`python`、`sed`、`cat > file` 或直接调用 hook 脚本代替。
3. 使用命令执行能力连续两次执行 raw Gradle 命令：`./gradlew :app:assembleDebug`。第一次被 hook 阻断后，仍然执行第二次以验证二次放行反馈。
4. 记录两次 raw Gradle 命令后你实际收到的 command hook 反馈原文、退出码和是否被阻断。
5. 再执行一次 `jugg gradle-build`，记录它是否被 raw Gradle hook 误拦截。
6. 将结果写入 prompt pack 同目录 `report.md`，必须包含反馈原文；不要只写总结。

## HOOKS-NONSOURCE: 非 sourceset 文件不应触发 raw Gradle 阻断

Prompt：请验证修改不在 Android sourceset 内的隔离文件时，command hook 不会误阻断 raw Gradle。不要读取或调用 hook 脚本，不要启动 Android Studio，不要修改真实业务代码。按下面步骤执行，并把你实际看到的结果写入报告：

1. 在当前 CWD 执行一次 `jugg gradle-build` 作为 hook 状态基线；记录命令是否执行成功。如果命令失败，继续后续步骤，但在报告中保留失败输出摘要。
2. 使用你的文件编辑能力创建或修改非 sourceset 隔离触发文件 `hook_benchmark_scratch/app/src/main/java/com/example/myapplication/HookNonSourceTrigger.kt`。不要用 shell 脚本、`python`、`sed`、`cat > file` 或直接调用 hook 脚本代替。
3. 使用命令执行能力连续两次执行 raw Gradle 命令：`./gradlew :app:assembleDebug`。
4. 记录两次 raw Gradle 命令的退出码、是否被 command hook 阻断，以及你实际收到的反馈原文。如果没有收到 hook 阻断或 warning，也必须明确写入报告。
5. 将结果写入 prompt pack 同目录 `report.md`，不要只写总结。

## 判定标准

- `HOOK-1` PASS：编辑 `app/src/main/java/com/example/myapplication/` 下隔离 Android 源码文件后没有收到 `You modified Android source files.` 软提醒，且后续第一次 raw Gradle 被阻断，说明 edit hook 已记录本会话写入状态。
- `HOOK-2` PASS：第一次 raw Gradle 反馈原文包含 `Do not verify with raw Gradle here`，第二次 raw Gradle 反馈原文包含 `Allowing this repeated command attempt`。
- `HOOK-3` PASS：`jugg gradle-build` 未被 raw Gradle hook 阻断。
- `HOOK-4` PASS：修改 `hook_benchmark_scratch/` 下非 sourceset 文件后，两次 raw Gradle 均未被 command hook 阻断；报告明确记录未收到阻断/ warning 反馈。
- 任一 hook 没有被真实 Agent 动作触发，或预期阻断 case 报告缺少 command hook 反馈原文，对应项为 `FAIL`。
