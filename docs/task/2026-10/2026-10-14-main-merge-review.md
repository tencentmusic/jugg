# main 合入 develop/4.0 的冲突与行为审查

**Purpose:** Integrate main 3.5.4 into develop/4.0 and preserve both incoming fixes and standalone/runtime behavior.
**Decision:** Resolve every textual and delete/modify conflict at the current behavior owner; audit automatic merges and verify the risky boundaries.
**Impact:** Keep plugin 4.0.5 and newer CLI/skill versions, adopt main's functionality, and repair cross-host cache/log integration without pushing or creating a PR.

## 阅读依据

- 必需入口：`docs/ai/00_overview.md`、`99_index.md`、`98_code_map.md`。
- 任务主题：`06_testing.md`、`97_maintenance_manual.md`（含 §13）、`02_compile_core.md`、`02_compile_databinding.md`、`02_compile_resource.md`、`02_compile_source.md`、`03_deploy_core.md`、`03_runtime_jvmti.md`、`04_engineering_compat.md`、`04_engineering_ide.md`、`05_utilities.md`、`08_mcp_design.md`、`09_plugin_runtime_debug.md`、`10_wiki_architecture.md`、`10_wiki_authoring.md`。
- 用户页/指南：`.agents/skills/wiki-writer/SKILL.md`；英文/中文 onboarding index、installation，reference/compatibility；英文/中文 concepts/compat-deploy、jugg-runtime、jugg-jvmti-agent 的受影响段落。
- 实现证据与 owner 见冲突表、自动合并审查表；文档不能替代上述实现核验。IDE index 初始确认工程并定位共享 cache 后返回 project_not_found，后续使用本工作区源码定位，没有在另一个项目上重构。

## 合并边界

- 日期：2026-10-14；原工作区干净，分支始终为 `develop/4.0`。
- 合并前 HEAD：`64c33b79b8b092073f4e0c467ba75fc1ce8f6e5f`；incoming 本地 `main` 与 `origin/main` 同为 `32fc3b6612eaebb487e4e70fde8015ef443381ec`。
- merge base：`600af6decc65e970520350d73eb8f11cda3405d7`。
- 使用 `git merge --no-commit --no-ff refs/heads/main`；此次范围是开始时的本地 main，未 fetch、push、创建 PR 或操作 release/tag。
- 49 个冲突文件：34 个文本冲突、15 个 modify/delete（12 个旧知识库文件、3 个旧 IDEA cache/service/test）。下表记录每个文件；数字为初始 merge marker 块数，modify/delete 用 MD 表示。最终合并保留两父提交，旧内容可从父提交重建。

## 全部冲突与解决方式

