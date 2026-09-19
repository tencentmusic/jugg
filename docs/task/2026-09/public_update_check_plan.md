# 无后台服务器时公网检查更新方案（Marketplace & GitHub）

> 创建日期：2026-09-19  
> 状态：讨论通过，待实施  
> 决策：版本检测下沉 main 层、双渠道 5s 并发超时、Marketplace 优先、平台调用统一反射。

---

## 1. 背景与现状

在没有配置自建 Jugg 后台服务器（或服务器不可用）时，用户点击 IDE 菜单的 `Check updates`（`CheckJuggUpdateAction`）会弹出 `CheckUpdatesProgressDialog`，当前仅提示：
```text
Jugg backend server is unavailable. Configure a Custom Server in Jugg Settings.
```
用户无法获知公开版本更新，也无法在 IDE 内完成升级。

Jugg 已在 **JetBrains Marketplace**（ID `34099`，xmlId `com.sickworm.jugg`）和 **GitHub Releases** 开源发布。在无自建后台时，需要自动从公开渠道拉取最新版本信息。

---

## 2. 核心原则与要求

1. **平台解耦与下沉**：
   - 版本检测与渠道 HTTP 查询逻辑与 IDE 平台解耦，统一放在 `main` 核心层，由独立的类承载。
2. **双渠道并发与 5s 超时**：
   - 同时并发查询 JetBrains Marketplace 与 GitHub Releases。
   - 整体/单次请求设置 5 秒超时保护，避免网络阻塞。
3. **渠道优先级与明确标识**：
   - 若 Marketplace 检测到高于当前版本的更新，优先展示 Marketplace 渠道。
   - 若 Marketplace 无新版本但 GitHub 有，展示 GitHub 渠道。
   - 弹窗中必须明确标明检测到的更新来源渠道（如 `(from JetBrains Marketplace)` / `(from GitHub Releases)`）。
4. **统一反射调用**：
   - Marketplace 及平台高层 API（如 `PluginUpdateInstaller` / `UpdateInstaller` / `PluginDownloader` 等）以及 `PluginInstaller` 的安装调用**统一使用反射**，禁止出现任何编译期静态引用，防止 JetBrains Plugin Verifier 误报兼容性问题。

---

## 3. 详细设计

### 3.1 核心数据结构（`main` 层）

在 `main/src/main/java/com/sickworm/intellij/jugg/server/` 下新建独立文件：

```kotlin
package com.sickworm.intellij.jugg.server

enum class UpdateChannel(val displayName: String) {
    MARKETPLACE("JetBrains Marketplace"),
    GITHUB("GitHub Releases"),
}

data class PublicUpdateInfo(
    val channel: UpdateChannel,
    val targetVersion: String,
    val downloadUrl: String?,
    val releaseNotes: String?,
    val updateId: Long? = null,
)

data class PublicCheckResult(
    val updateInfo: PublicUpdateInfo?,
    val latestCheckedVersion: String?,
    val isAlreadyLatest: Boolean,
    val failedReason: String? = null,
)
```

### 3.2 版本检测引擎：`PublicUpdateChecker`（`main` 层）

独立类：`com.sickworm.intellij.jugg.server.PublicUpdateChecker`
- 纯平台无关代码，仅依赖 OkHttp、Gson、JuggLogger 和现有 `PluginVersionComparator`。
- **并发查询流程**：
  1. 使用 Kotlin 协程并发启动两个 `async` 任务：
     - `queryMarketplace(currentVersion)`
     - `queryGithub(currentVersion)`
  2. 使用 `withTimeoutOrNull(5000L)` 限制最大等待时间为 5 秒。
  3. **Marketplace 查询细节**：
     - 请求 `https://plugins.jetbrains.com/api/plugins/34099/updates`
     - 解析返回的 JSON 数组，取第一条（最新）记录。
     - 获取 `version`（去除可能带有的 `-release` 后缀以便比对），`notes`，`file`，`id`。
     - 下载链接：`https://plugins.jetbrains.com/files/$file` 或官方 download 接口。
  4. **GitHub 查询细节**：
     - 请求 `https://github.com/tencentmusic/jugg/releases/latest`。
     - 捕获 302 重定向头 `Location` 或最终落地 URL，提取 tag（如 `/tag/3.5.1` 或 `v3.5.1`）。
     - 若能获取 tag，下载链接为 `https://github.com/tencentmusic/jugg/releases/download/$tag/jugg-$tag.zip`。
  5. **仲裁结果**：
     - 若 Marketplace 存在且版本比当前已安装版本高（`PluginVersionComparator.compare > 0`）：选用 Marketplace。
     - 否则，若 GitHub 存在且版本比当前已安装版本高：选用 GitHub。
     - 否则，若两者都返回了且版本 <= 当前版本：判定为 `isAlreadyLatest = true`。
     - 若两者在 5 秒内均无有效响应（或异常）：返回 `failedReason`。

### 3.3 平台安装执行器：`PublicUpdateInstaller`（`idea` 层）

在 `idea/src/main/java/com/sickworm/intellij/jugg/server/` 新增独立类：
`PublicUpdateInstaller`：
- **全程使用反射（Reflection Only）**：
  1. 优先探测平台高层更新 API（如 `com.intellij.openapi.updateSettings.impl.UpdateInstaller` 或 `com.intellij.ide.plugins.marketplace` 相关更新触发入口）。
  2. 若高层 API 不可用或抛出反射异常，安全降级为：
     - 从 `downloadUrl` 下载完整插件 zip 包到本地临时缓存。
     - 反射调用 `com.intellij.ide.plugins.PluginInstaller.installAfterRestart(...)`。
  3. 执行成功后，通过回调通知 UI 弹窗刷新状态为“需要重启 IDE”。

### 3.4 交互改造：`CheckUpdatesProgressDialog`（`idea` 层）

调整 `CheckUpdatesProgressDialog` 以支持公开渠道状态呈现：
1. **存在新版本**：
   - 展示：`<html>New version available: <b>${info.targetVersion}</b> (from ${info.channel.displayName}). Confirm update?</html>`
   - 按钮：`[Update]`、`[Cancel]`。
2. **已是最新版本**：
   - 展示：`"Jugg is already the latest version (${latestVersion})."`
   - 按钮：`[Close]`。
3. **查询失败且无后台**：
   - 展示：`"Jugg backend server is unavailable, and failed to fetch updates from Marketplace and GitHub."`
   - 按钮：`[Close]`，下方保留 Marketplace / GitHub 直达网页超链接。

---

## 4. 验证策略

1. **单元测试（L1/L2）**：
   - 为 `PublicUpdateChecker` 编写定向单元测试：
     - Marketplace 成功且有新版本；
     - Marketplace 无更新但 GitHub 有新版本；
     - 5s 超时容错处理；
     - 版本比较逻辑。
   - 为 `CheckUpdatesProgressDialog` 编写关于新渠道文案及状态展示的测试。
2. **构建与兼容性验证**：
   - `./gradlew :main:test --tests "com.sickworm.intellij.jugg.server.PublicUpdateCheckerTest"`
   - `./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.ui.CheckUpdatesProgressDialogTest"`
   - `./gradlew :idea:compileKotlin`，确保无静态引用违规。
