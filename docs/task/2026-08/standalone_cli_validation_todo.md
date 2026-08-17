# Standalone CLI 验证 TODO

## 范围与已验证基线

- 验证工程：`/Users/wormchen/IdeaProjects/joox/JOOX_Android_2`
- 补充验证工程：`/Users/wormchen/IdeaProjects/jugg/jugg/android_demo_project`
- 验证设备：Pixel_6 AVD（本次验证后已关闭）。
- standalone Runtime 声明能力为 `version`、`list-projects`、`init`、`compile`、`deploy`、`gradle-build`、`get-compile-status`、`status`。
- 使用本地 profile 时，`init`、`status`、增量 `compile`、`deploy`、`gradle-build` 均已到达服务端成功终态；两个工程完整 Gradle 构建的 `get-compile-status` 均记录为 `success`。

## 已解决问题

### TODO-1：当前 profile 为 remote 时 standalone init 直接拒绝

已修复：`standalone init` 幂等接受已选中的 remote profile，`compile` / `deploy` / `gradle-build` 会按该配置选择 remote Gradle client。remote 仅表示 Gradle full build/fallback 在远程执行；增量编译、设备操作和 project info dry-run 仍在 standalone 所在本机执行。

认证失败在 standalone 中不弹窗，而是返回 failed 终态并提示预先配置 SSH 凭据或完成 iFT 认证；失败后编译客户端、终端输出与取消监听器会清理。远程环境日志只展示 Java/Android/Gradle 路径白名单值，`PATH` 只标记已配置，其他变量值隐藏；SSH 会话在发送原始远程命令前必须成功关闭 PTY echo，否则直接失败。

验证：L2 定向测试已覆盖 remote profile 选择、非交互认证失败、失败资源清理、PTY echo 关闭握手与环境日志脱敏。L3 真实 SSH/iFT 流程需注入 `JUGG_REMOTE_CONFIG_FILE` 和 `JUGG_STANDALONE_REMOTE_PROJECT_DIR`；未注入时 JUnit 明确 skip，注入后最长等待 30 分钟并在结束时恢复原 profile，供手动环境验证。实施约束见 [standalone_remote_compile_plan.md](standalone_remote_compile_plan.md)。

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