| # | 文件 | 初始冲突 | 解决方式 |
|---|---|---|---|
| 1 | `.agents/skills/issue-handler/references/local-agent-investigation-guide.md` | 8 | 采用 main 的 ASK AI/Issue 只读调查流程、已有现场优先、版本匹配和证据边界；将当前知识库定位改为 docs/ai；旧 revision 缺失时仅对该文件回退一次到 docs/ai_knowledge，避免旧 main/标签链接失效。 |
| 2 | `.agents/skills/jugg-update-version-changelog/SKILL.md` | 1 | 保留本分支 English RC 为内容源；吸收 main 的相邻 [prefix][module] 标签与同窗口能力归并规则；分类说明译成英文，中文场景/文案例子保留为本地化输入。 |
| 3 | `.github/PULL_REQUEST_TEMPLATE.md` | 2 | 采用 main 的英文 PR 模板，知识库链接改为 docs/ai，Wiki 采用 English-first 后同步中文。 |
| 4 | `.github/workflows/canary.yml` | 1 | 采用 main 的 workflow_dispatch 手动 Canary 和当前 GITHUB_REF_NAME；不恢复旧 main 自动 Canary 触发。日常 Dev rolling 构建由 dev.yml 负责。 |
| 5 | `AGENTS.md` | 6 | 保留本分支完整英文知识库、YAGNI、测试价值和英文 Wiki 规则；吸收 main 的提交模块标签、PR 贡献规则、同任务快照调查、外部只读调查简短输出例外。例外不用于本次仓库合并。 |
| 6 | `CLAUDE.md` | 8 | 与 AGENTS.md 保持一致，吸收同一批规则，不恢复旧中文知识库或覆盖本分支的测试/英文维护约束。 |
| 7 | `README.md` | 8 | 保留本分支结构、易读的能力介绍与 Standalone 独立 CLI；吸收 Marketplace 安装、更新渠道以及 Gemini Anti-gravity 条件配置。修正公开更新检查与旧“完全离线”表述的冲突；main 的推广段落/Star History 仅属展示选择，未增加其重复内容。 |
| 8 | `README.zh-CN.md` | 8 | 逐项镜像英文 README 的 Marketplace、Standalone、公开更新检查和 Gemini Anti-gravity 事实；保留本分支中文页面组织。 |
| 9 | `build.gradle` | 1 | 保留本分支 versionName=4.0.5，不降级为 main 3.5.4；吸收 agentVersion=1.0.81。 |
| 10 | `change_log/change_log.html` | 4 | 保留本分支 4.0.0 Standalone 独有条目，拼接 main 累计 3.5.4/3.5.3/3.5.2 历史及原旧版本；英文先合并，中文镜像；未执行版本更新、历史重写或打标签。 |
| 11 | `change_log/change_log_cn.html` | 4 | 保留本分支 4.0.0 Standalone 独有条目，拼接 main 累计 3.5.4/3.5.3/3.5.2 历史及原旧版本；英文先合并，中文镜像；未执行版本更新、历史重写或打标签。 |
| 12 | `change_log/change_log_rc.yaml` | 1 | 保留本分支 4.0.0 Standalone 独有条目，拼接 main 累计 3.5.4/3.5.3/3.5.2 历史及原旧版本；英文先合并，中文镜像；未执行版本更新、历史重写或打标签。 |
| 13 | `change_log/change_log_rc_cn.yaml` | 1 | 保留本分支 4.0.0 Standalone 独有条目，拼接 main 累计 3.5.4/3.5.3/3.5.2 历史及原旧版本；英文先合并，中文镜像；未执行版本更新、历史重写或打标签。 |
| 14 | `docs/ai_knowledge/02_compile_core.md` | MD | 保留旧路径删除，将经代码确认的新增事实合入 docs/ai/02_compile_core.md：同步 HEAD 的刷新顺序/失败回退/成功更新 baseline、过多变更确认开关、已编译待部署文件计数。 |
| 15 | `docs/ai_knowledge/02_compile_databinding.md` | MD | 保留旧路径删除，将经代码确认的新增事实合入 docs/ai/02_compile_databinding.md：显式启用 DataBinding 的模块也须检查 <layout> 根；普通布局走 ViewBinding，生成源在 Kotlin 前发布；Adapter KAPT 排除旧 mapper Java，避免 duplicate class。 |
| 16 | `docs/ai_knowledge/02_compile_resource.md` | MD | 保留旧路径删除，将经代码确认的新增事实合入 docs/ai/02_compile_resource.md：R.java 仅在发现新增资源时重编译，保留其它资源链路。 |
| 17 | `docs/ai_knowledge/02_compile_source.md` | MD | 保留旧路径删除，将经代码确认的新增事实合入 docs/ai/02_compile_source.md：有效 IDE home 不能无条件清空；隔离 Kotlin 编译进程需传递有效 idea.home.path。 |
| 18 | `docs/ai_knowledge/03_runtime_jvmti.md` | MD | 保留旧路径删除，将经代码确认的新增事实合入 docs/ai/03_runtime_jvmti.md：compat flag 跳过所有 framework transforms；APK-root ClassLoader resource 不再有普通 overlay hook，需完整构建安装。 |
| 19 | `docs/ai_knowledge/04_engineering_compat.md` | MD | 保留旧路径删除，将经代码确认的新增事实合入 docs/ai/04_engineering_compat.md：ReadAction module 查询、Chipmunk 旧 API 失败不阻断后续兼容实现、Rabbit 不可变列表。 |
| 20 | `docs/ai_knowledge/04_engineering_ide.md` | MD | 保留旧路径删除，将经代码确认的新增事实合入 docs/ai/04_engineering_ide.md：ASK AI 表单/失败入口/handler finally/输出延后；Run 编译结果状态与递归回退；manager ready mount 和每项目首次 Run 打开窗口。 |
| 21 | `docs/ai_knowledge/05_utilities.md` | MD | 保留旧路径删除，将经代码确认的新增事实合入 docs/ai/05_utilities.md：全局日志与回退根、旧目录迁移/回滚、Snapshot 刷盘/独立副本/manifest/有限 logcat 及公共更新入口。Expect 环境变量传递的局部原因落在 CmdExecutor.kt 的 buildExpectCommand() 英文注释。旧全局部署 cache 描述与当前项目级代码不符，保留当前代码契约；autoUploadFailureLogs Claim 未找到当前实现，保留为历史待核实事实，不成为当前规则。 |
| 22 | `docs/ai_knowledge/08_mcp_design.md` | MD | 保留旧路径删除，将经代码确认的新增事实合入 docs/ai/08_mcp_design.md：Host 提供绝对日志路径，不恢复共享层 IDE Project 依赖；保留两类 Host 路由/协议状态契约。 |
| 23 | `docs/ai_knowledge/09_plugin_runtime_debug.md` | MD | 保留旧路径删除，将经代码确认的新增事实合入 docs/ai/09_plugin_runtime_debug.md：新日志位置与历史别名、事件快照优先、Kotlin home 诊断。 |
| 24 | `docs/ai_knowledge/10_wiki_architecture.md` | MD | 保留旧路径删除，将经代码确认的新增事实合入 docs/ai/10_wiki_architecture.md：手动 Canary/每日 Dev、IndexNow diff/all/keyLocation/已接受不等于已索引/有限 retry；保留 English-first Wiki。 |
| 25 | `docs/ai_knowledge/98_code_map.md` | MD | 保留旧路径删除，将经代码确认的新增事实合入 docs/ai/98_code_map.md：ASK AI、Snapshot、公开版本检查与 Gemini Anti-gravity 当前 owner；不复活旧路径。 |
| 26 | `docs/skills/jugg-android-dev-loop/SKILL.md` | 1 | 保留本分支较新的 1.0.37 / 2026-10-05，避免已发布能力和 CLI 路由说明回退至 main 1.0.31。 |
| 27 | `docs/skills/jugg-android-dev-loop/scripts/py/cmd/cmd_version.py` | 1 | 保留本分支 CLI_VERSION=1.0.24，不回退到 main 1.0.17。 |
| 28 | `docs/wiki/onboarding/installation.md` | 1 | 采用 main 的安装/Release/Dev/手动 Canary 页面组织；兼容范围按当前 reference/compatibility 校正为 Bumblebee 起及 AGP 3.4–9.1、Gradle 5.4.1–9.2.1，未照搬错误的旧最低版本。 |
| 29 | `docs/wiki/zh/onboarding/installation.md` | 1 | 严格镜像英文安装页面及经核验的兼容范围，路由和结构一致。 |
| 30 | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` | 4 | 合并 HostTaskExecutor/PublicUpdate 等 import；保持共享 runtime 初始化，随后 onManagerReady；不复活已迁移的初始化/processFileChanged；公开更新检查接入现有 ideaHotUpdateCoordinator，保留 ASK AI 注入和生命周期清理。 |
| 31 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentCacheStore.kt` | MD | 旧 IDEA owner 删除保持；将 main 的“缓存 APK 文件不存在”检测迁移至 main/.../deploy/cache/JuggDeploymentCacheStore.kt，保留项目锁、磁盘刷新、跨 Runtime 缓存和原序列化契约。 |
| 32 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentService.kt` | MD | 旧 IDEA owner 删除保持；将缺失 APK 检测迁移至共享 service，兼顾内存缓存；保留 executor/owner/generation 失效逻辑，overlay-ID 探测仅对 FileNotFoundException 返回缺失。 |
| 33 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt` | 1 | 同时保留 userActions 和 AskAiInvestigation；按真实调用显式注入，无额外默认/test-only 依赖；接入 ASK AI CompileUiHandler 包装。 |
| 34 | `idea/src/test/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelperTest.kt` | 2 | 保留本分支串行化/宿主/设备目标/回退用例，合并 HEAD、过多变更开关和待部署文件数断言；构造器按共享 owner 适配，confirmBuildChanges 使用当前单参数；回退提示断言匹配 main 新英文文案。 |
| 35 | `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompatDispatcherTest.kt` | 1 | 合并 import 和旧 API fallback 用例，保留本分支中性边界/设备转换断言。 |
| 36 | `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentCacheStoreTest.kt` | MD | 不恢复旧 IDEA 测试；在当前共享 cache owner 增补缺失 APK、其它设备正常读取断言，保留锁/跨 Runtime 原回归，fixture 使用真实临时 APK。 |
| 37 | `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployerInstallTest.kt` | 1 | 保留中性 Deploy API 与原安装/重试/脚本回归；合并 main 的缺失缓存 APK 恢复与其它解析异常不吞噬用例，按 fixture.compat 提供 overlay mismatch 结果，使用实际缓存 APK 文件。 |
| 38 | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/CompileJobManager.kt` | 2 | 采用 IMcpRuntime.compileLatestLogPath，保持 Host 负责日志位置；不合入依赖 runtime.project 的 IDE 专属辅助方法。Job/status/trigger 三处均使用同一契约。 |
| 39 | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/RestartAppMcpToolAction.kt` | 1 | 保留本分支多设备失败集合和结果结构；日志位置改用 runtime.compileLatestLogPath，不重新引入 IDE Project。 |
| 40 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt` | 1 | 保留 CompileEnvironmentSource 宿主抽象；GitFileChangesDetector 成为属性，吸收同步 HEAD 刷新、过多变更开关、待部署文件计数；原回退链路/结果状态继续保留。 |
| 41 | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt` | 1 | 在共享部署 owner 迁移缺失缓存 APK -> 当前 executor.overlayIdMismatch；只捕获 FileNotFoundException，其它异常保留；继续通过现有恢复/重装链路。 |
| 42 | `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt` | 1 | 新过多变更确认开关默认 true，使用本分支 setting(true) JSON 持久化，不恢复旧 PropertiesComponent 委托。 |
| 43 | `main/src/main/java/com/sickworm/intellij/jugg/logger/JuggLogger.kt` | 1 | 保留 instanceKey 与 Standalone resetLatestCompileLog；合入 flushProjectLog/日志路径和旧日志链接入口，IDE 与 CLI 共用 FileLogger。 |
| 44 | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggPathManager.kt` | 1 | 保留当前 runtime 包、项目锁/数据库/配置路径与 Standalone 子目录；合入全局按项目路径 hash 隔离的日志/现场目录及 legacyLogDir，不把部署缓存移到全局。 |
| 45 | `main/src/test/java/com/sickworm/intellij/jugg/ai/mcp/actions/CompileAndDeployMcpToolActionTest.kt` | 2 | 保留 TestMcpRuntime 宿主抽象和现有结果语义，合入 main 的 logPath/状态断言；自定义路径用 IMcpRuntime 委托覆盖，不再 mock IDE Project。 |
| 46 | `main/src/test/java/com/sickworm/intellij/jugg/ai/mcp/actions/CompileOnlyMcpToolActionTest.kt` | 2 | 保留 TestMcpRuntime 宿主抽象和现有结果语义，合入 main 的 logPath/状态断言；自定义路径用 IMcpRuntime 委托覆盖，不再 mock IDE Project。 |
| 47 | `main/src/test/java/com/sickworm/intellij/jugg/ai/mcp/actions/ForceGradleCompileMcpToolActionTest.kt` | 2 | 保留 TestMcpRuntime 宿主抽象和现有结果语义，合入 main 的 logPath/状态断言；自定义路径用 IMcpRuntime 委托覆盖，不再 mock IDE Project。 |
| 48 | `main/src/test/java/com/sickworm/intellij/jugg/ai/mcp/actions/GetCompileStatusMcpToolActionTest.kt` | 2 | 保留 TestMcpRuntime 宿主抽象和现有结果语义，合入 main 的 logPath/状态断言；自定义路径用 IMcpRuntime 委托覆盖，不再 mock IDE Project。 |
| 49 | `main/src/test/java/com/sickworm/intellij/jugg/project/change/GitFileChangesDetectorTest.kt` | 2 | 保留当前 runtime/module imports 和并发用例；合并 main HEAD 同步刷新测试，显式 mock detector 现有四项依赖；临时 Git fixture 不依赖 IDE TestGlobal。 |

## 自动合并后的语义审查

“无文本冲突”不等于通过审查。按 incoming 实现 diff 的行为 owner、迁移路径和调用方检查了以下变化：

| 范围 / 入口 | 风险与结论 | 证据 / 处理 |
|---|---|---|
| ASK AI：`AskAiInvestigation`、Runner、Panel、Snapshot、模板 | 新增类的旧 package import 可自动合入但无法编译；必须按共享 runtime/context owner 接线。快照仅针对最终未取消非 RPC 编译失败；设备/部署失败保持编译成功，递归 fallback 不复用上一轮结果。 | 编译、ASK AI/Runner/RunningTask/Snapshot 回归；现有构造依赖适配，没有生产 test-only seam。 |
| 日志路径：IDE / Standalone / MCP | main 改为全局日志，本分支 Standalone 和 IdeaMcpRuntime 自动合入后仍可能返回旧相对路径。 | 两 Host 均返回自己的绝对 compile_latest 路径，Standalone 注册全局子目录并保留旧别名；MCP job/status/restart 保持原协议。 |
| FileLogger + Standalone 旧别名 | IDE 父目录已是全局链接时，Standalone 子目录解析到自身；原迁移会复制/移动当前目录，丢失现场。 | 新文件系统行为用例先失败（FileNotFoundException），再用 Files.isSameFile 判断已解析别名，避免再次迁移；保留 main 的普通迁移、回滚、冲突保留、clean 后修复。 |
| 缺失 APK cache | main 对旧 IDEA 文件的修复会随 modify/delete 被遗漏；当前共享内存 cache 也可能绕过磁盘检查。 | 迁移到共享 cache/service/deployer；先失败的共享 cache 测试，随后 disk/memory、其它设备不受影响、异常分类和 reinstall/Flow 回归。 |
| Git HEAD / excessive changes / pending count | shared compiler 接口必须保留；同步 HEAD 发布要在输入读取前，失败要触发 Gradle；开关不可落回 IDEA Properties。 | 合并新逻辑到共享 compiler，保留宿主抽象；Git 与 CompileHelper 回归覆盖 HEAD 变化/失败、不改变 HEAD 的正常路径、开关和文件数。 |
| DataBinding / ViewBinding / KAPT / R namespace | 模块启用 DataBinding 不代表普通布局可生成 DataBinding helper；旧 mapper Java 会重复进入 KAPT；共享输出不能把 binding DEX 作为 R namespace clone。 | DataBindingArgs/生成顺序/ViewBinding Flow、RDex 路由测试通过；保留只选 R/R$ DEX 的 main 修复。完整 APK/KAPT 多版本与真机运行不由 mock/编译结果证明。 |
| Kotlin host home / KMP JVM classpath | IDE home 丢失可导致 Kotlin INTERNAL_ERROR；KMP JVM classes 目录须进入编译 classpath。 | 保留有效 home 与隔离进程参数；HostCompat/ProcessRunner、ModuleBuildPathInfo、依赖 classpath 回归。 |
| 部署兼容 / 多设备 / ReadAction / 不可变列表 | main 旧 runtime API 需转换；某版 API 异常不应掩盖原异常或中断后续兼容实现；Rabbit 列表不可直接修改。 | 当前接口和 LegacyDeviceAdapter 的真实边界适配；CompatDispatcher、Install、虚拟 Deploy Flow、IDE 编译通过；保留 suppressed 原因与原多设备结果集合。 |
| 公共更新检查 / 安装 | 无可用内部服务器时走 Marketplace/GitHub，现有热更新 coordinator 不可被旧 downloader 覆盖。 | PublicUpdateChecker/版本比较/进度 UI 测试通过；现有 runtime 下载与更新状态保留。未执行联网安装或 Marketplace 发布。 |
| JVMTI compat startup | main 主动跳过所有 framework transforms，改变 ClassLoader resource 能力范围。 | native policy 检查通过；更新英文知识库及英文/中文 compat-deploy、jugg-runtime、jugg-jvmti-agent，明确这类资源需完整构建安装；没有宣称完成真机 Android 多版本验证。 |
| Gemini CLI / Anti-gravity / hooks | Anti-gravity 存在 config 时才扩展，输入可能 camelCase/JSON 字符串；Stop 输出契约不同。 | installer 与 hooks Python 回归通过；保留本分支更高版本的 CLI/skill。 |
| 打包 / 发布 | main 使用 YAML changeNotes、手动 Canary、每日 Dev、Release Marketplace；Standalone 打包逻辑不可丢失，普通包不可带内部服务器配置。 | buildPlugin、ThirdPartyCompliance、embedded bundle SHA/恢复验证通过；ZIP 4.0.5、Standalone、英文 notes 和无 servers.json 校验。这里只检查配置，未触发远端发布。 |
| Wiki / SEO / IndexNow | onboarding 重组不能下调兼容事实；Pages 部署后 indexing 不能被解释为已收录；manual/all/diff 路由要排除 dev。 | Wiki validator、完整构建/homepage 检查、IndexNow dry-run；英文源与中文镜像对齐，未提交真实索引请求。 |
| repo/Issue/PR 工作流 | 外部只读调查规则不能覆盖本次开发授权；旧 revision 文档 root 不同。 | 英文 AGENTS/CLAUDE/PR 模板与新贡献说明保留；PR plan checker 5 项通过；新增 bounded 文档路径 fallback。 |

## 语言与历史事实逐项审查

incoming 审查命令：`python3 tools/check_english_first_diff.py --base 600af6decc65e970520350d73eb8f11cda3405d7 --head 32fc3b6612eaebb487e4e70fde8015ef443381ec --report /tmp/jugg-main-incoming-language-review.md`。检查 197 个文本文件、报告 51 个新增中文文件；下表逐项给出最终分类、英文落点/例外及未决事实。

| Incoming 文件 | 最终分类 | 英文落点 / 例外 | 未决事实 |
|---|---|---|---|
| `.agents/skills/issue-handler/references/information-collection-template.md` | 中文 launch prompt | `同文件 English launch prompt / 英文说明，中文块为本地化模板` | 无 |
| `.agents/skills/jugg-update-version-changelog/SKILL.md` | 英文当前指南及本地化输入 | `同文件英文正文；新模块分类译成英文，中文触发词和用户 changelog 例子保留` | 无 |
| `AGENTS.md` | 当前维护规则 | `同文件英文规则` | 无 |
| `CLAUDE.md` | 当前维护规则 | `同文件英文规则` | 无 |
| `CONTRIBUTING.zh-CN.md` | 中文本地化 | `CONTRIBUTING.md` | 无 |
| `README.zh-CN.md` | 中文本地化 | `README.md` | 无 |
| `change_log/change_log_cn.html` | 中文本地化 | `change_log/change_log.html` | 无 |
| `change_log/change_log_rc_cn.yaml` | 中文本地化 | `change_log/change_log_rc.yaml` | 无 |
| `docs/ai_knowledge/02_compile_core.md` | 当前知识，迁移 | `docs/ai/02_compile_core.md` | 无 |
| `docs/ai_knowledge/02_compile_databinding.md` | 当前知识，迁移 | `docs/ai/02_compile_databinding.md` | 无 |
| `docs/ai_knowledge/02_compile_resource.md` | 当前知识，迁移 | `docs/ai/02_compile_resource.md` | 无 |
| `docs/ai_knowledge/02_compile_source.md` | 当前知识，迁移 | `docs/ai/02_compile_source.md` | 无 |
| `docs/ai_knowledge/03_runtime_jvmti.md` | 当前知识，迁移 | `docs/ai/03_runtime_jvmti.md` | 无 |
| `docs/ai_knowledge/04_engineering_compat.md` | 当前知识，迁移 | `docs/ai/04_engineering_compat.md` | 无 |
| `docs/ai_knowledge/04_engineering_ide.md` | 当前知识，迁移 | `docs/ai/04_engineering_ide.md` | 无 |
| `docs/ai_knowledge/05_utilities.md` | 当前知识，迁移 | `docs/ai/05_utilities.md`；Expect 局部原因移至 `CmdExecutor.kt:buildExpectCommand()` 英文注释 | 旧全局部署 cache 位置已由当前项目级实现否定；不合入该旧架构事实。`32fc3b661:docs/ai_knowledge/05_utilities.md` 第 75–76 行的自动失败日志上传开关、排除规则与诊断包范围未找到当前实现，保留为历史待核实事实，不作为当前规则 |
| `docs/ai_knowledge/08_mcp_design.md` | 当前知识，迁移 | `docs/ai/08_mcp_design.md` | 无 |
| `docs/ai_knowledge/09_plugin_runtime_debug.md` | 当前知识，迁移 | `docs/ai/09_plugin_runtime_debug.md` | 无 |
| `docs/ai_knowledge/10_wiki_architecture.md` | 当前知识，迁移 | `docs/ai/10_wiki_architecture.md` | 无 |
| `docs/ai_knowledge/98_code_map.md` | 当前知识，迁移 | `docs/ai/98_code_map.md` | 无 |
| `docs/task/2026-09/auto_fallback_too_many_changes_toggle_plan.md` | 历史任务，原文保留 | `本文件新增 English Purpose / Decision / Impact 摘要；当前已确认事实进入对应 docs/ai 主题` | 计划内容不自动视为已经实现 |
| `docs/task/2026-09/gemini_antigravity_hook_compatibility.md` | 历史任务，原文保留 | `本文件新增 English Purpose / Decision / Impact 摘要；当前已确认事实进入对应 docs/ai 主题` | 计划内容不自动视为已经实现 |
| `docs/task/2026-09/git_head_sync_refresh_plan.md` | 历史任务，原文保留 | `本文件新增 English Purpose / Decision / Impact 摘要；当前已确认事实进入对应 docs/ai 主题` | 计划内容不自动视为已经实现 |
| `docs/task/2026-09/global_jugg_log_storage_plan.md` | 历史任务，原文保留 | `本文件新增 English Purpose / Decision / Impact 摘要；当前已确认事实进入对应 docs/ai 主题` | 计划内容不自动视为已经实现 |
| `docs/task/2026-09/jugg_wiki_seo_execution_plan.md` | 历史任务，原文保留 | `本文件新增 English Purpose / Decision / Impact 摘要；当前已确认事实进入对应 docs/ai 主题` | 计划内容不自动视为已经实现 |
| `docs/task/2026-09/public_update_check_plan.md` | 历史任务，原文保留 | `本文件新增 English Purpose / Decision / Impact 摘要；当前已确认事实进入对应 docs/ai 主题` | 计划内容不自动视为已经实现 |
| `docs/task/2026-10/ask-ai-investigation.md` | 历史任务，原文保留 | `本文件新增 English Purpose / Decision / Impact 摘要；当前已确认事实进入对应 docs/ai 主题` | 计划内容不自动视为已经实现 |
| `docs/task/2026-10/ask-ai-triage.md` | 历史任务，原文保留 | `本文件新增 English Purpose / Decision / Impact 摘要；当前已确认事实进入对应 docs/ai 主题` | 计划内容不自动视为已经实现 |
| `docs/task/2026-10/databinding_plain_layout_hot_deploy_crash.md` | 历史任务，原文保留 | `本文件新增 English Purpose / Decision / Impact 摘要；当前已确认事实进入对应 docs/ai 主题` | 计划内容不自动视为已经实现 |
| `docs/wiki/.vitepress/config.mts` | 本地化导航 | `同文件英文导航/对应英文 page；中文导航名称例外` | 无 |
| `docs/wiki/zh/capabilities/compile/gradle-fallback.md` | 中文本地化 | `docs/wiki/capabilities/compile/gradle-fallback.md` | 无 |
| `docs/wiki/zh/concepts/how-jugg-works.md` | 中文本地化 | `docs/wiki/concepts/how-jugg-works.md` | 无 |
| `docs/wiki/zh/guide/advanced-options.md` | 中文本地化 | `docs/wiki/guide/advanced-options.md` | 无 |
| `docs/wiki/zh/guide/cli.md` | 中文本地化 | `docs/wiki/guide/cli.md` | 无 |
| `docs/wiki/zh/guide/compile.md` | 中文本地化 | `docs/wiki/guide/compile.md` | 无 |
| `docs/wiki/zh/guide/mcp.md` | 中文本地化 | `docs/wiki/guide/mcp.md` | 无 |
| `docs/wiki/zh/guide/remote-gradle.md` | 中文本地化 | `docs/wiki/guide/remote-gradle.md` | 无 |
| `docs/wiki/zh/guide/run.md` | 中文本地化 | `docs/wiki/guide/run.md` | 无 |
| `docs/wiki/zh/index.md` | 中文本地化 | `docs/wiki/index.md` | 无 |
| `docs/wiki/zh/onboarding/first-run.md` | 中文本地化 | `docs/wiki/onboarding/first-run.md` | 无 |
| `docs/wiki/zh/onboarding/index.md` | 中文本地化 | `docs/wiki/onboarding/index.md` | 无 |
| `docs/wiki/zh/onboarding/installation.md` | 中文本地化 | `docs/wiki/onboarding/installation.md` | 无 |
| `docs/wiki/zh/reference/compatibility.md` | 中文本地化 | `docs/wiki/reference/compatibility.md` | 无 |
| `docs/wiki/zh/reference/index.md` | 中文本地化 | `docs/wiki/reference/index.md` | 无 |
| `docs/wiki/zh/reference/log-files.md` | 中文本地化 | `docs/wiki/reference/log-files.md` | 无 |
| `docs/wiki/zh/troubleshooting/changes-not-applied.md` | 中文本地化 | `docs/wiki/troubleshooting/changes-not-applied.md` | 无 |
| `docs/wiki/zh/troubleshooting/compile-failed.md` | 中文本地化 | `docs/wiki/troubleshooting/compile-failed.md` | 无 |
| `docs/wiki/zh/troubleshooting/runtime-crash.md` | 中文本地化 | `docs/wiki/troubleshooting/runtime-crash.md` | 无 |
| `docs/wiki/zh/troubleshooting/supported-packaging.md` | 中文本地化 | `docs/wiki/troubleshooting/supported-packaging.md` | 无 |
| `main/src/main/resources/diagnostics/ask-ai-zh.md` | 中文提示模板 | `main/src/main/resources/diagnostics/ask-ai-en.md` | 无 |
| `main/src/test/java/com/sickworm/intellij/jugg/diagnostics/InvestigationSnapshotBuilderTest.kt` | 语义测试输入 | `中文用户输入/语言选择断言例外；非语义注释无新增中文` | 无 |


候选合并树复查（起点为上述合并前 HEAD）：207 个文本文件、41 个新增中文文件。初始 51 项中仍出现的项继续适用上表分类；新增的四项如下。没有新中文当前维护规则留在 AGENTS/CLAUDE 或 docs/ai。

| 最终复查新增项 | 分类 | 英文落点 / 例外 | 未决事实 |
|---|---|---|---|
| `docs/task/2026-10/2026-10-14-main-merge-review.md` | 指定中文 reviewer 的任务记录 | 本文件 English Purpose / Decision / Impact 摘要 | 运行边界在验证段明确 |
| `docs/wiki/zh/concepts/compat-deploy.md` | 中文本地化 | `docs/wiki/concepts/compat-deploy.md` | 无 |
| `docs/wiki/zh/concepts/jugg-runtime.md` | 中文本地化 | `docs/wiki/concepts/jugg-runtime.md` | 无 |
| `docs/wiki/zh/concepts/jugg-jvmti-agent.md` | 中文本地化 | `docs/wiki/concepts/jugg-jvmti-agent.md` | 无 |

## 验证证据与边界

- 测试价值：增加的两类断言是“缺失 APK 触发恢复、其它设备/异常保持正常”和“父目录别名下现场文件不丢失”，均为独立可观察稳定行为，使用现有 L1/L2 owner；没有修改生产接口以便测试。其余为维护或运行已有回归。
- 失败证据：缺失磁盘 APK 测试在共享 owner 上先出现 AssertionError（`cache-before-2.log`，4 项中 1 项失败）；Standalone alias 用例先出现现场文件 FileNotFoundException（`log-alias-before-2.log`，1 项失败）。修复后针对性复查通过。
- 工具链：默认 JDK 21 与 Gradle 7.3.3 的 class major 65 不兼容，使用已有 Corretto 17。最初部分 main 测试缺 ANDROID_HOME 导致 TestGlobal 初始化失败，明确设置 `/Users/wormchen/Library/Android/sdk` 后通过。cmd_line 原测试 JVM 的历史 `-XX:MaxPermSize` 参数在 JDK17 无效；仅通过 `/tmp/jugg-main-merge-audit/test-jvm.gradle` 过滤该参数，不改无关构建配置。
- incoming 新测试的旧 import/IDE device stub/调用方法不能直接适配当前架构；修正 owner 路径，Controller 通过 LegacyDeviceAdapter 测试真实 IDE 边界，RunningTask 使用 getTargetDevices(null)，匹配该分支已支持的设备选择契约。main 改写回退提示措辞，更新旧文案断言，提示条件仍然保留。
- `:idea:compileKotlin :cmd_line:compileKotlin` 通过；IDE 相关测试批次 163 项初始 7 项失败已解释并修复，最终包括失败 owner 与虚拟 Deploy Flow、R namespace 的定向批次 127 项全部通过。
- main 初批 442 项中 6 项因缺 SDK 环境失败；补环境后相关 owner 全部通过。最终针对日志/cache/ViewBinding/Kotlin 的 40 项通过；合并各 owner 最新结果，main 覆盖 446 项（含新增日志用例及已有 service 3 项），无剩余失败。
- Standalone runtime/installer/environment/device 23 项通过；native policy 10 项通过。native policy 是静态约束证据，不证明设备上 framework hook 行为。
- 部署/编译 orchestration 的替代 Flow 证据：RunningTask 从 compile 到 deploy/fallback 的结果回归，以及 JuggDeployerHelperDeployFlowTest 在虚拟设备上的 apply/recover/reinstall/history 行为。没有运行会 checkout/重写 demo 现场的 TopLevelFlowWithGitTest；没有将这些结果称为真机 L3。真机、完整 DataBinding/KAPT APK 矩阵、远程 Standalone Gradle 与各 Android Studio 版本运行仍为未执行边界。
- Python hooks 81 项、PR plan 5 项通过。Wiki validator、最终 VitePress build/homepage、IndexNow dry-run 通过。
- 最终 `:idea:buildPlugin :idea:verifyThirdPartyCompliance` 通过，同时 verifyEmbeddedStandaloneBundle 验证共享 JAR 省略后的可恢复性与 SHA。ZIP 为 `jugg-4.0.5-develop_4.0-SNAPSHOT.zip`，包含匹配 Standalone bundle，plugin.xml 版本/Standalone changeNotes 正确，各内嵌 JAR 和 ZIP 未包含 servers.json。
- 临时原始输出位于 `/tmp/jugg-main-merge-audit/`，属于机器本地证据；本记录、两父提交与保留的测试是持久审查依据。

## 最终一致性

- 当前知识库保持单一英文 `docs/ai/`，旧中文 modify/delete 内容按上表迁移，独有 Standalone、项目锁、项目级 deployment DB 和运行契约保留。
- 用户受影响页面按英文优先同步中文；新增非英文历史任务记录补齐英文摘要，不创建第二份历史全文。
- 完成后对初始 HEAD 与候选/最终 merge commit 重跑语言审查；本记录的中文正文属于指定中文 reviewer 的任务记录例外，英文摘要已提供。
- 最终状态、冲突标记、diff whitespace、两父 ancestry 和分支检查记录于任务最终回复。只提交此任务产生的合并与适配，不推送。


## 主要复查命令

下面均为定向测试，不运行无过滤的 `:main:test` / `:idea:test`。

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME=/Users/wormchen/Library/Android/sdk ./gradlew :main:test --tests '*FileLoggerTest' --tests '*JuggDeploymentCacheStoreTest' --tests '*JuggDeploymentServiceTest' --tests '*JuggPathManagerTest' --tests '*ViewBindingCompileFlowTest' --tests '*KotlinCompilerHostCompatTest'
JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME=/Users/wormchen/Library/Android/sdk ./gradlew :idea:test --tests '*JuggRunningTaskTest' --tests '*JuggDeployerInstallTest' --tests '*JuggControlPanelControllerTest' --tests '*JuggCompileHelperTest' --tests '*JuggDeployerHelperDeployFlowTest' --tests '*RDexForSubmoduleCompilerTest'
JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME=/Users/wormchen/Library/Android/sdk ./gradlew :idea:buildPlugin :idea:verifyThirdPartyCompliance
python3 .agents/skills/wiki-writer/scripts/validate_wiki.py
npm --prefix docs/wiki run check:homepage
INDEXNOW_ALL=true node docs/wiki/scripts/submit-indexnow.mjs --dry-run
python3 -m unittest discover -s docs/skills/hooks/tests -v
python3 -m unittest tools/test_check_pr_plan.py -v
```

