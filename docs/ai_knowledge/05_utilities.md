# 公共工具模块（Utilities）

> 最后核对：2026-07-24
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只记录 `main` 中被编译、部署、MCP、远端能力共同复用的公共能力：源码入口、跨模块数据流、隐形约束与排查第一跳。编译/部署主流程细节分别看 `02_compile_core.md`、`03_deploy_core.md`、`08_mcp_design.md`。

---

## 2. 核心源码索引

| 能力 | 核心入口 | 作用 |
|------|----------|------|
| 日志 | `main/src/main/java/com/sickworm/intellij/jugg/logger/JuggLogger.kt`、`FileLogger.kt`、`TimeLogger.kt` | 项目级 / 全局日志分发、`compile_latest.log` 快捷入口、阶段耗时埋点 |
| 路径与临时产物 | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggPathManager.kt`、`JuggGlobalPathManager.kt`、`main/src/main/java/com/sickworm/intellij/jugg/project/ExpiredArtifactCleaner.kt` | 项目级 `build/jugg`、稳定 `.gradle/jugg`、用户级 `~/.jugg` 文件归属，以及项目级过期产物清理 |
| APK 修改 | `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkFileModifier.kt`、`ResourceApkModifier.kt` | APK 插入、替换、zipalign、签名与资源 APK 增量更新 |
| Git worktree | `main/src/main/java/com/sickworm/intellij/jugg/git/GitManager.kt`、`WorktreeFileRepository.kt` | Git 变更识别；worktree 下把 HEAD 操作定向到 worktree-local HEAD |
| 平台桥接 | `main/src/main/java/com/sickworm/intellij/jugg/platform/IPlatformApi.kt`、`PlatformApi.kt` | core 层调用 UI、设备、Gradle、MCP host 能力的抽象边界 |
| 远端服务 | `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServer.kt`、`JuggServerChooser.kt`、`JuggEventLocalStore.kt`、`JuggRemoteCompileApplier.kt` | 上报、版本检测、server failover、全局本地事件记录与远端编译 apply；缺少内置配置时仅明确设置的自定义服务器可启用后台 |
| 问题诊断 | `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/IssueReportBundleBuilder.kt`、`IssueReportUploader.kt` | 白名单诊断包、脱敏、manifest 校验与单一 HTTPS endpoint 上传 |
| 配置模型 | `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt`、`JuggGradleCompileOptions.kt` | 持久化设置、运行参数、Gradle task 派生与远端编译参数 |

---

## 3. 核心数据流

```text
JuggManager 初始化
  -> JuggPathManager 定义 project-local build/jugg、database、log、tmp、mcp_fetch
  -> JuggLogger.register(project, pathManager.logDir) 建立项目日志分发
  -> 编译/部署/MCP 共享同一 Logger 与路径对象
  -> FileLogger 写 compile_*.log，并维护 compile_latest.log / compile_latest-1.log
```

```text
需要 IDE / 设备 / 用户交互能力的 core 逻辑
  -> 调用 PlatformApi
  -> PlatformApi 只转发到已注入的 IPlatformApi host 实现
  -> main 模块避免直接依赖 IDE 实现，测试可使用 platform_compat 桩
```

```text
需要 Jugg 自有全局文件
  -> JuggGlobalPathManager 统一落到 ~/.jugg
  -> resources / hot_update / deploy_cache 等不再散落到项目 build 目录
  -> 项目级编译缓存、日志、DB 仍由 JuggPathManager 留在 build/jugg
