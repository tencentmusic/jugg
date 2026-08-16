# Standalone CLI 验证 TODO

## 范围与已验证基线

- 验证工程：`/Users/wormchen/IdeaProjects/joox/JOOX_Android_2`
- 验证设备：已连接的 Pixel 7 虚拟设备。
- standalone Runtime 声明能力为 `version`、`list-projects`、`init`、`compile`、`deploy`、`gradle-build`、`get-compile-status`、`status`。
- 使用本地 profile 时，`init`、`status`、增量 `compile`、`deploy`、`gradle-build` 均已到达服务端成功终态；完整 Gradle 构建的 `get-compile-status` 记录为 `success`。

## 已确认问题

### TODO-1：当前 profile 为 remote 时 standalone init 直接拒绝

复现条件：当前共享 run configuration 的 `isRemoteCompile=true`。

实际结果：`standalone init` 返回 “Standalone Runtime does not support remote compile profiles. Use IDEA or select a local profile.”，无法继续创建或选择可用的 local standalone profile。

预期结果：standalone 不支持远程编译时，应在不改写 remote profile 的前提下选择已有 local profile，或创建 local standalone profile；不应因为当前 profile 是 remote 而阻断初始化。

定位：`StandaloneProjectInitializer.initialize()` 在读取当前 profile 后直接返回失败（`cmd_line/.../StandaloneProjectInitializer.kt:25-26`）；`StandaloneConfigurationRunner` 也在运行前直接拒绝 remote profile（`.../StandaloneConfigurationRunner.kt:109-113`）。同时 `resolveExecutionType()` 固定返回 `local`（`:244`），需要与最终 profile 选择语义一并核对。

## 待复现问题

### TODO-2：deploy 与 gradle-build 的 CLI 终端输出缺失

在本次 Codex 工具调用中，`deploy` 和 `gradle-build` 未返回 CLI JSON；但 standalone 服务端 job 实际继续执行并成功结束。`compile_2026-08-16_17-07-50.0.log` 记录 `onEndBuilding isSuccess: true`，且 `get-compile-status` 返回 `Gradle build finished successfully.`。

需要在普通终端直接运行相同命令，区分 CLI stdout/阻塞问题与 Codex 工具输出采集现象；未复现前不得作为产品缺陷处理。

## 验证前置条件

### TODO-3：JOOX Agent hooks 的端到端验证需要以 JOOX 为 task 工作区

Jugg hooks 已安装，当前 Codex 会话中的 `PreToolUse` 事件也已写入 `jugg-hook-debug.log`。但 hook payload 的 `cwd` 固定为当前 task 工作区 `/Users/wormchen/IdeaProjects/jugg/jugg_f2`，不会因为 shell 命令的工作目录或 `--project-dir` 切换为 JOOX。因此本会话不能真实验证 JOOX 的“源码编辑 → raw Gradle 拦截 → Stop hook”链路。

后续应在以 `/Users/wormchen/IdeaProjects/joox/JOOX_Android_2` 为工作区的 Codex task 中执行该链路；不得通过直接运行 hook 脚本伪造事件。