Standalone 23 项验证使用上文临时 JVM 参数过滤脚本以及 `--tests '*StandaloneRuntimeTest' --tests '*StandaloneRuntimeInstallerTest' --tests '*StandaloneCompileEnvironmentSourceTest' --tests '*StandaloneDeployTargetManagerTest'`；native 为 `:jvmti_agent:testDebugUnitTest --tests '*InstrumenterSourcePolicyTest'`。


## 委派审查与修复复审（2026-10-14）

用户在初始 merge `cf93909b0faa1ccfec6c4287ea737f30a7ee73db` 完成后明确要求“委派 subagent review merge，如发现问题则修复并重新review”。三名独立 reviewer 只读检查双方 diff 和合并实现，未修改文件、切换分支或运行会重写 demo 的 Flow。审查范围同时包含冲突解决与自动合并；已有测试通过不作为功能保留的独立证明。

| Reviewer | 范围 | 第一轮结论 | 第二轮结论 |
|---|---|---|---|
| `review_runtime` | 全局/Standalone 日志、cache 恢复、MCP Host 契约、ASK AI/IDE wiring、公共更新 | R1：Standalone 先、IDE 后迁移会删除真实日志（P1） | 用当前真实编译产物独立验证两种启动顺序；日志保留，正常迁移/回滚不变；未发现剩余明确问题 |
| `review_compile_deploy` | Git HEAD、shared compiler、too-many fallback、DataBinding/KAPT/R DEX、Kotlin/KMP/native、部署恢复 | R2：遗漏旧过多变更开关的 JSON 迁移（P2） | 旧 key、默认值和现有 JSON 优先规则正确；未发现剩余明确编译/部署遗漏 |
| `review_docs_build` | 版本/更新器、包、CI/release、changelog、知识库迁移和英中 Wiki | R3：新 Skill 内容沿用旧版本导致更新被跳过（P2）；另列 R4 为 incoming main 既有文档错误 | 检查当前 ZIP 中 Skill 及五个 hooks 与源文件一致、版本正确，双语页面一致；未发现新的明确问题 |

