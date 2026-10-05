# Jugg English-first 迁移清单与分阶段执行计划

> 创建日期：2026-10-05
> 适用分支：`develop/4.0` 及后续向它合入的分支
> 状态：执行中；阶段 A 为规则与入口迁移

**Purpose:** Make current Jugg engineering materials usable in English while preserving reviewable Chinese task records and existing localized user pages.
**Decision:** Translate Chinese-only current documents, use English as the source for bilingual content, and execute A–F with one independent agent and one commit per stage.
**Impact:** English becomes the default source for current guidance; reviewers may evaluate task plans in their strongest language, with a short English abstract for non-English bodies.

## 1. 目标与边界

Jugg 的新增工程规则、现行维护知识、用户资料、发行资料和 Agent 工作流以英文为首写语言和事实基准。已有中英双语的内容继续按本地化契约提供中文；只有一份中文版的现行文档直接译为英文，不保留中文副本。用户明确使用中文时，Agent 仍可用中文回复。English-first 不要求翻译所有历史档案。

知识库当前位于 `docs/ai_knowledge/`；迁移目标为 **`docs/ai/`，只保留英文**。这是执行计划中的目录改名，本文提交不提前移动目录。新建 `docs/task` 文档采用第 7 节确定的按审核者语言写正文、必要时附英文摘要的规则，不要求正文一律英文。

迁移时保留既有产品事实、命令、路径、配置键、日志关键词和公共行为。仅改变语言的工作不顺带重构代码或改写产品行为。文档与实现冲突时，以当前实现为准，并单独记录事实修订，避免把翻译和行为修订混在同一个难以审阅的 diff 中。

本计划按以下口径分类：

| 类别 | 目标状态 |
|---|---|
| 单份中文现行文档 | 直接将原文译成英文，不保留中文副本；知识库同时迁移至 `docs/ai/`。 |
| 已有中英双语内容 | 英文为事实基准；原有中文本地化版本继续在同一工作包同步。 |
| 任务方案与调查记录 | 旧文档保留原文；新文档按审核者语言写正文，非英文正文附简短英文摘要。仍有效的结论提炼到英文现行文档。 |
| 中文作为真实输入的数据 | 保留中文，例如中文编译器诊断、测试夹具、`values-zh-rCN` 资源。 |

## 2. 当前基线与全部待办

以下数量来自创建本计划前当前分支的 Git 跟踪文件盘点；“含中文”不表示文件完全没有英文技术术语。新分支合入后需要重新盘点增量，不能把这些数字当作永久基线。

