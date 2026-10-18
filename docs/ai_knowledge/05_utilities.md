# 公共工具模块（Utilities）

> 最后核对：2026-10-15
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
| 远端服务 | `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServer.kt`、`PublicUpdateChecker.kt`、`JuggServerChooser.kt`、`JuggEventLocalStore.kt`、`JuggRemoteCompileApplier.kt` | 上报、版本检测、server failover、全局本地事件记录与远端编译 apply；缺少内置配置时继承此前内网包保存的服务器列表，显式自定义服务器优先，无自建后台时自动通过 PublicUpdateChecker 并发向 JetBrains Marketplace 与 GitHub 查询公网更新 |
| 问题诊断 | `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/IssueReportBundleBuilder.kt`、`IssueReportUploader.kt` | 白名单诊断包、按目标决定日志脱敏、manifest 校验与单一目标上传（公共服务 HTTPS，后台 HTTP/HTTPS） |
| 本地 Agent 调查 | `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/InvestigationSnapshotBuilder.kt` | 独立证据副本、来源与 SHA-256 清单、部分采集失败记录和 ASK AI prompt；不自动上传 |
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

- `GitManager` 每次操作创建 Repository，并在操作完成、异常或提前返回时关闭；`Git.wrap(repository)` 不接管 Repository 的生命周期，不能仅关闭 Git 包装对象。一次 `filterChangedFiles()` 的 `IndexDiff` 与 `FileTreeIterator` 共用同一 Repository。配置读取、仓库存在性检查和初始化返回的 Git 对象也必须释放；worktree 继续使用原 builder 和 worktree-local HEAD 语义。
- JGit 的 `packedGitLimit` 只约束 pack 窗口数据，不代表索引及整个 `WindowCache` 保留树的上限。缓存条目中的 `pack` 字段可以强引用 `Pack.loadedIdx`；反复创建而不关闭 Repository，可能让同一个大 pack 的索引出现多个副本。定位内存时先分开窗口、索引和副本数量，不应仅缩小窗口缓存或依赖 GC。
- IDE 侧 `AskAiInvestigation` 负责问题输入、环境/logcat/产物采集与失败入口；`JuggManager` 只委托，`InvestigationSnapshotBuilder` 保持独立副本和 prompt 职责。复制日志前 `JuggLogger.flushProjectLog()` 等待异步 writer 排空，完整异常栈读取保存的真实文件；`RunLogCollector` 仅保留原有消息摘要。等待不持有全局生命周期锁或部署 monitor；中断/失败只记录 `logs-flush`，其余证据继续保留。
- ASK AI 现场保存在 `JuggPathManager.investigationDir`，按项目路径 hash 隔离于全局 Jugg root，不进入 `build/jugg` 或远程编译同步范围；启动后独立清理超过 30 天的副本，复制文件保留采集时 mtime，源 mtime 仅写入 manifest。单个来源文件无法复制时记录失败，清单或 prompt 无法写入则明确失败。本地副本保留原始上下文，不复用用于上传的脱敏报告包；用户自行决定是否向 Issue 提供内容。
- Source 的物理保存名包含单次采集序号，APK 归属和 deployPath 独立记录，避免不同 APK 的同名资源/Dex 相互覆盖。复制前后检查源大小和 mtime，检测到采集期间变化则舍弃该文件并记录原因。本地部署记录不是设备加载状态证明。
- Ask AI prompt 从 `main/src/main/resources/diagnostics/ask-ai-zh.md` / `ask-ai-en.md` 读取完整 Markdown 模板。生成时读取 JVM 系统默认 `Locale`，`language == zh` 使用中文（覆盖简体、繁体），其他语言使用英文，Agent 输出和 Issue Form 同步选择；弹窗说明始终使用英文。模板只交接问题描述、现场路径、时间、语言和源码获取授权；环境与采集失败项从 manifest 读取，不在 prompt 重复展开。模板占位符单次替换，原始描述和路径不翻译、不再次展开。
- 共用的 `.agents/skills/issue-handler/references/local-agent-investigation-guide.md` 承载前置知识、现场分析、简洁回复和按需 Issue 交接，合入 main 后供后续调查读取；实现与支持范围仍按现场版本核对。所有入口先读取 AGENTS、00/99/98，再进入 09 及相关专题，不按错误分类提前跳过 Jugg 上下文。ASK AI 直接读取 manifest 对应副本；非 ASK AI 或关键证据缺失时按需读取同目录 `local-agent-evidence-collection.md`，定位已有材料，不重复采集或执行构建/设备操作。
- Guide 的 Issue 交接要求 Agent 对照当前 Bug Issue Form，生成 `issues/new` 预填写链接和按字段可复制全文；中文/英文选择对应 YAML，URL 使用 `template`、`title`、`description`、`diagnostic_material` 等当前字段 ID 并逐值编码，不能只传 `body`。URL 仅包含可公开摘要，过长或预填写失效时回退短模板链接和全文。Report ID 不编造，原始现场不冒充脱敏 Diagnostics Bundle，本地路径不代表附件；下拉框、重复 Issue 搜索确认和最终提交由用户核对完成。

