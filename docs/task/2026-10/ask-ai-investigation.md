**Purpose:** Track local ASK AI evidence preservation.

**Decision:** Save independent incident files before presenting a localized prompt.

**Impact:** Adds Panel and compile-failure investigation entries without automatic upload.

# ASK AI 自助调查与现场保全

## 调查指南收敛与现场采集拆分

2026-10-14：按用户讨论后的方向调整第一版。所有入口先建立 Jugg 工程认知，再结合现场调查，不再以前置错误分类决定是否读取知识库或结束排查。ASK AI 中英文模板仅交接问题、语言、现场路径、时间和源码获取授权，环境与采集失败项继续保存在 manifest。保留用户对 Jugg 的“Android 增量编译构建方案”定位，对应英文介绍收进共用 guide。

- `local-agent-investigation-guide.md` 统一前置知识、证据分析、版本核对、简洁回复和按需 Issue 交接。先读 AGENTS、00/99/98，再进入 09 和相关专题；症状表只推荐高价值材料，不限定原因或要求提前停止。
- 新增同目录 `local-agent-evidence-collection.md`。没有快照时定位已有现场；ASK AI 直接使用 manifest 中的副本，仅关键证据缺失时按需读取采集章节。采集不执行构建、复现或设备命令，也不要求先打包所有产物。
- 非 ASK AI 的中英文启动模板同样明确独立官方源码副本的获取授权，避免 guide 默认禁止与入口例外反复解释。已有用户仓库保持只读。
- 回复默认只提供原因、关键依据和建议，必要时补未解决项；反证、行为 owner 等作为调查检查，不强制逐项汇报。已有 Issue 提供补充内容，不引导重复建单；新 Issue 继续保留预填写链接与按字段完整内容。
- AGENTS/CLAUDE 补充外部只读现场调查的简洁回复适用范围，并统一优先读取历史现场日志；本仓库开发维护清单保持原要求。
- 更新 04/05 知识库，以及三组中英文排查 Wiki 中失准的分流和采集失败项说明。保留入口、路由、标题、现场格式和采集实现。

验证策略：现有 `InvestigationSnapshotBuilderTest`（L1）改前 8 项通过；保留独立副本、多 APK 归属、局部失败、语言选择、保存一致性和单次替换，更新 prompt→manifest/guide 的交接断言，不用固定长篇措辞证明 Agent 调查质量。执行定向回归、双语与链接静态检查、Wiki production build 和生成 HTML 核查。模板文案效果采用内容审阅；尚不声称真实 Agent 的根因准确率或回复长度已得到验证。指南需合入 main 后远程入口才会读取新版，本轮不推送。


验证结果：JDK 17 的 `InvestigationSnapshotBuilderTest` 8 项通过，无失败或跳过；中英文模板各由 93 行缩到 24 行。指南、采集文件、启动模板的官方 Raw 链接映射到本地文件均存在，双语占位符一致。Wiki 镜像/相对链接检查、production build 与 6 个生成页面的更新内容检查通过；`git diff --check` 通过。未改动生产 Kotlin 或采集行为，无需扩大到 IDE 编译或 L3；尚未用真实 Agent 案例验证调查质量与输出长度。

## 预览计数展示调整

2026-10-14：用户要求移除复制弹窗的 `Missing/failed collection items` 行，仅打印日志。删除 AskAiDialog 底部计数容器，保留复制成功反馈；SnapshotBuilder 保存完成后以 DEBUG 记录计数和现场目录。manifest、prompt 和 Snapshot 数据契约保持不变。

失败证据为当前预览始终显示计数标签。此变更为简单 UI 展示和普通日志调整，不增加绑定 Swing 结构或日志字面值的自动化测试；复用 SnapshotBuilder 定向回归，执行 idea 编译与实际组件渲染。同步 IDE 知识库；Wiki 未说明该计数行，其余用户操作不变，无需调整。原工作区 HEAD 已包含用户的 3.5.4 版本提交，本轮单独提交，不重写该提交。

