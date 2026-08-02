# Jugg 开源发行网络隔离与诊断上传实施方案

> 状态：已实现并验证
> 依据：`open_source_network_and_diagnostics_design.md`

## 1. 精简后的目标

本次只解决两个问题：

1. 公开源码和 `buildPlugin` 产物不包含 `servers.json`；没有该文件且用户未设置自定义服务器时，Jugg 不执行任何自有后台请求。
2. 用户明确设置的自定义服务器不依赖 `servers.json`，更新、事件上报等现有后台能力继续正常生效。
3. 每次 report 事件都写入本地 `~/.jugg/action.db`，不受远端服务器是否存在、可达或请求是否成功影响。
4. 保留用户主动提交诊断包的能力，但上传地址、文件范围和用户确认与 Jugg 后台完全解耦。

不再引入 `public/internal` 运行时配置模型，不生成 `distribution.json`，也不重构 `JuggServer` 的其他职责。

## 2. 构建与后台隔离

### 2.1 `servers.json` 管理

- 在 `.gitignore` 加入 `/main/src/main/resources/config/servers.json`。
- 使用 `git rm --cached` 从仓库移除现有文件，不删除开发者本地副本。
- `main/build.gradle` 的默认 `processResources` 显式排除 `config/servers.json`。

仅加入 `.gitignore` 不能阻止 Gradle 打包本地文件，因此必须同时增加资源排除规则。

### 2.2 构建任务

在 `idea/build.gradle` 新增 `buildPluginInternal`：

- 依赖现有 `buildPlugin`，不复制插件构建流程。
- 任务图包含 `buildPluginInternal` 时，允许 `main:processResources` 加入本地 `servers.json`。
- 执行前检查文件存在且 JSON 是非空 server rule 数组，否则失败。
- 直接执行 `buildPlugin` 时，无论本机是否存在 `servers.json`，产物都不包含该文件。
- internal 与 public 产物沿用现有命名；如需同时保留两份，再单独增加文件名后缀，不在首版实现。

建议验证命令：

```text
./gradlew :idea:buildPlugin
./gradlew :idea:buildPluginInternal
```

### 2.3 `JuggServer` 行为

`JuggServer` 初始化时尝试读取 `servers.json`，但后台可用性需要动态判断：

- 文件不存在、内容为空或解析失败，且用户没有设置自定义服务器：不进行选服、DNS/可达性探测、远端事件上报、更新检查、热更新、配置下发和远端机器申请。
- `JuggSettings.serverExpireTimeMill == -1` 且 `serverUrl` 非空：视为用户明确设置的自定义服务器，即使没有 `servers.json` 也保持现有后台能力。
- 缺少 `servers.json` 时，不得把历史自动选中的 `JuggSettings.serverUrl` 当作自定义服务器，避免从 internal 包切换到公开包后继续连接旧服务器。
- 文件有效：保持现有 internal 行为。
- 所有 `JuggServer` 网络入口统一经过动态的 `hasAvailableServer()` 边界，不在调用方散落判断。
- 主动问题诊断上传由独立 uploader 处理，不经过 `JuggServer`，因此用户显式输入 URL 并确认后仍可上传。

## 3. 本地事件数据库

### 3.1 写入语义

`JuggServer.report()` 保持现有调用方式，每个事件在同一个后台协程中执行：

1. 先 Best-effort 写入 `~/.jugg/action.db`。
2. 再判断是否存在可用服务器；存在则执行原远端上报，不存在则结束。
3. 本地写入失败不能阻止远端上报；远端失败也不能回滚或删除本地记录。

本地记录的是 report 被调用时形成的事件，而不是远端请求结果，因此同一事件只写一次，不因远端重试重复插入。

### 3.2 表结构

新增 `jugg_event` 表，结构与后端 `Main.kt#JuggEventTable` 对齐：

```text
id INTEGER PRIMARY KEY AUTOINCREMENT
version TEXT NOT NULL
ide_version TEXT NOT NULL
username TEXT NOT NULL
project_id TEXT NOT NULL
session_id TEXT NOT NULL
action TEXT NOT NULL
is_success INTEGER NOT NULL DEFAULT 1
cost_time INTEGER NOT NULL DEFAULT 0
detail TEXT NULL
```

不引入 Exposed，直接复用项目已有 SQLite JDBC 和 `SqLiteDriverLoader`。新增 `JuggEventLocalStore` 负责建表和插入，通过进程级锁串行化多个项目对全局数据库的写入，并设置有限 `busy_timeout`。首版不增加查询 UI、上传补偿、清理或保留期策略。

## 4. 诊断上传改动范围总结

讨论稿其余内容可以收敛为三个必要改动。

### 4.1 安全诊断包

新增 `IssueReportBundleBuilder`：

- 只生成并打包白名单文件：环境摘要、项目摘要、用户选择的编译日志、logcat、可选 hook 日志。
- 不再上传原始 `project_infos`、`remoteDiffDir`、`tmpGradleProjectInfo` 或其他目录。
- 项目摘要使用安全字段重新生成，不序列化完整 `ModuleInfo`，确保签名密码、路径、placeholder、APT/KAPT 参数不会进入诊断包。
- 文本日志替换项目目录、用户目录、Git 身份和已知密码；脱敏失败则禁止上传。
- 最终 zip 内含 `manifest.json`，打包后重新核对 manifest 与实际 entry，不一致则失败。

