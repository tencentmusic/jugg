# 内网服务器默认配置继承

## 已确认范围

用户已确认：运行过内网包且当前没有显式 Custom Server 的 IDE，升级公开包后继续使用此前内网包的服务器列表。显式 Custom Server 优先；清空 Custom Server 恢复默认时回到继承列表。继承配置使用 IDE 全局存储，不依赖项目 build 目录，也适用于其他项目。

- `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt`：新增内部持久化字段保存完整内置服务器列表，避免被后台 defaultSettings 反射修改。
- `main/src/main/java/com/sickworm/intellij/jugg/server/JuggServerChooser.kt`：记住有效内置列表，公开包缺少内置列表时读取保存值；保留项目下发规则、显式 Custom Server、自动选服和故障切换的现有优先级。
- `idea/src/test/java/com/sickworm/intellij/jugg/server/JuggServerChooserTest.kt`：在现有 behavior owner 中覆盖跨包继承、用户设置优先、全新公开安装、恢复默认、项目配置优先和缓存损坏。
- `docs/ai_knowledge/05_utilities.md`：同步继承机制及历史边界。
- `docs/wiki/{zh/,}guide/jugg-backend/{index,diagnostics,self-hosting}.md`：以中文为基准同步三组中英文页面，修正“公开包不会默认连接后台”的绝对表述。

不新增生产类，不修改后台协议和 defaultSettings 机制，不改公开包的资源打包规则，不推送或创建 PR。

## 实现约定

默认列表保持在 `hasAvailableServer()` 与 `updateServer()` 原有默认规则读取点按需加载，不在选服器对象创建时初始化，也不额外缓存快照。优先取当前包内有效列表，同时更新全局保存值；当前包没有有效列表时读取保存值。项目配置显式提供的 `servers` 仍优先，空列表仍按现有语义处理。Custom Server 的 `serverExpireTimeMill=-1` 标记保持不变，继承列表不冒充用户设置。保存/读取失败只影响继承，保留当前内置列表和其他有效功能，记录失败原因。

必须先运行一次包含此能力的内网包才能记住完整列表。已经只剩公开包且没有列表的旧安装，不能仅凭历史 serverUrl 推断内网身份。继承后继续按既有后台规则上报事件、检查更新和选择诊断上传目标。

## 验证策略

测试价值门禁通过：升级恢复与配置优先级是稳定且可观察的用户行为，归属已有 `JuggServerChooserTest`，层级 L2。通过测试侧隔离资源的 ClassLoader 模拟带/不带内置列表的两个插件版本，共享真实的测试设置存储；不在生产代码引入测试 seam，不反射调用私有实现。

1. 先新增行为断言并运行定向测试，取得升级后丢失后台的失败证据。
2. 实现后执行 `./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.server.JuggServerChooserTest'` 及相关默认设置回归、`:idea:compileKotlin`。
3. Wiki 执行语言镜像/链接检查、production build 和生成 HTML 内容检查。
4. 执行 `git diff --check`，检查英文注释和日志格式；委派只读实现审查并处理有效问题。
5. 仅提交本次改动，核对 develop/3.6 和原 main 工作区状态。

## 阅读依据

- 仓库规则与技能：`AGENTS.md`、`.agents/skills/feature-development/SKILL.md`、`.agents/skills/wiki-writer/SKILL.md`。
- 知识库：`docs/ai_knowledge/00_overview.md`、`99_index.md`、`98_code_map.md`、`04_engineering_ide.md`、`05_utilities.md`、`06_testing.md`、`10_wiki_authoring.md`、`10_wiki_architecture.md`。
- 历史方案：`docs/task/2026-09/project_default_settings_plan.md`；对照 `c8d13814` 的默认设置下发能力和现有 `ProjectDefaultSettingsApplier` 实现。
- 用户说明：中英文后台首页、诊断、自建后台、插件分发页面，以及安装和配置入口；本次只修正因继承行为失准的三组后台页面。
- 行为证据：`JuggSettings` 持久化委托、`JuggServerChooser`、`JuggServer`、`ServerRule`、默认设置应用器及其既有定向测试；确认公开包和内部包的资源打包差异。

## 执行结果