- `JuggLogger.getInstance(...)` 要求对应 project key 已注册；未注册会 fail fast，排查“拿不到 logger”先看初始化时机，而不是补空 logger。
- `FileLogger` 的 `compile_latest.log` 和 `build/jugg/log` 都是 best-effort 快捷入口；初始化及 `recreateIfDeleted()` 会尝试维护兼容链接。旧 `build/jugg/log` 真实目录会先迁入全局目录，同名文件增加 `.legacy-N` 后缀，迁移或链接失败时恢复旧目录；普通文件不覆盖。真实滚动文件仍是 `compile_yyyy-MM-dd_HH-mm-ss.%g.log`，日志丢失排查要同时看当前主文件和 `compile_latest-1.log`。
- `TimeLogger.start/end` 以字符串 tag 配对；同一 tag 被跨阶段复用会污染耗时判断，新增高频埋点前先确认 tag 唯一性。
- `TaskRunnerManager.runTaskSafe` 仅在后台任务失败时上报任务名、耗时与异常信息；成功任务不发送事件。
- 每次 `JuggServer.report()` 都先 Best-effort 写入全局 `action.db`（默认 `~/.jugg/action.db`）；无服务器或远端失败不影响本地记录，本地写入失败也不阻止远端上报。
- 普通 `buildPlugin` 不携带 `config/servers.json`；`buildPluginInternal` 才校验并打包本地忽略文件。`JuggServerChooser` 仅在 `hasAvailableServer()` 或 `updateServer()` 原有默认规则读取点按需加载列表，不在对象创建时初始化；读取当前非空内置列表时写入 IDE 全局 `JuggSettings.inheritedServerRulesJson`，缺少内置列表时恢复该列表。该字段为 internal，后台 defaultSettings 无法反射写入。项目下发的 `servers` 优先于默认列表，显式空列表不回退；用户明确设置的 Custom Server（`serverExpireTimeMill=-1`）仍优先，清空后恢复默认选服。继承列表继续使用原有过期刷新和故障切换，不把自动选服改成 Custom Server。保存失败仍使用当前内置列表；继承数据读取或解析失败时记录 debug 并舍弃继承，其他路径继续工作。
- 内网配置继承不依赖项目 build 目录，但要求至少运行一次包含此能力的内网包。全新公开包无保存列表时保持无后台；历史 `serverUrl` 单独存在不作为内网身份依据。继承后台被当前选服器选中后，其事件上报、更新检查与诊断上传规则保持不变。
- 默认服务器 JSON 的加载来源记录为 debug 日志：包内 `config/servers.json`（含规则数量）、IDE 全局继承配置，或两者均不可用；不打印 JSON 正文。继承配置读取、解析失败仍记录原因。
- 手动问题报告使用当前选中且可用的后台服务，对其根地址拼接 `/report_issue`，以默认勾选的诊断候选项直接打包上传，不显示文件/地址确认面板；复制结果增加 `Server Url: <后台根地址>`。后台直传允许 HTTP 或 HTTPS，请求失败时不回退公网，结果窗口可对同一 zip、同一目标重试。后台上传成功或失败的结果窗口均提供 `Save to local`，复用 `RevealFileAction.openFile(bundle.file)` 打开目录并选中本次上传的 ZIP，点击后保留结果窗口；公共服务上传结果不增加该按钮。没有可用后台地址时仍显示确认面板并请求 `https://jugg.sickworm.com/report_issue`，可取消候选项或仅本地保存，公共服务继续仅接受 HTTPS。
- 后台可通过 `autoUploadFailureLogs` 开启最终失败日志自动上传，并用 `autoUploadFailureLogsExcludeRegex` 排除已知错误。排除正则只对本轮最终错误摘要做包含匹配，不扫描日志全文；空正则不过滤，非法正则按 fail-closed 跳过上传。手动上传向明确设置的 Custom Server 和内置列表自动选出的后台发送 `file`、`project_name`、`username`，两者使用同一规则；公共服务仅接收 `file`。自动上传始终发送 `is_auto_upload=true`、首条非空失败摘要 `failed_reason`、`project_name`、`username`、`plugin_version`、`report_id`，有更长错误内容时发送 `error_detail`。手动和自动上报都将同一目标快照绑定到诊断包；明确设置的 Custom Server 和自动选择的后台均接收未脱敏的 Jugg 日志、logcat、hook 日志与自动上传错误文本，仅无可用后台时的公共服务和本地保存走日志脱敏。公共服务拒绝标记为未脱敏的诊断包，公共服务域名不能配置为未脱敏后台，上传不跟随重定向。manifest 对原文日志标记 `redaction=none`，结构化工程快照的敏感字段无论目标如何始终脱敏。
- 自动失败诊断包复用手动报告的完整准备流程，包含 `JuggPathManager.logDir` 中按修改时间排序的最近两份真实 `compile_*.log`、环境信息、工程摘要、脱敏工程快照、存在时的 hook 调试日志和 manifest；排除 `compile_latest*` 快捷入口且不采集 adb logcat。仅在存在 `availableServerUrl` 时上传到该后台的 `/report_issue`，允许 HTTP 或 HTTPS；没有可用后台时直接跳过，失败不回退公网。上传异步 Best-effort 执行，失败不重试也不影响 Run 结果。
- 问题报告把现存的 `project_infos.json`、`gradle_project_infos.json` 和 `gradle_include_builds.txt` 当前记录的 `include_build_*_gradle_project_infos.json` 作为默认勾选、可取消的高敏感度候选项，结构化脱敏副本位于 `diagnostics/project-info/`；`applicationId` 等诊断字段保留，SigningConfig 凭据、keystore、keyAlias、Manifest placeholders、APT/KAPT 参数和通用敏感键的值替换为占位符。JSON 解析失败时只跳过对应快照，目录残留的 included build 文件和其他 `project_infos.db` 文件不进入诊断包。必选 Jugg 日志仍排在最前。
- MCP 拉取产物保留 30 天，问题诊断临时产物保留 7 天；两者在项目启动后使用独立后台任务调用 `ExpiredArtifactCleaner`，局部失败不会阻断另一类清理。
- `CompileEffectAnalyzer` 从依赖 JAR 提取的 class 统一放在 `JuggPathManager.extractedClassesDir`（`build/jugg/tmp/extracted_classes/`），保留包路径；`DeployFileManager.init()` 调用其 `clear()` 删除该子目录，覆盖全量构建后重建、项目恢复和 CLI 初始化。清理失败只打印 warn，不阻断初始化；增量轮次结束不清理，历史散落在 `tmp/` 根下的 class 和其他临时目录均不处理。
- `JuggPathManager` 同时暴露 project-local 与 global root：编译产物、DB 优先 project-local；日志按工程隔离在 global `log/`，`build/jugg/log` 仅 best-effort 创建兼容符号链接；跨项目复用资源、deploy cache、hook / resource 文件使用 `JuggGlobalPathManager`。`~/.jugg` 探测失败时，全局 root 改为 `${java.io.tmpdir}/jugg-<user>`，后续编译不应再因家目录权限失败。
- `WindowsRsyncRuntime` 只从 IDE 继承的 PATH 顺序选择第一个 rsync.exe，忽略空项和相对目录；只使用同目录 ssh.exe/cygpath.exe，不跳过不完整安装。无 JUGG_CYGWIN_HOME、固定根目录或系统 OpenSSH 回退。首次本地传输由独立的 `WindowsRsyncProbe` 执行 rsync/ssh 版本探测，失败记录后继续实际流程，取消时停止后续探测，不额外复测；cygpath 在实际转换时验证，同一客户端复用运行时。Cygwin 不分发、不自动安装。
- `NativeProcessDiagnostics` 复用执行器注入的项目 JuggLogger，独立记录原生进程的工具路径、白名单版本参数、父子 PID、耗时、真实十进制/十六进制退出码及取消状态；正常记录为 debug，版本探测失败为 warn 并说明继续实际流程。启动异常仅保留异常类型和系统 error 编号，不记录完整 argv、环境或原始异常消息；stdout/stderr 继续沿原有输出链路记录。新增日志失败只舍弃该条诊断，不覆盖进程结果，不启动外部采集或重试。PID 与时间戳用于手动关联 ProcMon，日志不能单独证明冲突文件或占用者。
- 本地检测失败通过 `WindowsRsyncSetupException` 和 `TerminalOutputListener.onWindowsRsyncSetupFailure` 传递类别、工具路径、缺失项和受控原因；不传输原始进程输出或远端认证数据。CLI 输出恢复方法，IDE 提供双语环境配置 Prompt hyperlink，并跳过 ASK AI 现场采集。对话框仅预览/复制，复制失败保留预览供手工操作；配置完成后重启 IDE 并手动重试。
- Windows 外部 SSH 使用同一 Cygwin 安装的 ssh，隔离用户配置，按已有配置使用公钥或密码；密码通过每次调用的环境和内置 askpass.exe 读取，不出现在 argv/日志/文件中。askpass 源码为 `tools/windows-rsync/windows_rsync_askpass.cs`，由系统 .NET Framework 4 编译。known_hosts 参数不夹带字面双引号，统一由 -e 渲染层转义。
- Cygwin `winsymlinks:nativestrict` 在链接目标尚未落盘时会报 ENOENT。只有 classpath 拉取返回 23 且包含明确的 symlink ENOENT 诊断时，才在首次传输已落盘目标后重试一次；第二次结果原样返回。认证、断网和普通部分传输错误不重试，取消也不重试。下载链接已由 JVM 读取验证，不回退 Cygwin 专用链接。
- `PlatformApi.impl` 是 host 注入边界；core 代码不要绕过它直接调用 IDE / Android Studio API，否则 `main` 模块测试和 CLI 场景会失效。
- `JuggSettings` 的远程命令历史按 `user + host + port + remoteProjectPath` 保存，每个目标只保留最近 10 条并按完整命令去重。读取损坏数据或写入失败时返回空历史，不影响远程命令执行；命令正文不得写入 Jugg 持久日志。`RemoteUserCommand` 将正文编码后交给子 shell，并用每次执行唯一的完成标记解析退出码，避免用户命令中的注释、`exit` 或输出内容干扰协议。
- APK 修改链路依赖 `PlatformApi.allAvailableJavaHomes()` 寻找可用签名 JDK；每次重试会移除已有的 `JAVA_HOME` 并写入当前候选，即使原环境未设置该变量也能真正切换 JDK。签名失败不要只看 apksigner 输出，也要检查 host Java home 列表。
- `ApkFileModifier.insertAndResign()` 在同目录临时副本上完成插入、对齐、签名和校验，校验复用实际签名成功时的 JDK 环境，全部成功后才替换原 APK；任一阶段失败时保留原 APK，并 best-effort 清理临时文件。
- `ApkFileModifier` 的 `ByteArray` 插入在 JVM 14+ 继续使用 ZipFS `STORED` 语义，即使同批存在 file-backed NativeLib 也保持不变。仅存在 file-backed NativeLib 时切换到流式 ZIP 重写：file-backed entry 继承基线的 `STORED` / `DEFLATED` 方法，写入后复核 CRC 与源文件状态；超过经典 ZIP 单 entry 4 GiB 边界或大型 entry 不存在于基线 APK 时明确失败。
- `ApkFileModifier` 调用 zipalign 和 apksigner 时按宿主 shell 逐项转义参数（`shellEscapeArgument`）；SDK、APK、keystore 路径包含空格、括号或 Unicode 字符时仍作为单个参数传递。`CustomApkSignScriptRunner` 复用同一转义规则拼接 `<configured command> '<apk 绝对路径>'`。
- `ApkFileModifier` 的可空 `customApkSignScriptRunner` 决定签名阶段走自定义脚本还是默认 keystore：走脚本时 `signConfig` 可以为空，签名后仍统一执行 `verifyApk()` 和原子替换。脚本命令使用 `isSecureCommand`，因此 `CmdExecutor` 的 debug 日志只打印 `(secure)`，脚本原文不进入日志。
- 兼容资源 APK 修改在 JVM 14+ 继续使用 ZipFS；`ResourceApkModifier` 为每轮写入创建唯一同目录临时文件，成功关闭后优先原子替换正式 `resource.ap_`，平台不支持时回退普通替换，避免异常遗留的 ZipFS URI 和半成品污染后续 Run。日志记录条目数、内容总字节、最大条目、APK 字节及导出前后 heap，用于区分 ZIP 生成峰值与 deployer payload 包装峰值。
- 远端编译的 Exclude patterns 控制 local-to-remote 源文件同步中的可配置排除规则。`.gradle` 和 `build` 保持原有固定 include/exclude 顺序：默认排除目录，同时放行 `.gradle/jugg/**`、`build/jugg/config/**` 等 Jugg 必需文件，用户不能通过该字段移除这两项。未自定义时使用并展示 `local.properties`、`.idea/`、`*.iml`、`.git/objects/`、`.git/modules/`、`.cxx/`；用户修改后只使用保存的可配置列表，明确清空表示不应用这些可配置默认排除。旧版本 Additional exclude patterns 没有自定义标记，升级后按未设置处理。配置用分号或换行分隔 rsync glob（逗号仅用于输入兼容），所有同步模式都将 pattern 按用户输入原样交给 rsync，作用域以本次实际传输根为准；`.git/` 可匹配任意层级的同名目录，`/.git/` 仅匹配传输根目录。它不是 gitignore 语义，`..`、引号和 Windows 绝对路径始终不支持。

- Windows rsync 的版本探测和 cygpath 转换使用成功静默模式：正常 stdout/stderr 不进入控制台，失败时恢复原输出通道，收集结果、退出码和取消语义保留。远端系统检查只过滤成功完成标记；实际 rsync 传输仍实时输出。

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