### 4.2 独立单目标上传

新增 `IssueReportUploader`：

- 使用完整上传 URL，不复用 `JuggSettings.serverUrl`，不自动拼接 `/report_issue`。
- 默认只允许 HTTPS，拒绝 user-info、fragment 和 query。
- 每次只请求用户确认的一个 URL；失败后保留 zip，不切换服务器。
- 新增 `JuggSettings.reportUploadUrl` 记住用户上次成功使用的地址。

### 4.3 确认窗口

替换当前简单的 `ReportConfirmDialog` / `ReportProgressDialog`：

- 上传前展示最终 URL、实际文件、大小、敏感等级和脱敏状态。
- 用户可以取消 logcat、hook 日志等非必要文件，也可以只保存诊断包而不上传。
- URL 或文件选择变化后重新生成 manifest 和 zip，再次确认后才能上传。
- 上传失败展示本地 zip 路径，并允许对同一 URL 重试。

## 5. 精确文件范围

### 构建与后台隔离

| 文件 | 改动 |
|---|---|
| `.gitignore` | 忽略本地 `servers.json`。 |
| `main/src/main/resources/config/servers.json` | 从 Git 索引移除。 |
| `main/build.gradle` | 默认排除资源；internal 任务启用时校验并加入。 |
| `idea/build.gradle` | 新增 `buildPluginInternal`，依赖 `buildPlugin`。 |
| `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServer.kt` | 动态识别内置/自定义服务器；每次 report 先写本地，再按服务器可用性远端上报。 |
| `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServerChooser.kt` | 缺失资源时安全返回空配置，不再使用 `!!`。 |
| `main/src/main/java/com/sickworm/intellij/jugg/server/JuggEventLocalStore.kt` | 新增 `action.db` 建表、进程内串行插入和局部失败处理。 |
| `main/src/main/java/com/sickworm/intellij/jugg/project/JuggGlobalPathManager.kt` | 新增 `actionDbFile` 路径入口。 |

### 诊断上传

| 文件 | 改动 |
|---|---|
| `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/IssueReportModels.kt` | 新增清单、候选文件和结果模型。 |
| `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/IssueReportBundleBuilder.kt` | 新增白名单、脱敏、manifest 和 zip 校验。 |
| `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/IssueReportUploader.kt` | 新增 URL 校验和单目标上传。 |
| `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServer.kt` | 删除旧 `reportAndUploadLogs()`、递归打包和上传 fallback。 |
| `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt` | 新增独立 `reportUploadUrl`。 |
| `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | 编排诊断包生成、确认、保存和上传。 |
| `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/ReportIssueDialog.kt` | 新增报告确认窗口。 |
| `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/ReportIssueResultDialog.kt` | 新增结果、保存路径和重试窗口。 |
| `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/ReportConfirmDialog.kt` | 删除。 |
| `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/ReportProgressDialog.kt` | 删除。 |

### 测试与文档

- L1 `JuggServer`/构建契约：缺少 `servers.json` 且无自定义地址时不发请求；自定义地址仍正常请求；两种构建产物的资源内容正确。
- L1 `JuggEventLocalStoreTest`：首次建表、字段写入、多事件追加和数据库暂时不可写时局部失败。
- L1 `JuggServer` report owner：无服务器、服务器失败和服务器成功三种情况都各写入一条本地事件；远端发送次数保持原契约。
- L1 `IssueReportBundleBuilderTest`：禁入数据、脱敏、manifest/zip 一致性。
- L1 `IssueReportUploaderTest`：URL 校验、唯一请求目标、失败不 fallback。
- L2 报告流程：选择或 URL 变化后重新确认，失败保留 zip。
- 同步 `05_utilities.md`、`98_code_map.md`、README 和问题报告 Wiki。

## 6. 不在范围内

- 不新增通用发行配置或网络框架。
- 不拆分、重写 internal 后台协议。
- 不处理 Git 历史中的旧地址。
- 不修改用户主动配置的 SSH、Gradle 下载或其他非 Jugg 后台网络。
- 不实现服务端、鉴权、自动上传或后台重试。
- 不提供 `action.db` 查询界面、远端补偿队列、自动清理或数据迁移。

## 7. 实施顺序

1. 先增加构建产物、无 server 网络行为及自定义服务器行为的失败证据。
2. 完成 `.gitignore`、构建任务和 `JuggServer` 动态服务器边界。
3. 先写本地事件数据库失败测试，再接入 report 调用链。
4. 增加安全诊断包及失败测试。
5. 增加单目标 uploader 和确认窗口。
6. 构建 public/internal 两个插件产物，执行内容扫描和网络观察。
7. 同步知识库、Wiki 和 README。

已按以上范围完成实现。Swing 确认窗口保持直接状态，不为普通控件增加实现细节测试；通过 `:idea:compileKotlin` 和真实插件产物验证覆盖接线与构建边界。
