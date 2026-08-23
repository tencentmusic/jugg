# Marketplace 远程能力知情确认实施记录

> 状态：已实施，待提交
> 批准范围：2026-08-23 用户确认

## 目标

保持 Jugg 默认离线和现有 Custom Server 工作流不变，仅在用户主动触发网络或远程代码能力前补充明确、可见的目标地址与确认。

## 已批准改动

1. 问题报告确认窗口显示实际上传 URL，并说明只会上传到该单一地址。
2. `JuggManager` 将实际调用 `IssueReportUploader` 的 URL 传入确认窗口；不增加 URL 设置、重试 fallback 或新的上传逻辑。
3. Custom Server 输入窗口说明其可能用于远程编译、问题报告、检查 Jugg 更新、下载并加载自定义编译器。
4. 用户输入非空 Custom Server URL 后，显示包含该 URL 和远程代码风险的二次确认；取消时不保存 URL。
5. 增加 `JuggServerChooserTest`，覆盖确认和取消后的持久化结果；报告窗口仅做手工 UI 核验，不增加实现细节测试。
6. 同步 Marketplace 上架准备文档的公开发行口径。

## 明确不做

- 不新增 remote code enable 开关。
- 不改变已保存 Custom Server 的网络、热更新或自定义编译器行为。
- 不新增上传 URL 配置、自动重试或服务器 fallback。
- 不将 MD5 升级为签名校验。
- 不修改 MCP、SSH 凭据、发布 CI 或 Marketplace 后台设置。

## 验证策略

- L1：`JuggServerChooserTest` 通过可控的 `PlatformApi` 实现验证用户确认后保存 URL、取消后保持原设置。
- 替代验证：定向编译与 `:idea:buildPlugin`，并人工检查报告窗口的 URL 与实际传入 URL 相同。

## 实际验证

- 失败证据：实施前，`JuggServerChooserTest` 的确认路径未调用二次确认，取消路径仍会写入新 URL。
- 定向测试：`./gradlew :idea:test --tests com.sickworm.intellij.jugg.server.JuggServerChooserTest --no-daemon`。
- 构建验证：`./gradlew :idea:buildPlugin --no-daemon`。
- 人工代码核验：`JuggManager` 将同一 URL 传入确认窗口、首次上传和重试上传。