| ID | 范围与现状 | 待办 |
|---|---|---|
| R1 | `AGENTS.md`、`CLAUDE.md` 以中文写作，并规定知识库和任务方案使用中文。 | 直接将两份规则译为英文，不留中文版；明确现行文档英文基准、中文回复例外及知识库过渡期；把第 7 节的 `docs/task` 生成指引写入两份规则。 |
| R2 | `CONTRIBUTING.md`、`.github/PULL_REQUEST_TEMPLATE.md` 仍写中文 Wiki 为内容基准；英文 Issue 表单位于中文表单之后。 | 改成英文基准，调整 Issue 默认展示顺序，更新 PR 文档勾选项和双语提示；保留中文反馈入口。 |
| R3 | Wiki 英文根路径和中文 `/zh/` 已有 143 对 Markdown 页面，路径镜像校验通过；`10_wiki_authoring.md` 和 `wiki-writer` skill 要求先中文后英文。 | 反转编辑顺序和事实基准，继续保持双语同步与路由镜像；核对英文主页、搜索、nav/sidebar 和站内链接。无需整站重翻。 |
| R4 | `docs/ai_knowledge` 的 35 篇、约 721 KB / 7,940 行都含中文，没有英文对应文档。 | 分工作包将其译为英文并迁移到 `docs/ai/`，最终删除旧目录，不创建中文镜像；更新索引、交叉链接、Agent 引用和维护规则。 |
| R5 | `.agents/skills/wiki-writer/SKILL.md` 主要为中文；`jugg-update-version-changelog` skill 要求先起草中文 changelog；部分 Agent 配置显示中文。 | 将单份中文 skill 直接译为英文，反转 changelog 首写顺序；保留中文触发语、面向中文用户的模板。其他 skill 逐个核对，不因出现汉字就整文件重写。 |
| R6 | `docs/skills/README.md`、`ADK_RULES.md` 为中文维护说明；benchmark 的 22 篇 Markdown 含中文且无英文题目包；内置开发 skill 有少量中英混排。 | 将中文独有的维护说明和 benchmark 母版原位译英；检查导出器与评分口径。内置 skill 只清理混排与默认语言，保留按用户语言选择的中英输出模板。改内置 skill/CLI 文案时遵守版本递增规则。 |
| R7 | `tools/collect_jugg_scene_prompt.md` 只有中文；`tools/constref_resource_compare.md` 为中文内部评估指南；`tools/align_markdown_article.py` 输出中文差异报告。 | 将两份中文独有文档原位译英；差异报告默认英文，但保留中文图片占位符的识别能力。 |
| R8 | `THIRD_PARTY_NOTICES.md`、`third_party/INTEGRATION.md`、`third_party/MODIFICATIONS.md` 的关键说明为中文；`components.csv` 和 SBOM 含中文字段。Notice、修改说明及 SBOM 由 `tools/generate_third_party_compliance.rb` 生成。 | 将中文独有说明译英，不留中文版；同步源数据、生成逻辑和发行产物。保持许可证标识、上游引用、组件数及内容一致，由人专项审核法律语义。 |
| R9 | `cmd_line/src/demo/` 与 `cmd_line/src/demo_release/` 共 6 个文件含中文说明或终端输出；JVMTI `FileUtil.java` 有中文权限错误。 | 将中文独有的示例和错误信息译英；验证脚本行为和诊断可读性。 |
| R10 | 个别测试代码有中文注释或数据，生产编译器有中文错误匹配词。 | 只把不承担测试语义的注释改为英文；中文输入、断言样本、编译器诊断匹配词和中文资源保留。 |
| R11 | `docs/task` 的 214 篇已跟踪 Markdown 中有 207 篇含中文；`docs/superpowers` 有 2 篇中文方案。 | 保留历史原文；仍有效的结论提炼到英文现行文档。新增 `docs/task` 按第 7 节生成指引处理。 |
| R12 | README、SECURITY、CONTRIBUTING、Issue 表单、双语 changelog 和 Wiki 已有英文版本；发布脚本先输出英文发行说明。 | 核对英文入口与翻译同步，不重复创建镜像；新现行内容按英文首写流程进入。 |
| R13 | 当前没有只针对新增中文独有现行资料的增量门禁。 | 建立基于本次 diff 的检查与例外表：拦截新中文独有的现行规范；允许 `docs/task` 按审核者语言生成，并检查非英文正文有简短英文摘要；历史档案、本地化、测试输入与编译器诊断豁免。 |

`docs/wiki` 的 143 对页面已通过 `.agents/skills/wiki-writer/scripts/validate_wiki.py`。它们不是“只有中文”的存量缺口；要改的是首写规则。`docs/task` 的 207 篇是含中文的历史文档数量，不等于 207 篇必须全文翻译。

## 3. AI 上下文容量与人工审核规则

每个下表中的**工作包**是一次受 AI 上下文容量约束的独立处理与人工审核单元。阶段可以包含多个工作包；不得为节省任务数量把整个知识库、所有 benchmark 或全部第三方清单一次塞进上下文。每个 A～F 阶段由独立 subagent 完成，并且只提交一个阶段级 commit；同阶段内各包先分批处理和验证，再一起提交。

1. 每包先读仓库强制文档，再只加载该包的目标文件及直接引用。计划目标是**待处理原文不超过约 45 KB、通常不超过 3～5 篇文档**；若还需读取大量源码、生成物或测试证据，应进一步缩小。字节数只是预估代理，执行前优先查看实际 token 用量，并给生成内容、验证和修订留足空间。
2. 单篇超过目标体量时按稳定章节拆成连续工作包；同一文件的后续工作包只携带已审核的术语表、章节摘要和未解决问题，不重复加载所有历史上下文。`04_engineering_project.md` 约 71 KB，必须这样处理。
3. 一个工作包只承担一个可审阅目标。英译、事实纠错、路径切换和 CI 门禁尽量在阶段内分批处理；需要一起修改才能保持可运行的文件作为一个小组处理。每个阶段仅有一个提交，并在提交说明或交接记录中列出各包的验证证据。
4. AI 在交付时附变更文件、原文对应章节、保留的命令/数据、验证证据和待人审核的术语或事实疑点。审核人按工作包确认事实与术语；同阶段内发现的问题在该阶段 commit 前局部修订，不让错误传播到后续翻译。
5. 翻译现行知识时，先保持原文事实，再对关键行为抽样核对代码。发现原文可能过期时单独记录并核实，不在英文版里悄悄增加、删除或弱化事实。

下面的工作包大小按原文 `wc -c` 粗估。45 KB 是拆包目标，不是质量保证；即使小于这个数，若代码核验或输出过大，也必须继续拆分。

