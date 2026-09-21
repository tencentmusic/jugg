# 公共工具模块（Utilities）

> 最后核对：2026-09-08
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只记录 `main` 中被编译、部署、MCP、远端能力共同复用的公共能力：源码入口、跨模块数据流、隐形约束与排查第一跳。编译/部署主流程细节分别看 `02_compile_core.md`、`03_deploy_core.md`、`08_mcp_design.md`。

---

## 2. 核心源码索引

| 能力 | 核心入口 | 作用 |
|------|----------|------|
| 日志 | `main/src/main/java/com/sickworm/intellij/jugg/logger/JuggLogger.kt`、`FileLogger.kt`、`TimeLogger.kt` | 项目级 / 全局日志分发、`compile_latest.log` 快捷入口、阶段耗时埋点 |
| 路径与临时产物 | `main/src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt`、`JuggGlobalPathManager.kt`、`ExpiredArtifactCleaner.kt` | 项目级 `build/jugg`、稳定 `.gradle/jugg`、用户级全局 root（优先 `~/.jugg`，不可写时 `${java.io.tmpdir}/jugg-<user>`），以及项目级过期产物清理 |
| APK 修改 | `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkFileModifier.kt`、`ResourceApkModifier.kt` | APK 插入、替换、zipalign、签名与资源 APK 增量更新 |
| APK 签名脚本 | `main/src/main/java/com/sickworm/intellij/jugg/apk/CustomApkSignScriptRunner.kt` | 用项目脚本替换 `ApkFileModifier` 的默认 keystore 签名，透传待签名 APK 绝对路径并转发脚本输出 |
| Git worktree | `main/src/main/java/com/sickworm/intellij/jugg/git/GitManager.kt`、`WorktreeFileRepository.kt` | Git 变更识别；worktree 下把 HEAD 操作定向到 worktree-local HEAD |
| 平台桥接 | `main/src/main/java/com/sickworm/intellij/jugg/platform/IPlatformApi.kt`、`PlatformApi.kt` | core 层调用 UI、设备、Gradle、MCP host 能力的抽象边界 |
| 远端服务 | `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServer.kt`、`PublicUpdateChecker.kt`、`JuggServerChooser.kt`、`JuggEventLocalStore.kt`、`JuggRemoteCompileApplier.kt` | 上报、版本检测、server failover、全局本地事件记录与远端编译 apply；缺少内置配置时仅明确设置的自定义服务器可启用后台，无自建后台时自动通过 PublicUpdateChecker 并发向 JetBrains Marketplace 与 GitHub 查询公网更新 |
| 问题诊断 | `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/IssueReportBundleBuilder.kt`、`IssueReportUploader.kt` | 白名单诊断包、脱敏、manifest 校验与单一 HTTPS endpoint 上传 |
| 配置模型 | `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt`、`JuggGradleCompileOptions.kt` | 持久化设置、运行参数、Gradle task 派生与远端编译参数 |

---

## 3. 核心数据流

```text
JuggManager 初始化
  -> JuggPathManager 定义 project-local build/jugg、database、tmp、mcp_fetch，以及 global project log
  -> JuggLogger.register(project, pathManager.logDir) 建立项目日志分发
  -> JuggLogger.linkLegacyLogDir(project, pathManager.legacyLogDir) 登记兼容入口
  -> 编译/部署/MCP 共享同一 Logger 与路径对象
  -> FileLogger 写 compile_*.log，并维护 compile_latest*.log 与 build/jugg/log 兼容链接
```

```text
需要 IDE / 设备 / 用户交互能力的 core 逻辑
  -> 调用 PlatformApi
  -> PlatformApi 只转发到已注入的 IPlatformApi host 实现
  -> main 模块避免直接依赖 IDE 实现，测试可使用 platform_compat 桩
```

```text
需要 Jugg 自有全局文件
  -> JuggGlobalPathManager 优先落到 ~/.jugg
  -> 家目录不可写时回退到 ${java.io.tmpdir}/jugg-<user>
  -> resources / hot_update / deploy_cache / CLI / skills / hooks / test_flag 共用该 root
  -> 日志按 <工程名>_<工程绝对路径 SHA-256 前 8 位> 隔离在 log/，其余项目级编译缓存与 DB 留在 build/jugg
```

---

## 4. 隐形约束

