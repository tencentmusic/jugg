# 公共工具模块（Utilities）

> 最后核对：2026-08-18
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
| Runtime 信息 | `project/runtime/RuntimeInfo.kt` | Host 显式提供 runtime type/version、host version 与 build time，供 Server、锁和 hot update 使用 |
| Hot update | `server/JuggHotUpdateManager.kt`、`idea/.../server/IdeaHotUpdateCoordinator.kt`、`idea/.../loader/JuggHotUpdateBootstrap.kt` | 共享下载校验、原子发布与清理；IDEA 检查和安装编排；Loader 启动前只读 manifest |
| 配置模型 | `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt`、`project/runtime/JsonRuntimeSettingsRepository.kt`、`ProjectCustomConfigManager.kt`、`JuggGradleCompileOptions.kt` | IDEA/standalone 共享设置、project custom config 生命周期、运行参数与 Gradle task 派生 |

---

## 3. 核心数据流

```text
JuggManager 初始化
  -> JuggPathManager 定义 project-local build/jugg、database、log、tmp、mcp_fetch
  -> IDEA Runtime 注册 pathManager.logDir；standalone Runtime 注册 pathManager.standaloneCliLogDir
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
  -> settings.json / action.db / resources / hot_update / skills / library_test_build_records 等跨项目状态集中管理
  -> 写入统一通过 ~/.jugg/locks/global.lock 串行，文件快照使用临时文件和原子替换
  -> 项目级编译缓存、日志、DB 仍由 JuggPathManager 留在 build/jugg
```

```text
IDEA Runtime settings 初始化
  -> JuggManager 的 Init Jugg 后台任务读取旧 PropertiesComponent 并转换为 legacy fields，不阻塞 init 调用线程
  -> JuggSettings.migrateLegacyJuggSettings() 通过 IDEA adapter 读取旧属性，只回填 settings.json 缺失字段，已有 JSON 值优先
  -> 成功后在 PropertiesComponent 记录迁移完成；失败不阻断启动且不清理旧属性，下次启动继续重试
  -> 首次 persisted setting get/set 自动加载 JSON，之后按字段同步持久化修改

Standalone/CLI Runtime settings
  -> 首次 persisted setting get/set 通过 JsonRuntimeSettingsRepository 读取同一 settings.json
  -> 文件缺失时使用 JuggSettings 默认值且不创建文件

Project custom config
  -> ProjectCustomConfigManager 私有持有 ProjectCustomConfigStore
  -> local custom_config.json 优先于 server default_custom_config.json
  -> refresh/updateDefaultConfig 统一应用 server、文件规则、classpath、custom compiler 与 embedded APK
```

```text
Runtime info
  -> Host 创建 RuntimeInfo(runtimeType/runtimeVersion/hostVersion/buildTime)
  -> JuggServer 只消费注入 info，不读取 Project、plugin manifest 或 PlatformApi
  -> TaskRunner 从 info 中使用 runtime type/version 建立锁 owner identity
```

```text
Hot update
  -> JuggHotUpdateManager 在固定全局锁内下载、MD5 校验并原子发布 immutable jar 与 hot_update_data.json
  -> compatible update 分别发布 IDEA load_manifest.json 与 standalone_load_manifest.json
  -> isNeedReinstall=true 只保存 JAR、candidate Bundle 和 metadata，不替换 active manifest
  -> 新插件启动后仅在 releaseBuildId 精确一致时激活 candidate standalone snapshot
  -> IDEA JuggHotUpdateBootstrap 与 standalone StandaloneBootstrap 分别只读取自己的 manifest
```

---

## 4. 隐形约束