### Findings 与解决方式

| ID | 触发与证据 | 最小修复 | 回归/正常路径与复审 |
|---|---|---|---|
| R1 / P1 | Standalone 在真实旧 `build/jugg/log` 下建立 child symlink，IDE 随后迁移 parent；`File.deleteRecursively()` 清理备份时沿 child link 删除全局日志。真实 FileLogger 在完全临时目录复现：迁移前 incident 存在，之后不存在；现有 owner 的新增断言先失败为 FileNotFoundException。 | 只替换迁移备份清理：本地 `deleteLegacyLogPath` 以 `NOFOLLOW_LINKS` 判断目录，删除 symlink 本身；保留复制、提交、回滚和清理失败的诊断，失败原因不丢弃。 | `FileLoggerTest` 18 项通过，含两种启动顺序、真实旧目录、同名文件、回滚和历史备份恢复。runtime reviewer 用当前编译产物再次独立复现两种顺序，均保留日志。 |
| R2 / P2 | main 3.5.4 将 false 写至 `jugg.isConfirmFallbackWhenTooManyChanges`；合并后 setting(true) 改为共享 JSON，但自动合入的 IDEA migration 列表缺此项，升级后用户偏好丢失并重新弹窗。实际迁移成功用例扩展后先发生 AssertionError。 | 在现有 `readLegacyJuggSettings` 加同名 `addBoolean` 映射；不重置完成标志、不改变公共接口或其它迁移流程。 | 实际 `JuggSettings` 读取确认旧 false 保留；已有 generic `mergeMissing` 回归保护 JSON 已有值。无旧键继续默认 true。compile reviewer 复核调用链和 JSON 优先规则通过。 |
| R3 / P2 | 初始合并保留 develop 1.0.37，但 Skill 正文和 hooks 内容变化。更新器在 bundled == local 时返回；将 existing outdated test 的用户版本改为真实旧 develop 1.0.37 后，缺少应更新的 CLI 文件而失败。 | Skill 提升为 `1.0.38 / 2026-10-14`；沿用现有更新分支。CLI 脚本相对 develop 未变，CLI_VERSION 继续 1.0.24，插件版本继续 4.0.5。 | AutoUpdater 的旧版本更新及同版本跳过回归通过；docs/build reviewer 读取实际插件 ZIP，Skill 1.0.38、正文和五个 hooks 脚本与源码逐字一致。初始合并表的“保留 1.0.37”是初始决策，本次复审以 1.0.38 修正。 |
| R4 / incoming main 既有文档错误 | first-run 英中页用四条实现中不存在的日志作为成功判据，声称仅首次需要完整 Gradle；表格把新增方法当作无需重启，并将结构变化写成仅重启 Activity。它们随 main 原样带入，不是冲突解决独造。 | 英文先修，中文同步：使用当前真实 Gradle BUILD_AND_INSTALL SUCCESSFUL / App launched，区分构建安装启动成功与后台基线准备；说明后续 full build 的条件、Hot Fix 与进程重启、资源刷新和不固定的耗时。路由/title/H1 保留。 | 对照 JuggRunningTask 的结果日志和部署行为；生产 Wiki build、镜像/链接 validator 通过；docs reviewer 二轮确认双语段落/表格/边界一致。纯文案不加单元测试。 |