- 先运行新增用例取得失败证据：选服器 13 个用例中 5 个失败，分别覆盖公开包升级继承、清空 Custom Server 恢复、刷新内置列表、项目列表不覆盖继承值和损坏缓存恢复；生产代码修改后全部通过。
- 最终定向验证共 25 个用例通过：`JuggServerChooserTest` 13 个、`ProjectDefaultSettingsApplierTest` 11 个、`JuggServerTest` 1 个；同时通过 `:idea:compileKotlin`。损坏缓存包含非法 JSON、错误根结构、null 项、缺失和空 URL。
- 标准测试命令先在既有 `:jvmti_agent:buildInstrumentJar` 的 D8 阶段因 `ViewHierarchyServer$1.class` 触发空指针而中断，尚未进入测试。确认已有完整 Agent bundle 后，改用 `./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.server.JuggServerChooserTest' --tests 'com.sickworm.intellij.jugg.ide.ui.ProjectDefaultSettingsApplierTest' --tests 'com.sickworm.intellij.jugg.server.JuggServerTest' :idea:compileKotlin -x :jvmti_agent:buildAgentBundle --console=plain` 完成验证，未改 Agent 构建逻辑。本次没有验证重新打包或真实 IDE 安装升级。
- Wiki 语言镜像/链接校验通过；取消 `JUGG_WIKI_DEV` 后的 production build 通过，逐页确认六个修改页面的 HTML 含有新说明且未生成开发专用页面。
- 实现与已确认范围一致：新增一个内部全局设置字段，在现有选服器中保存/恢复完整规则，未新增生产类型或测试专用入口。知识库与三组中英文 Wiki 已同步。
- 独立只读实现审查完成，未发现需要修改的有效问题。真实 IDE 重启后的磁盘持久化和实际安装升级未验证；本轮验证基于共享 IDE 测试设置及两种包资源模拟。

## 初始化时机修正（2026-10-18）

- 用户指出 `defaultServerRules` 的属性初始化将原有按需读取前置到了构造阶段，要求恢复原时机。对照 `8f62ac6a7` 的父提交，原来只有 `hasAvailableServer()` 与 `updateServer()` 的 Elvis 表达式需要默认规则时才读取。
- 新增 L2 回归 `JuggServerChooserTest#bundled defaults are remembered only when default rules are needed`：先记住内网列表，再创建带新列表的选服器并仅提供项目规则，公开包应仍沿用原列表；真正请求默认列表后，后续公开包才沿用新列表。断言公开选服结果，保护持久化时序，不绑定私有字段或新增测试入口。
- 修复前 14 个选服器用例中仅该新增用例失败：期望 `https://internal.example.com`，实际得到提前保存的 `https://replacement.example.com`。删除提前初始化的字段，两个原读取点直接调用 `loadDefaultServers()`，保留短路条件及内网继承逻辑。
- 本轮依据：`AGENTS.md`、知识库 `00_overview.md`、`99_index.md`、`98_code_map.md`、`05_utilities.md`、`06_testing.md`、本方案及当前/历史选服器实现。用户操作和配置结果未改变，Wiki 现有说明仍适用；仅更新知识库中的初始化时机描述。
- 最终 26 个定向用例通过：选服器 14 个、默认设置应用器 11 个、服务器 1 个，`:idea:compileKotlin` 与 `git diff --check` 通过。标准命令再次被既有 Agent D8 错误阻断，确认现有 bundle 完整后使用前述 `-x :jvmti_agent:buildAgentBundle` 方式验证，未改构建逻辑。

## 来源日志补充（2026-10-18）

- 按用户要求为默认服务器 JSON 增加 debug 来源日志：包内 `config/servers.json` 及规则数量、IDE 全局继承配置、无可用默认 JSON。沿用现有读取与异常路径，不记录 JSON 正文，不改变初始化时机。
- 普通日志文本不作为稳定业务契约，不新增文案断言；复用 `JuggServerChooserTest` 验证选服及初始化时序，并执行定向编译与 diff 日志格式检查。
- 同步知识库的日志说明；Wiki 操作与结果不受影响。验证后 amend 到当前包含继承能力与时机修正的提交，不推送。
- 验证结果：`JuggServerChooserTest` 14 个用例及 `:idea:compileKotlin` 通过，沿用已确认的 Agent bundle 跳过方式；`git diff --check` 与本次日志格式检查通过。