验证结果：JDK 17 `:idea:compileKotlin` 与 SnapshotBuilder 8 项定向回归通过，无失败或跳过。仓库外已有 Java harness 使用本次编译的实际 AskAiDialog 生成预览图，目视确认底部计数行已移除、目录和 prompt 布局正常；复制成功反馈代码保留。git diff --check 和新增日志格式检查通过。此组件渲染不替代真实 IDE 中的剪贴板交互。

## 已批准的职责收敛与日志调整

2026-10-13：用户批准落地并 squash。新增 IDE 侧 `diagnostics/AskAiInvestigation.kt`，承接问题表单、环境/设备/产物采集、失败快照与 console 入口；编译 UI decorator 同文件内聚。`JuggManager` 仅组装并委托入口，`JuggConfigurationRunner` 仅包装本轮 handler，删除 Controller 的失败保存中转。现有初始化过期清理调度保持不变。

`RunLogCollector` 恢复原有 INFO/WARN 消息摘要，不向 MCP 返回额外异常栈。完整栈由现有 FileLogger 负责；复制快照前通过 `JuggLogger.flushProjectLog()` 等待现有异步 writer 排空，不在 JuggLogger 全局锁或 DeployFileManager monitor 内等待。flush 异常记为单项采集失败，其余现场继续保存。

已有回归 owner 为 ConfigurationRunner（显示条件/完成回调）、SnapshotBuilder（独立副本/模板）、RunningTask（编译结果）和 ControlPanel（入口）。把 Ask AI 的 handler 回归移至新 owner，补充真实日志文件中最终异常栈的保存验证，保留 MCP 摘要契约回归；不新增测试 seam。执行定向 L1/L2、idea 编译和已有 L3 安装启动 Flow；同步 04/05/98 知识库。此次内部职责和日志时序调整不改变用户操作，Wiki 无需修改。最终只读复审后 amend 当前 ASK AI feature 提交，保留 cf83c3216 及更早历史。

## 已批准的目标

2026-10-13：用户批准方案并要求实施。编译失败的 Task Output 末尾新增 ASK AI；Running Panel 的 Jugg Plugin 分组新增 ASK AI，填写问题类型和描述后采集现场并生成 prompt。覆盖编译失败、运行时崩溃、改动不生效及其他问题。

Prompt 明确要求读取 Jugg 调查指南、AGENTS.md、知识库和实际源码，并核对现场版本。指南默认禁止 clone、fetch 和仓库缓存写入，除非用户显式要求；ASK AI prompt 显式要求在独立调查目录 clone 官方仓库，必要时在该调查副本 fetch。已有用户仓库保持只读，应用修改、构建、复现和设备命令仍不在 Agent 调查授权内。

## 交互与证据

- 编译最终失败且未取消时固定本次日志、失败时间和上下文，再显示可点击链接；成功、取消、部署失败、回退成功不显示。点击只预览已保存 prompt，不采集下一轮状态。
- Panel 先收集类型、描述和设备，再后台保存现场，最后展示 prompt；预览可复制并显示成功反馈。
- 保存目录：全局 Jugg root 下按项目隔离的 `investigations/<project>/<id>`；实际复制文件，避免 Gradle clean 或 Clear Jugg Build 删除现场。按采集时间保留 30 天，复用 ExpiredArtifactCleaner。
- 保存 Jugg 日志、按设备区分的带时间戳 logcat（包含 crash buffer）、环境版本、项目模型、编译基线、部署历史、改动文件清单、APK/split APK、已部署/待部署增量产物、现存兼容资源 APK 和 mapping。
- 文件清单保存来源、角色、目标 APK、大小和 SHA-256，防止多 APK 同名文件混淆。已部署记录仅表示本地记录，不能证明设备实际加载。
- 采集不运行构建、不重启/重装、不清除设备日志；单项失败记录原因并保留其余结果。根目录或清单写入失败明确失败。Panel 采集复用 TaskRunnerManager 初始化阻塞标记，正在运行的 Run 要求结束后重试；产物复制持有 DeployFileManager monitor。外部构建仍可能改写来源，复制前后检查大小及 mtime，发现变化则明确记录该项失败。
- 本地目录供用户自己的 Agent 分析，不自动上传或提交 Issue。