- `JuggLogger.getInstance(...)` 要求对应 project key 已注册；未注册会 fail fast，排查“拿不到 logger”先看初始化时机，而不是补空 logger。
- `FileLogger` 的 `compile_latest.log` 是 best-effort 快捷入口；真实滚动文件仍是 `compile_yyyy-MM-dd_HH-mm-ss.%g.log`，日志丢失排查要同时看当前主文件和 `compile_latest-1.log`。
- IDEA 日志位于 `build/jugg/log/`，standalone 日志位于 `build/jugg/log/standlone_cli/`；两个目录各自最多保留 10 份日志。Issue Report 按两目录的修改时间合并，只带最新 10 份，并保留 `standlone_cli/` 目录层级。
- `TimeLogger.start/end` 以字符串 tag 配对；同一 tag 被跨阶段复用会污染耗时判断，新增高频埋点前先确认 tag 唯一性。
- `TaskRunnerManager.runTaskSafe` 仅在后台任务失败时上报任务名、耗时与异常信息；成功任务不发送事件。
- 每次 `JuggServer.report()` 都先 Best-effort 写入 `~/.jugg/action.db`；无服务器或远端失败不影响本地记录，本地写入失败也不阻止远端上报。
- 普通 `buildPlugin` 不携带 `config/servers.json`；`buildPluginInternal` 才校验并打包本地忽略文件。缺少内置配置时，历史自动选服地址无效，只有用户明确设置的 Custom Server 继续生效。
- 问题报告不复用 server failover：客户端只上传白名单生成且已脱敏的 zip，并固定请求 `https://jugg.sickworm.com/report_issue`，不展示地址或尝试 fallback。
- MCP 拉取产物保留 30 天，问题诊断临时产物保留 7 天；两者在项目启动后使用独立后台任务调用 `ExpiredArtifactCleaner`，局部失败不会阻断另一类清理。
- `JuggPathManager` 同时暴露 project-local 与 global root：编译产物、deployment cache、DB、日志优先 project-local；跨项目复用的 hot update、history、hook / resource 文件优先 `JuggGlobalPathManager`，写事务进入固定全局锁。
- `settings.json` 写入使用固定全局锁、临时文件和原子替换；同进程更新由 `JuggSettings` 串行，字段修改会在锁内基于最新磁盘快照更新，避免双 Runtime 的不同字段互相覆盖；IDEA legacy migration 只补缺失字段，不能覆盖已存在 JSON 值。CLI 强制 backup classpath 使用进程级 override，不修改共享用户设置。`JuggGlobalPathManager.rootDir` 切换后 `JuggSettings` 会自动丢弃旧 root 缓存，测试通过独立 root 隔离真实用户设置。
- `PlatformApi.impl` 是 host 注入边界；core 代码不要绕过它直接调用 IDE / Android Studio API，否则 `main` 模块测试和 CLI 场景会失效。
- `JuggSettings` 的远程命令历史按 `user + host + port + remoteProjectPath` 保存，每个目标只保留最近 10 条并按完整命令去重。读取损坏数据或写入失败时返回空历史，不影响远程命令执行；命令正文不得写入 Jugg 持久日志。`RemoteUserCommand` 将正文编码后交给子 shell，并用每次执行唯一的完成标记解析退出码，避免用户命令中的注释、`exit` 或输出内容干扰协议。
- `JuggServer` 的 runtime identity 必须由 Host 注入 `RuntimeInfo`；IDEA、CI、standalone 不得在共享 Server 内推断 plugin/IDE metadata。事件保留后端兼容的 `version/ide_version` 字段，实际值分别来自 `runtimeVersion/hostVersion`；`runtimeType` 仅用于 Runtime 锁 owner identity，不进入事件上报。
- `JuggServer` 使用挂在 Runtime Scope 下的 `SupervisorJob` 执行更新检查、上报和自定义编译器下载等辅助任务；Runtime dispose 仍会取消这些任务，但任一辅助任务的未捕获异常不得反向取消编译、部署和 TaskRunner 共用的 Runtime Scope。
- hot update jar 和 metadata 写入必须经过 `JuggHotUpdateManager` 的全局锁与原子替换；IDEA 与 standalone 共享 immutable JAR 内容池但使用独立 manifest。`isNeedReinstall=true` 不得更新任一 active manifest，只有新插件 `releaseBuildId` 与 candidate 精确一致才能激活 standalone snapshot；旧 Gson JSON 的 nullable standalone 字段统一以 `orEmpty()` 消费。
- 未引用 hot update jar 保留 90 天；MCP fetch artifact 独立按 30 天清理。runtime/deployer 内容版本资源策略推迟到 standalone deployer 落地时确定。
- APK 修改链路依赖 `PlatformApi.allAvailableJavaHomes()` 寻找可用签名 JDK；签名失败不要只看 apksigner 输出，也要检查 host Java home 列表。
- 远端编译的 Exclude patterns 控制 local-to-remote 源文件同步中的可配置排除规则。`.gradle` 和 `build` 保持原有固定 include/exclude 顺序：默认排除目录，同时放行 `.gradle/jugg/**`、`build/jugg/config/**` 等 Jugg 必需文件，用户不能通过该字段移除这两项。未自定义时使用并展示 `local.properties`、`.idea/`、`*.iml`、`.git/objects/`、`.git/modules/`、`.cxx/`；用户修改后只使用保存的可配置列表，明确清空表示不应用这些可配置默认排除。旧版本 Additional exclude patterns 没有自定义标记，升级后按未设置处理。配置用分号或换行分隔 rsync glob（逗号仅用于输入兼容），所有同步模式都将 pattern 按用户输入原样交给 rsync，作用域以本次实际传输根为准；`.git/` 可匹配任意层级的同名目录，`/.git/` 仅匹配传输根目录。它不是 gitignore 语义，`..`、引号和 Windows 绝对路径始终不支持。

---

## 5. 排查入口

| 现象 | 优先入口 |
|------|----------|
| `compile_latest.log` 不更新或只看到旧日志 | `JuggLogger.register/unregister`、`FileLogger.recreateIfDeleted()`、`FileLogger.resetLatestCompileLog()` |
| 需要判断日志来自哪个 Runtime | `build/jugg/log/`（IDEA）或 `build/jugg/log/standlone_cli/`（standalone）；锁竞争再查看 `Runtime lock contention` |
| 日志里缺某个阶段耗时 | 对应调用点是否成对调用 `TimeLogger.start/end` |
| APK 修改后安装无效或签名异常 | `ApkFileModifier.insertAndResign()`、`alignApk()`、`signApk()` |
| worktree 下变更识别错乱 | `GitManager` 与 `WorktreeFileRepository` |
| `main` 测试中平台能力报错 | `PlatformApi.impl` 注入点与 `platform_compat/base_api` 桩 |
| 远端服务地址异常或频繁切换 | `JuggServerChooser`、`JuggSettings.serverUrl/serverExpireTimeMill` |
| report issue 缺项目信息或日志 | `ProjectInfoReader.printInfo()`、`DeployTargetManager.dumpErrorLogs()`、`JuggServer.reportAndUploadLogs()` |
| hot update 下载成功但下次启动未加载 | `JuggHotUpdateManager.loadManifestFile`、`JuggHotUpdateBootstrap`、runtime `buildTime` 与 loader embedded build time 是否一致 |

---

## 6. 关联文档

- 编译：`02_compile_core.md`
- 部署：`03_deploy_core.md`
- 工程与路径：`04_engineering_project.md`
- 兼容层：`04_engineering_compat.md`
- MCP：`08_mcp_design.md`
