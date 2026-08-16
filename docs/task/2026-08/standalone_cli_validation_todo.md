# Standalone CLI 验证 TODO

## 范围与已验证基线

- 验证工程：`/Users/wormchen/IdeaProjects/joox/JOOX_Android_2`
- 补充验证工程：`/Users/wormchen/IdeaProjects/jugg/jugg/android_demo_project`
- 验证设备：Pixel_6 AVD（本次验证后已关闭）。
- standalone Runtime 声明能力为 `version`、`list-projects`、`init`、`compile`、`deploy`、`gradle-build`、`get-compile-status`、`status`。
- 使用本地 profile 时，`init`、`status`、增量 `compile`、`deploy`、`gradle-build` 均已到达服务端成功终态；两个工程完整 Gradle 构建的 `get-compile-status` 均记录为 `success`。

## 已确认问题

### TODO-1：当前 profile 为 remote 时 standalone init 直接拒绝

复现条件：当前共享 run configuration 的 `isRemoteCompile=true`。

实际结果：`standalone init` 返回 “Standalone Runtime does not support remote compile profiles. Use IDEA or select a local profile.”，无法继续创建或选择可用的 local standalone profile。

原预期：standalone 不支持远程编译时，应在不改写 remote profile 的前提下选择已有 local profile，或创建 local standalone profile；不应因为当前 profile 是 remote 而阻断初始化。

定位：`StandaloneProjectInitializer.initialize()` 在读取当前 profile 后直接返回失败（`cmd_line/.../StandaloneProjectInitializer.kt:25-26`）；`StandaloneConfigurationRunner` 也在运行前直接拒绝 remote profile（`.../StandaloneConfigurationRunner.kt:109-113`）。同时 `resolveExecutionType()` 固定返回 `local`（`:244`）。

后续决策：改为直接支持 remote compile，不再通过自动选择 local profile 绕过。实施方案见 [standalone_remote_compile_plan.md](standalone_remote_compile_plan.md)，待下一个会话 review 后落地。

## 已解决问题

### TODO-2：ConstRef 运行期扫描遇到 SQLite 缓存损坏

在 `android_demo_project` 的 standalone `gradle-build` 成功后，`compile_latest.log` 记录 `ConstRefEngine scene FULL_SCAN failed`，原因为 `SQLITE_CORRUPT_INDEX`；随后 `ConstRefCacheCleaner` 记录 `const-ref cache db cleanup failed`。

完整 Gradle 构建与后续 deploy 仍成功，因此主流程被正确隔离；但 FULL_SCAN 未完成，可能降低后续常量引用分析的可靠性。当前清理逻辑仅记录 warning，未见运行期损坏后的重建或显式 no-op 降级。

已修复：运行期命中损坏信号后，关闭连接、删除或移走 DB/WAL/SHM、重建 schema，并仅重试原操作一次；重建或重试失败时保留当前操作降级，主流程继续。

## 跳过范围

### Agent hooks

按用户指示跳过真实端到端 hooks 验证。此前已确认 Jugg hooks 安装且当前 Codex 会话可以触发 `PreToolUse`，但未把该观察当作 JOOX hooks 链路通过证据。

## 已关闭的验证观察

- `deploy` 与 `gradle-build` 在伪终端均输出完整 JSON；此前缺失输出是 Codex 调用采集现象，不是 CLI stdout 问题。
- 用户授权后，`android_demo_project` 已在原工作树成功运行 `gradle-build`；未发现本次验证引入新的 Git 状态条目。