## 最终输出模板

```markdown
## 原因

[已确认 / 推断 / 没有确切证据] 结论。
依据：关键日志、产物或源码位置及适用版本。
证据不足时指出缺失项，不把猜测写成根因；解释竞争假设和反证结果。

## 建议

优先操作：[修改具体文件或配置 / 降级 Gradle 编译一次 / 采集具体证据 / 提交 Issue]。
说明操作步骤、预期结果及判断是否解决的方法。

## 如果仍然出现

说明具体补采内容，或建议提交 Issue。

## Issue 草稿与跳转（仅建议提交 Issue 时）

标题、问题现象、环境与版本、复现步骤、预期与实际结果、已有对照结果、
关键日志与产物、调查结论及尚未确认的部分。
```

## Issue 预填写交接补充

2026-10-13：用户要求不能自行解决时，Agent 提供一键跳转和自动填写的 Issue 内容。保留现有 ASK AI 入口，仅补充生成 prompt 的交付要求：对照当前仓库 Bug Issue Form，使用中文 `01_bug_report_zh.yml`（英文使用 `04_bug_report_en.yml`），按 `description`、`diagnostic_material` 等字段 ID 填写，生成 URL 编码后的可点击 `issues/new` 链接。不能只用适用于普通 Markdown Issue 的 body 参数。

Issue 内容包括已知事实、环境、复现/对照、关键错误、调查结论和未知项；Report ID 仅使用现存实际值，本地路径不视为维护者可访问的附件。URL 只包含可公开摘要，长内容保留按字段可复制全文；过长或预填写失效时回退模板链接和复制内容。下拉框未预填时列出对应选项，提交确认由用户操作，不替用户声明已搜索重复 Issue，也不自动提交或上传现场。

本次补充修改 SnapshotBuilder 的 prompt、现有 L1 prompt 交付契约回归、05_utilities 知识库及三组排查 Wiki。根据 GitHub 官方 Creating an issue 和 form schema 核对 template、title、字段 ID 及 URL 长度限制；先取得预填写交付契约失败，再执行定向 main 测试、Wiki 校验/build、渲染输出和 diff 检查。

补充验证：JDK 17 的 InvestigationSnapshotBuilderTest 5 项通过；Wiki 镜像校验与正式 build 通过，6 个生成页面均包含预填写链接段落，diff 检查通过。这些验证保护生成 prompt 的交付要求，不能代替最终用户的 Agent 实际生成 URL 和 GitHub 登录后的表单端到端验证；此项尚未实测。

补充只读复审通过，字段/模板、附件、回退及用户最终提交边界与现有 Issue Form 和调查指南一致。

## 已批准的语言与界面补充

2026-10-13：用户确认实施以下合并范围。生成 prompt 时读取 JVM 系统默认 Locale，language 为 zh（含简体、繁体）时使用中文，其余使用英文；用户描述、日志、路径和环境数据保留原文。Agent 的输出和 Issue Form 使用对应语言。