## 4. 分阶段执行

### 阶段 A：规则与入口先行

先建立过渡规则：**新的现行规范英文首写；现有中文知识库在完成迁移前仍可按原路径读取**。不能在 `docs/ai/` 尚未就绪时让 Agent 误以为旧路径已经是英文。`docs/task` 按第 7 节的生成指引执行。

| 包 | 范围 | 完成条件 |
|---|---|---|
| A1 | `AGENTS.md`、`CLAUDE.md`、`CONTRIBUTING.md`、PR 模板与 Issue 模板。 | 两份 Agent 规则直接译英且保持一致；明确写入第 7 节的 `docs/task/YYYY-MM/` 生成指引，保留按月份建档要求；英文 Issue 选项先展示，中文用户入口保留。 |
| A2 | `.agents/skills/wiki-writer/SKILL.md`、旧路径的 `10_wiki_authoring.md`。两份原文合计约 41 KB。 | Wiki 以英文为首写基准，中文页面同批同步；skill 本身译英且不留中文版；原有双语路由校验不退化。若上下文超预算，拆成两包。 |
| A3a | `10_wiki_architecture.md`、Wiki 配置与验证脚本。配置文件约 37 KB，执行前按实际读取范围缩小。 | 移除废弃服务的发布说明；当前 Wiki 路由说明一致，双语页面路径及验证脚本继续可用。 |
| A3b | `.agents/skills/jugg-update-version-changelog/SKILL.md`、`tools/auto_update.md`、发行说明生成器。 | changelog 改为英文先起草、中文跟进；保留现有双语格式和发布脚本契约。 |

### 阶段 B：对外可见的中文缺口

| 包 | 范围 | 完成条件 |
|---|---|---|
| B1 | `tools/collect_jugg_scene_prompt.md`、CLI 两套 demo 的 README 与四个示例脚本。 | 中文独有内容原位译英，英文用户可独立完成现场采集与 demo；命令和结果判断保持不变。 |
| B2 | JVMTI 中文权限错误、非语义性中文代码注释；生产代码与测试夹具分开审核。 | 用户错误可读；中文诊断匹配、中文测试路径、中文资源仍能发挥原作用。 |
| B3 | 第三方清单源数据 `components.csv` 约 41 KB 和生成脚本。按组件行拆成多个不超过预算的包。 | 英文说明进入源数据和脚本；组件身份、许可证表达、104 条组件记录不变。 |
| B4 | 重新生成并核对 Notice（约 53 KB）、Modifications、SBOM；另处理 `INTEGRATION.md`。 | 生成物可复现，发行包仍携带完整第三方资料；人专项审核许可证、例外条款和修改描述。Notice 如超预算按组件区段审核。 |

### 阶段 C：现行知识库英文版

将 `docs/ai_knowledge/` 的每篇中文原文直接翻译到目标目录 `docs/ai/`，**不创建 `zh/` 或其他中文副本**。迁移期间旧目录仅作为待处理原文暂存；**每个 C 包单独翻译、验证并由人审核，阶段 C 只产生一个 commit**。阶段 F 再删除旧目录，统一切换现行引用；未完成前，Agent 仍按当前强制流程读取旧路径。

| 包 | 文件名（源目录 `docs/ai_knowledge/`，目标目录 `docs/ai/`） | 原文约量 |
|---|---|---:|
| C1a | `00_overview.md`、`01_architecture.md`、`99_index.md` | 21 KB |
| C1b | `98_code_map.md` | 27 KB |
| C2 | `97_maintenance_manual.md`、`10_wiki_architecture.md`、`10_wiki_authoring.md` | 42 KB |
| C3 | `02_compile_source.md` | 36 KB |
| C4 | `02_compile_core.md`、`02_compile_custom_ui.md` | 36 KB |
| C5 | `02_compile_resource.md`、`02_compile_databinding.md` | 39 KB |
| C6 | `02_compile_manifest.md`、`02_compile_manifest_obfuscation.md`、`02_compile_obfuscation.md` | 14 KB |
| C7 | `03_deploy_core.md` | 43 KB |
| C8 | `03_deploy_complete.md`、`03_deploy_const_ref.md`、`03_deploy_data_generator.md` | 43 KB |
| C9 | `03_deploy_system_app.md` | 25 KB |
| C10 | `03_runtime_jvmti.md` | 40 KB |
| C11 | `04_engineering_compat.md`、`04_engineering_debug_attach.md` | 40 KB |
| C12 | `04_engineering_ide.md` | 36 KB |
| C13a | `04_engineering_project.md` 第 1～3 节 | 20 KB |
| C13b | `04_engineering_project.md` 第 4 节 | 26 KB |
| C13c | `04_engineering_project.md` 第 5～8 节，合并已审核的前两部分 | 26 KB |
| C14 | `05_utilities.md` | 17 KB |
| C15 | `06_testing.md` | 21 KB |
| C16 | `06_android_test.md` | 30 KB |
| C17 | `08_cli_tools_list.md` | 31 KB |
| C18 | `08_mcp_tools_list.md` | 29 KB |
| C19 | `08_mcp_design.md`、`08_mcp_layout_verify_design.md`、`08_mcp_figma_layout_verify_internals.md`、`08_mcp_ui_verify_checklist.md` | 40 KB |
| C20 | `09_plugin_runtime_debug.md` | 38 KB |