### 本轮验证与持久化

- 新增/扩展行为断言通过价值门槛：日志文件不能因迁移消失、旧偏好不能因存储迁移重置、已安装旧版本应收到新内容，均为稳定且可判定的用户结果，使用已有 owner，没有生产 test-only seam。
- 修复前证据：`/tmp/jugg-merge-subreview/settings-before.log`（4 项中 1 项失败）、`logs-before.log`（1 项失败）、`skill-before.log`（1 项失败）。这些本地输出不保证跨机器长期存在；回归测试、此记录与提交为持久依据。
- 修复后 `:main:test --tests '*FileLoggerTest' --tests '*JsonRuntimeSettingsRepositoryTest'`：22/22 通过。
- 修复后 `:idea:test --tests '*IdeaRuntimeSettingsMigrationTest' --tests '*JuggCliAutoUpdaterTest'`：15/15 通过。
- `:idea:buildPlugin :idea:verifyThirdPartyCompliance` 通过，包含 `verifyEmbeddedStandaloneBundle`；reviewer 独立核对实际 ZIP 的 Skill/hooks 内容。
- `python3 .agents/skills/wiki-writer/scripts/validate_wiki.py --wiki-root docs/wiki` 和 `npm --prefix docs/wiki run build` 通过；最终 HTML 复核两语言的新成功日志与后续构建边界。
- 额外阅读依据：`08_cli_tools_list.md` §3.7、wiki-writer skill、first-run 两语言原文；reviewer 按范围阅读 `02_compile_core/source/databinding`、`03_deploy_core`、`03_runtime_jvmti`、`04_engineering_compat/ide`、`05_utilities`、`08_mcp_design`、`10_wiki_architecture/authoring`、`06_testing` 与 `97_maintenance_manual`。入口文档与实现 owner 延续本记录已有清单。
- 知识库现有“旧设置填缺失 JSON、旧日志可安全迁移”的契约已经表达目标，本轮修复实现使其成立；局部不跟随链接的原因留在英文代码注释，不新增重复知识条目。受影响用户事实在英中 Wiki 同步。
- 第二轮结论只覆盖已核验路径：不声称完成真机、各 Android Studio 版本、远程 Standalone Gradle 或实际更新下载安装验证。未增加这些未执行场景的成功宣称。
- 修复分开提交，初始 merge 和父提交保留；最终 diff/工作区与语言检查在提交后再次确认。未 push 或创建 PR。

## 知识库复审收尾

- 补回 `GitFileChangesDetector` 的源码路径及 `refreshChangedFilesIfHeadChanged()` 方法入口，保留同步刷新与成功后提交 HEAD baseline 的现有契约。
- 将 Expect 环境变量传递的局部原因从 `04_engineering_compat.md` 移至 `CmdExecutor.kt:buildExpectCommand()` 旁的英文注释；移除的事实有明确落点，不扩展知识库中的单文件实现细节。
- 自动失败日志上传的未核实描述实际来自 incoming `32fc3b661:docs/ai_knowledge/05_utilities.md` 第 75–76 行，已修正冲突表和语言审查表的归属；仍保留为历史待核实事实，不提升为当前产品能力。
- 已核对方法定义、真实调用方及 incoming 原文，`git diff --check` 通过。源码除新增注释外与复审前完全一致，未改变行为；按 `06_testing.md` 的文档维护规则不新增测试、不重复运行构建。维护手册与入口路由保持不变，无受影响的用户 Wiki 页面。
- 按用户要求将本轮知识库收尾 amend 到当前 merge commit，保留两个父提交和原提交说明；最终提交后复查语言差异与工作区状态。