- 新增 `main/src/main/resources/diagnostics/ask-ai-zh.md`、`ask-ai-en.md`：完整 Markdown 调查模板，现场字段通过单次占位符替换填入，JSON 使用代码块，避免多行插值影响头部缩进。
- 修改 `main/src/main/java/com/sickworm/intellij/jugg/diagnostics/InvestigationSnapshotBuilder.kt` 和现有测试：模板选择、保存/复制一致性、简体/繁体/其他语言、头部对齐、原始输入不被再次替换及调查/Issue 交付契约。
- 修改 `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/AskAiDialog.kt`：描述和 prompt 内容框增加随 IDE 缩放的 8px 内边距；复制弹窗依次展示采集说明、可复制路径、Agent 使用说明、只读 prompt，缺失项计数移到底部。弹窗说明始终使用英文，不随系统语言翻译。
- 修改 `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt`：输出 `Got an unexpected compilation error? [Ask AI] to investigate with your agent.`，仅 Ask AI 可点击。
- 修改 `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanel.kt`：入口显示 Ask AI。
- 复用 `JuggConfigurationRunnerTest.kt` 和 `JuggRunSettingsComponentTest.kt`，同步入口名称断言并验证原有显示条件和 Panel 操作结构。
- 同步 `05_utilities.md` 及编译失败、运行崩溃、改动不生效三组中英文 Wiki；不新增配置、类或接口。
- 先执行现有 L1 owner 的失败回归，再执行定向 main 测试、idea 编译/ConfigurationRunner 回归、模板打包检查及 Wiki 校验/build；UI 间距与布局采用实际组件渲染验证，不新增绑定 Swing 私有属性的测试。
- 验证并完成只读 subagent 审查后，将 `beeef69e4`、`78c27e1b9` 与本次改动合并为一个 feature/diagnostics 提交，保留 `cf83c3216` 及更早历史，不推送或创建 PR。

## 原始改动清单

- `idea/.../ide/logic/JuggConfigurationRunner.kt`：绑定失败上下文、追加 console hyperlink、保持原 onEnd 回调。
- `idea/.../ide/logic/JuggRunningTask.kt`：异常终止时保留实际编译结果，避免把部署异常误报为编译失败；回退重新编译前重置结果。
- `idea/.../ai/mcp/RunLogCollector.kt`：复用同步任务日志收集并保存异常栈，避免异步日志落盘尚未完成时丢失最终异常。
- `idea/.../ide/ui/JuggControlPanel.kt`、`JuggControlPanelController.kt`：Panel 入口与调度。
- `idea/.../JuggManager.kt`：环境/设备/产物采集、串行边界和过期清理。
- 新增 `idea/.../ide/ui/AskAiDialog.kt`：问题填写与结果预览。
- 新增 `main/.../diagnostics/InvestigationSnapshotBuilder.kt`：独立的副本保全、manifest 与 prompt。
- `main/.../project/JuggPathManager.kt`：项目级全局调查目录。
- `.agents/skills/issue-handler/references/local-agent-investigation-guide.md`：显式要求例外。
- 现有 `JuggConfigurationRunnerTest.kt`、`JuggRunningTaskTest.kt`、`JuggRunSettingsComponentTest.kt` 和新增 `InvestigationSnapshotBuilderTest.kt`：有价值的行为回归，Panel 现有动作契约增加 ASK AI。
- 知识库 `04_engineering_ide.md`、`05_utilities.md`、`98_code_map.md`；编译失败、运行时崩溃、改动不生效中英文 Wiki。

## 验证策略与现状

- 失败证据：当前 console 和 Panel 没有 ASK AI；IssueReportBundleBuilder 不保全部署产物。
- L1：真实临时文件验证副本不受原文件修改/删除影响、多 APK 同名文件隔离、缺失文件记录、prompt 的授权和交付契约。
- L2：AskAiInvestigationTest 保护成功/取消/部署失败/RPC 不显示入口、失败上下文固定、完成回调前日志副本含最终异常栈及 flush 中断时保留其余现场；JuggConfigurationRunnerTest 保持运行配置及 MCP 消息摘要契约；JuggRunningTaskTest 通过实际 run→编译边界→设备/部署→onEnd 覆盖编译异常、部署异常、回退重置及正常缺设备路径。外部 compiler/device 采用 Mockito，不增加生产 seam。
- L3：复用 TopLevelFlowTest.testInstallAndLaunch 执行仓库 demo 的真实编译、部署和启动。
- 替代验证：`:idea:compileKotlin`、Wiki production build、镜像/链接检查及 diff 检查。具备可用 IDE/设备时验证实际 UI 与现场；无法执行时明确未验证边界。
- 实现后按 feature-development 技能委托只读 subagent review，修正有效问题后提交本次改动，不推送或创建 PR。