每包检查英文页面的标题、表格、链接、代码块、路径和术语与原文对应；索引类文件还需检查所有目标页面能找到。C2 等接近工作包上限的任务先看实际上下文用量，必要时继续拆分，不按表格强行合并。C13a/C13b 的分段结果只是待审核草稿，C13c 完成整篇合并后才标记该文档已译完。

### 阶段 D：Agent 资料与 benchmark

| 包 | 范围 | 完成条件 |
|---|---|---|
| D1 | `docs/skills/README.md`、`ADK_RULES.md`、内置开发 skill 中英混排的入口和三个相关 reference。 | 中文独有的维护说明原位译英；面向中文用户的输出模板仍可用；版本号、日期及自动刷新规则正确。 |
| D2 | benchmark-cli 的 7 篇 Markdown。 | 中文独有母版原位译英；题目、期望与评分口径一致，CLI 参数和预期状态不变。 |
| D3 | benchmark-hooks 的 3 篇与 runner README。 | 英文母版和导出说明可执行，hook 触发要求不变。 |
| D4 | benchmark-instrument 的 5 篇。 | 英文用例覆盖原有成功、失败和无设备边界。 |
| D5 | benchmark-ui-verify 的 6 篇。 | 英文 UI 验证条件、选择器和评分阈值不变。 |
| D6 | `.agents/skills` 中中文专用内容与 Agent 配置，逐个处理。 | Wiki skill 英文可独立执行；Issue 调查模板继续按报告者语言选择；中文触发语仍能路由到原 skill。 |

### 阶段 E：历史档案与合入分支规则

| 包 | 范围 | 完成条件 |
|---|---|---|
| E1 | `docs/task` 以及 `docs/superpowers` 的现有中文档案。 | 历史原文保留，不批量翻译 207 篇旧任务文档；仍有效的产品结论提炼到英文现行文档。新任务方案按第 7 节生成。 |
| E2 | `tools/constref_resource_compare.md`、`tools/align_markdown_article.py` 和后续仍被维护流程引用的中文专用指南。 | 英文维护者可按说明复现流程、阅读差异报告；没有过期命令；中文历史图片占位仍可识别。 |
| E3 | 向 `develop/4.0` 合入其他分支时的增量分类和检查。 | 合入后比较新增/修改文件：现行规则和产品事实须在集成完成前有英文基准；新任务方案按第 7 节补英文摘要；历史记录保留中文并按需提炼；本地化、诊断匹配和测试输入豁免。禁止直接用英文版覆盖中文分支新增的独有事实。 |

E3 的检查只针对本次合入 diff，不因已有中文档案阻止合并。检查可以先报告、后逐步收紧为门禁；不建立简单的“发现任何汉字即失败”规则。每次集成须记录新中文资料的分类、对应英文落点和待人审核的事实疑点。

### 阶段 F：路径切换与最终门禁

| 包 | 范围 | 完成条件 |
|---|---|---|
| F1a | 已审核的 `docs/ai/` 与旧 `docs/ai_knowledge/`、现行规则和工具引用。 | 35 篇英文文档齐全后删除旧目录；更新 `AGENTS.md`、`CLAUDE.md`、索引、skills、脚本等现行引用，所有强制入口指向 `docs/ai/`。此包不重新翻译正文。 |
| F1b | 历史任务资料中的旧知识库路径引用。 | 区分可点击的现行链接和记录当时状态的历史文字；修复前者，不机械改写后者。路径扫描涉及约 107 个已跟踪文件，其中多数为 `docs/task`，按实际 diff 继续拆包。 |
| F2 | 新旧文档规则、Wiki 镜像、发布资料、skill 包、demo、第三方产物的集成检查。 | 增量语言门禁、Wiki 校验和相关定向验证通过；人工确认英文入口可独立完成安装、排查、贡献、发行与维护。 |

## 5. 各类验证与审核门槛