```

---

## 4. 隐形约束

- `JuggLogger.getInstance(...)` 要求对应 project key 已注册；未注册会 fail fast，排查“拿不到 logger”先看初始化时机，而不是补空 logger。
- `FileLogger` 的 `compile_latest.log` 是 best-effort 快捷入口；真实滚动文件仍是 `compile_yyyy-MM-dd_HH-mm-ss.%g.log`，日志丢失排查要同时看当前主文件和 `compile_latest-1.log`。
- `TimeLogger.start/end` 以字符串 tag 配对；同一 tag 被跨阶段复用会污染耗时判断，新增高频埋点前先确认 tag 唯一性。
- `TaskRunnerManager.runTaskSafe` 仅在后台任务失败时上报任务名、耗时与异常信息；成功任务不发送事件。
- 每次 `JuggServer.report()` 都先 Best-effort 写入 `~/.jugg/action.db`；无服务器或远端失败不影响本地记录，本地写入失败也不阻止远端上报。
- 普通 `buildPlugin` 不携带 `config/servers.json`；`buildPluginInternal` 才校验并打包本地忽略文件。缺少内置配置时，历史自动选服地址无效，只有用户明确设置的 Custom Server 继续生效。
- 问题报告不复用 server failover：客户端只上传白名单生成且已脱敏的 zip，并固定请求 `https://jugg.sickworm.com/report_issue`，不展示地址或尝试 fallback。
- MCP 拉取产物保留 30 天，问题诊断临时产物保留 7 天；两者在项目启动后使用独立后台任务调用 `ExpiredArtifactCleaner`，局部失败不会阻断另一类清理。
- `JuggPathManager` 同时暴露 project-local 与 global root：编译产物、deployment cache、DB、日志优先 project-local；跨项目复用的 hot update、history、hook / resource 文件优先 `JuggGlobalPathManager`，写事务进入固定全局锁。
- `PlatformApi.impl` 是 host 注入边界；core 代码不要绕过它直接调用 IDE / Android Studio API，否则 `main` 模块测试和 CLI 场景会失效。
- APK 修改链路依赖 `PlatformApi.allAvailableJavaHomes()` 寻找可用签名 JDK；签名失败不要只看 apksigner 输出，也要检查 host Java home 列表。
- 远端编译的 Exclude patterns 控制 local-to-remote 源文件同步中的可配置排除规则。`.gradle` 和 `build` 保持原有固定 include/exclude 顺序：默认排除目录，同时放行 `.gradle/jugg/**`、`build/jugg/config/**` 等 Jugg 必需文件，用户不能通过该字段移除这两项。未自定义时使用并展示 `local.properties`、`.idea/`、`*.iml`、`.git/objects/`、`.git/modules/`、`.cxx/`；用户修改后只使用保存的可配置列表，明确清空表示不应用这些可配置默认排除。旧版本 Additional exclude patterns 没有自定义标记，升级后按未设置处理。配置用分号或换行分隔 rsync glob（逗号仅用于输入兼容），所有同步模式都将 pattern 按用户输入原样交给 rsync，作用域以本次实际传输根为准；`.git/` 可匹配任意层级的同名目录，`/.git/` 仅匹配传输根目录。它不是 gitignore 语义，`..`、引号和 Windows 绝对路径始终不支持。

---

## 5. 排查入口

| 现象 | 优先入口 |
|------|----------|
| `compile_latest.log` 不更新或只看到旧日志 | `JuggLogger.register/unregister`、`FileLogger.recreateIfDeleted()`、`FileLogger.resetLatestCompileLog()` |
| 日志里缺某个阶段耗时 | 对应调用点是否成对调用 `TimeLogger.start/end` |
| APK 修改后安装无效或签名异常 | `ApkFileModifier.insertAndResign()`、`alignApk()`、`signApk()` |
| worktree 下变更识别错乱 | `GitManager` 与 `WorktreeFileRepository` |
| `main` 测试中平台能力报错 | `PlatformApi.impl` 注入点与 `platform_compat/base_api` 桩 |
| 远端服务地址异常或频繁切换 | `JuggServerChooser`、`JuggSettings.serverUrl/serverExpireTimeMill` |

---

## 6. 关联文档

- 编译：`02_compile_core.md`
- 部署：`03_deploy_core.md`
- 工程与路径：`04_engineering_project.md`
- 兼容层：`04_engineering_compat.md`
- MCP：`08_mcp_design.md`