## 实施结果

两个入口、问题表单、设备选择、现场副本、manifest、prompt 预览和复制均已实现。真实滚动日志最多保存最近 10 个；logcat 最多保存各设备当前缓冲区的 20000 行，使用 5 秒无输出超时，不保证保留所有历史现场。兼容资源 APK、mapping 和 wrapper properties 只采集现存文件。Prompt 显式授权在独立调查副本 clone/fetch，调查指南默认仍禁止，已有用户 checkout 保持只读。

- JDK 17 正常执行 `:idea:compileKotlin`。新快照 owner 的失败测试先证实类未实现导致编译失败，随后验证独立副本、多 APK 归属、局部失败和 prompt 契约。没有新增仅服务于 mock 的生产 seam。
- 初次使用 JDK 21 时，仓库存量旧 R8 在 `:main:processResources` 的 instrumentJar 阶段发生 D8 NPE；改用现有 JDK 17 后按正常任务链通过，未修改构建配置。
- Wiki 镜像/链接检查通过；正式 `npm run build` 通过；逐项确认 6 个生成页面包含 ASK AI 操作段落；`git diff --check` 通过。
- 实际 Android Studio ASK AI 弹窗、真实设备的 ASK AI logcat 采集和崩溃/改动不生效现场尚未进行端到端验证；当前目标工程未在 IDE 中打开。
- 独立只读审查确认并修复两项问题：异步文件写入可能缺少最终异常，改为同步收集任务输出并保留异常栈；异常部署原来传 FAILED 丢失实际编译成功标记，改为保留本轮结果并在 fallback 前重置。先运行新回归验证失败，再修改 owner。
- 复审补充项目关闭时的 listener 清理边界：项目注销已经 dispose dispatcher，已知的未注册异常局部收口，原 onEnd 在独立 finally 中保证执行；新增注销后的回归。
- 最终按类过滤的 JuggConfigurationRunnerTest 14 项、JuggRunningTaskTest 15 项、JuggRunSettingsComponentTest 30 项、InvestigationSnapshotBuilderTest 4 项全部通过。L3 TopLevelFlowTest.testInstallAndLaunch 在在线测试设备通过仓库 demo 的真实编译、安装和启动，共 64 项无失败或跳过。首次 L3 缺少 ANDROID_HOME，补齐本机 SDK 路径后通过，未修改项目配置；最后的 listener 清理修复后仅重跑受影响的 compileKotlin 和 ConfigurationRunner 14 项，其他测试沿用此前通过证据。
- 最后只读复审通过，已确认问题全部关闭。仅提交本任务改动，提交标题为 `[feature][diagnostics] add self-service ASK AI investigation with preserved evidence`，不推送或创建 PR。

## 语言与界面补充实施结果