- **文档翻译包**：检查 Markdown/站内链接、代码块和表格条目；抽查原文与英文事实对应；`git diff --check`。只改文字时不增加模拟实现的自动化测试。
- **Wiki 包**：运行 `python3 .agents/skills/wiki-writer/scripts/validate_wiki.py --wiki-root docs/wiki`，再按变更范围执行 production build 和中英文页面预览。
- **Skill/CLI 包**：按 `08_cli_tools_list.md` 的版本规则递增并核对安装后的版本；仅运行受影响的定向脚本或现有测试。
- **第三方包**：重新生成并比较 Notice、Modifications、SBOM；核对 104 条组件、许可证字段和发行包清单，由人审核法律相关措辞。
- **运行代码包**：先记录中文错误出现的真实边界，再做匹配风险相符的定向验证；不对 Jugg `:main:test`、`:idea:test` 运行未过滤的全量测试。
- **切换包**：扫描过时的中文首写指令、失效路径和无英文对应的现行文档；核对工作区仅含该包改动。各工作包分别验证；每阶段只提交一个独立 commit，人工审核后推进下一阶段。

## 6. 执行顺序和完成定义

按 A → B → C → D → E → F 推进；C 的知识库工作包按表内依赖顺序执行，但彼此不共享庞大对话历史。每完成一包，人工审核结论和术语决定写进该阶段的提交说明或简短交接记录。后续 AI 只读取当前包需要的结论，不加载以前所有会话。每个阶段由不同 subagent 负责，在该阶段所有工作包通过验证后创建一个独立 commit。

完成标准是：英文成为新现行规范的默认首写语言；现行工程规则、`docs/ai/` 知识库、公开发行说明及 Agent 核心工作流可独立用英文完成；中文独有现行规范不保留中文副本，已有双语本地化入口保留；`docs/task` 的新文档能由人按熟悉语言审核且可通过英文摘要检索；历史中文档案可检索且不会被误当成现行英文规范；新分支合入时有增量分类和审核证据。

## 7. 已确定：新任务方案的语言和生成指引

`docs/task/YYYY-MM/` 承载的是待人判断的工作方案、调查记录和交接材料。若要求正文一律英文，非英文母语审核者可能更难发现逻辑漏洞；若只写中文，英文使用者和后续 Agent 又难以检索。任务方案与长期生效的 `docs/ai/` 知识库应采用不同语言规则。

| 方案 | 收益 | 代价 |
|---|---|---|
| 全文只用英文 | 全库语言统一。 | 非英文母语者需要审阅不熟悉语言中的细节，直接损害方案判断。 |
| 全文中英双语 | 两类读者都能读完整方案。 | AI 输入、输出和人工 diff 约翻倍，两版容易偏离；与本计划按上下文容量拆包的目标冲突。 |
| **按审核者语言写正文，附简短英文摘要** | 人用熟悉的语言判断细节，英文摘要提供检索入口。 | 摘要不是全文替代品；跨语言的关键决策仍需在后续英文现行文档中落地。 |

采用第三种，并在 A1 明确写入 `AGENTS.md` 与 `CLAUDE.md` 的文档生成指引：方案正文使用实际审核者最容易准确判断的语言；若审核者未指定，使用当前用户的语言。非英文正文在开头附简短英文 `Purpose / Decision / Impact` 摘要，供检索和跨语言交接。摘要由 AI 起草、随正文一起供人审核，但不要求人仅凭英文摘要批准实现。任务完成后，仍有效的产品事实和架构约束进入英文 `docs/ai/`；历史方案保持原文，不再维护第二份全文镜像。若审核者需要英文全文，则直接用英文写该任务方案。

A1 的验收需包含一个英文正文示例和一个中文正文加英文摘要的示例，确认两个路径都遵守 `docs/task/YYYY-MM/` 建档位置，且英文摘要不替代人的正文审核。

### A1 建档验收示例

英文审核者审阅的方案可以直接用英文正文，例如 `docs/task/2026-10/example_english_plan.md`：

```markdown
# Example plan

## Purpose
Explain the user-visible problem and the proposed change.

## Decision
Record the decision and its evidence in English for the reviewer.
```

中文审核者审阅的方案使用中文正文，例如 `docs/task/2026-10/example_chinese_plan.md`。开头的英文摘要用于检索与交接，不替代审核者阅读中文正文：

```markdown
# 示例方案

**Purpose:** Explain the user-visible problem.
**Decision:** Record the chosen approach.
**Impact:** State what changes for users or maintainers.

## 背景与判断
这里用中文写足供审核者判断的事实、方案和验证依据。
```

以上是指引中的样例片段，不要求创建这两份示例文件。