- `JuggLogger.getInstance(...)` 要求对应 project key 已注册；未注册会 fail fast，排查“拿不到 logger”先看初始化时机，而不是补空 logger。
- `FileLogger` 的 `compile_latest.log` 和 `build/jugg/log` 都是 best-effort 快捷入口；初始化及 `recreateIfDeleted()` 会尝试维护兼容链接。旧 `build/jugg/log` 真实目录会先迁入全局目录，同名文件增加 `.legacy-N` 后缀，迁移或链接失败时恢复旧目录；普通文件不覆盖。真实滚动文件仍是 `compile_yyyy-MM-dd_HH-mm-ss.%g.log`，日志丢失排查要同时看当前主文件和 `compile_latest-1.log`。
- `TimeLogger.start/end` 以字符串 tag 配对；同一 tag 被跨阶段复用会污染耗时判断，新增高频埋点前先确认 tag 唯一性。
- `TaskRunnerManager.runTaskSafe` 仅在后台任务失败时上报任务名、耗时与异常信息；成功任务不发送事件。
- 每次 `JuggServer.report()` 都先 Best-effort 写入全局 `action.db`（默认 `~/.jugg/action.db`）；无服务器或远端失败不影响本地记录，本地写入失败也不阻止远端上报。
- 普通 `buildPlugin` 不携带 `config/servers.json`；`buildPluginInternal` 才校验并打包本地忽略文件。缺少内置配置时，历史自动选服地址无效，只有用户明确设置的 Custom Server 继续生效。
- 问题报告不复用 server failover：客户端只上传白名单生成且已脱敏的 zip，并固定请求 `https://jugg.sickworm.com/report_issue`；确认窗口展示固定、单一的 HTTPS 目标地址，不持久化地址且不尝试 fallback。
- 后台可通过 `autoUploadFailureLogs` 开启最终失败日志自动上传，并用 `autoUploadFailureLogsExcludeRegex` 排除已知错误。排除正则只对本轮最终错误摘要做包含匹配，不扫描日志全文；空正则不过滤，非法正则按 fail-closed 跳过上传。
- 自动失败诊断包只包含 `JuggPathManager.logDir` 中按修改时间排序的最近两份真实 `compile_*.log` 和 manifest；排除 `compile_latest*` 快捷入口，不包含工程快照、环境摘要、logcat 或 hook 日志。上传异步 Best-effort 执行，失败不重试也不影响 Run 结果。
- 问题报告把现存的 `project_infos.json`、`gradle_project_infos.json` 和 `gradle_include_builds.txt` 当前记录的 `include_build_*_gradle_project_infos.json` 作为默认勾选、可取消的高敏感度候选项，结构化脱敏副本位于 `diagnostics/project-info/`；`applicationId` 等诊断字段保留，SigningConfig 凭据、keystore、keyAlias、Manifest placeholders、APT/KAPT 参数和通用敏感键的值替换为占位符。JSON 解析失败时只跳过对应快照，目录残留的 included build 文件和其他 `project_infos.db` 文件不进入诊断包。必选 Jugg 日志仍排在最前。
- MCP 拉取产物保留 30 天，问题诊断临时产物保留 7 天；两者在项目启动后使用独立后台任务调用 `ExpiredArtifactCleaner`，局部失败不会阻断另一类清理。
- `JuggPathManager` 同时暴露 project-local 与 global root：编译产物、DB 优先 project-local；日志按工程隔离在 global `log/`，`build/jugg/log` 仅 best-effort 创建兼容符号链接；跨项目复用资源、deploy cache、hook / resource 文件使用 `JuggGlobalPathManager`。`~/.jugg` 探测失败时，全局 root 改为 `${java.io.tmpdir}/jugg-<user>`，后续编译不应再因家目录权限失败。
- `PlatformApi.impl` 是 host 注入边界；core 代码不要绕过它直接调用 IDE / Android Studio API，否则 `main` 模块测试和 CLI 场景会失效。
- `JuggSettings` 的远程命令历史按 `user + host + port + remoteProjectPath` 保存，每个目标只保留最近 10 条并按完整命令去重。读取损坏数据或写入失败时返回空历史，不影响远程命令执行；命令正文不得写入 Jugg 持久日志。`RemoteUserCommand` 将正文编码后交给子 shell，并用每次执行唯一的完成标记解析退出码，避免用户命令中的注释、`exit` 或输出内容干扰协议。
- APK 修改链路依赖 `PlatformApi.allAvailableJavaHomes()` 寻找可用签名 JDK；每次重试会移除已有的 `JAVA_HOME` 并写入当前候选，即使原环境未设置该变量也能真正切换 JDK。签名失败不要只看 apksigner 输出，也要检查 host Java home 列表。
- `ApkFileModifier.insertAndResign()` 在同目录临时副本上完成插入、对齐、签名和校验，校验复用实际签名成功时的 JDK 环境，全部成功后才替换原 APK；任一阶段失败时保留原 APK，并 best-effort 清理临时文件。
- `ApkFileModifier` 调用 zipalign 和 apksigner 时按宿主 shell 逐项转义参数（`shellEscapeArgument`）；SDK、APK、keystore 路径包含空格、括号或 Unicode 字符时仍作为单个参数传递。`CustomApkSignScriptRunner` 复用同一转义规则拼接 `<configured command> '<apk 绝对路径>'`。
- `ApkFileModifier` 的可空 `customApkSignScriptRunner` 决定签名阶段走自定义脚本还是默认 keystore：走脚本时 `signConfig` 可以为空，签名后仍统一执行 `verifyApk()` 和原子替换。脚本命令使用 `isSecureCommand`，因此 `CmdExecutor` 的 debug 日志只打印 `(secure)`，脚本原文不进入日志。
- 兼容资源 APK 修改在 JVM 14+ 继续使用 ZipFS；`ResourceApkModifier` 为每轮写入创建唯一同目录临时文件，成功关闭后优先原子替换正式 `resource.ap_`，平台不支持时回退普通替换，避免异常遗留的 ZipFS URI 和半成品污染后续 Run。日志记录条目数、内容总字节、最大条目、APK 字节及导出前后 heap，用于区分 ZIP 生成峰值与 deployer payload 包装峰值。
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