- 已使用完整中英文 Markdown 资源模板。系统默认 Locale 的 zh-CN、zh-TW、zh-HK 生成中文，其余（en-US、fr-FR、ja-JP、ROOT）生成英文；指定 Agent 使用相同语言输出，并选用对应 Bug Issue Form。用户原文和 JSON 不翻译，单次替换不展开用户内容中的占位符。
- Task Output 使用已确认文案，仅 Ask AI 可点击；Running Panel 显示 Ask AI。描述/prompt 文本区域增加随 IDE 缩放的 8px 内边距，复制弹窗按“采集说明/路径/AI 使用说明/prompt”顺序排列，失败计数在底部；说明固定英文。
- TDD：旧实现的 Markdown、英文语言与中文地区输出契约验证失败（8 项中 4 项失败），随后 InvestigationSnapshotBuilderTest 8 项全通过；保持来源副本、APK 隔离、采集失败和授权/Issue 交付回归。JuggConfigurationRunnerTest 14 项、JuggRunSettingsComponentTest 30 项全通过，共 52 项无失败或跳过。
- JDK 17 `:idea:compileKotlin` 通过；当前 main jar 内包含 diagnostics/ask-ai-zh.md 与 ask-ai-en.md。没有修改编译/部署编排，沿用原始任务已通过的 L3 安装启动证据。
- Wiki 镜像/链接校验、正式 build 和 6 个 HTML 的语言规则/入口文字检查通过；git diff --check 通过。
- 临时 Java harness 在仓库外使用现有测试运行依赖，以 MockApplication/mock dialog peer 实例化实际 AskAiDialog，渲染输入与预览中心组件并目视检查。说明固定英文、中文 prompt、8px 内边距及布局符合要求；不替代真实 Android Studio 内采集、剪贴板和完整弹窗交互。辅助 harness 补齐 IDE 所需 JVM opens 和 JNA 路径后完成渲染；未修改生产或构建配置。两张检查图位于 /tmp/jugg-ask-ai-request.png 与 /tmp/jugg-ask-ai-preview.png。
- 当前目标工程已在 IDE 中打开，源码通过 android-studio-index 定位。真实 ASK AI 采集/复制和最终用户 Agent 生成 Issue 链接后的 GitHub 表单仍未做端到端验证。
- 用户授权把两次 ASK AI 提交及本次改动合并为一次，原 cf83c3216 及更早历史保留；提交标题统一为 `[feature][diagnostics] add localized ASK AI investigation with preserved evidence`。
- 最后只读复审通过，未发现需修正项；双语模板、系统语言选择、单次替换、证据边界、Issue 交接、弹窗布局和文档镜像均与批准范围一致。

## 职责收敛与日志调整实施结果

- 已新增 `idea/.../diagnostics/AskAiInvestigation.kt`，两个入口的输入、采集、快照和失败 hyperlink 内聚；JuggManager 仅组装/委托，Runner 仅包装本轮 handler，Controller 的失败保存中转已删除。原有异步清理和运行中采集保护保留。
- RunLogCollector 与 cf83c3216 完全一致，不改变 MCP 结果。完整异常栈从保存的真实日志读取；快照复制前在部署 monitor 外调用现有队列 flush，新增 JuggLogger 入口不持全局锁。flush 失败记录 logs-flush，保留其他证据；中断状态下避免向同一个 writer 再排入 warning，由 manifest 保留失败原因。
- 先确认现有 ConfigurationRunner 14 项通过；MCP 消息摘要契约新增 throwable 断言在旧实现中失败。原 4 项 Ask AI handler 回归移至新 owner，新增完成回调前真实日志副本含最后的 debug/异常栈，以及 flush 中断时仍保存状态 JSON 和 task output 的回归。首次新测试 fixture 漏设 mock options 的 buildTarget 导致保存前失败，补齐为 APP 后再验证；中断异常可能没有 message，按异常类型核查 manifest。生产代码没有新增 mock seam。
- 最终 JDK 17 定向编译通过；SnapshotBuilder 8、AskAiInvestigation 6、ConfigurationRunner 10、RunningTask 15、ControlPanel 30 和 L3 testInstallAndLaunch 1 全部通过，共 70 项无失败或跳过。L3 在在线 emulator-5554（Android API 35）完成仓库 demo 的真实编译、安装和启动。
- 已同步 04/05/98 知识库与此方案；用户操作和 UI 文案未改变，Wiki 沿用此前验证。git diff --check 与日志格式检查通过。真实 Android Studio 中的 Ask AI 采集、复制及最终 Agent Issue URL 表单仍未做端到端验证。
- 最后只读复审通过；修正代码地图中遗留的旧 handler 路径和 Manager 采集职责描述，未发现剩余已确认问题。将本轮调整合并进现有 localized ASK AI feature 提交，保留原父提交 cf83c3216。
