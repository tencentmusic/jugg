# English-first translation review result

**Purpose:** Identify semantic distortion and omitted information in English-first changes from `bce808da` through `a7da9ae69351674ab03b6dca750b337722e932e1`.
**Decision:** Apply all five maintainer-approved corrections while retaining the original review evidence.
**Impact:** Current English guidance and generated third-party records preserve the verified source meaning.

## 审查范围与方法

这一范围有 153 条 Git 路径变更；将 `docs/ai_knowledge/` 与 `docs/ai/` 的同名文件配对后，`progress.md` 列出 118 个审查单元。按 `git show bce808da:<原路径>` 与 `git show a7da9ae69:<目标路径>` 比较原文和现文，核对条件、否定、数值、步骤、职责与发行范围。下文“现文”指审查时的 `a7da9ae69`，不是修复后的文本；保留问题描述以便追溯。

## 已批准并修复的五项

### R1 · 第三方 Notice 三条发行事实被旧 CSV 内容覆盖（重要）

- 位置：[THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md#L185) 的 #20、[第 194 行](../../../THIRD_PARTY_NOTICES.md#L194) 的 #21、[第 707 行](../../../THIRD_PARTY_NOTICES.md#L707) 的 #78。
- #20：基准 Notice 第 185 行写 Data Binding 7.4.2 的 compiler、compiler-common、common、baseLibrary JAR **随插件与 cmd_line 发行**，并解释选择 7.4.2 而非 8.7.3，是因为 8.7.3 为 class file 61.0，不能在 Java 11 IDE 加载。现文只写 `cmd_line standalone distribution`，选版原因也消失。
- #21：基准 Notice 第 194 行写**当前发行物已改为 7.4.2**，8.7.3 的 Java 17 字节码无法在 Electric Eel 等 Java 11 IDE 加载 `XMLParser$ElementContext`；现文反说 8.7.3 随插件发行。
- #78：基准 Notice 第 707 行写 ANTLR 重定位代码内嵌于 Data Binding compiler-common **7.4.2**；现文改成 **8.7.3**。
- 根因与证据：基准 [components.csv](../../../third_party/components.csv#L21) 的 #20/#21/#78 备注原本就与基准 Notice 的后续人工修订冲突；本次英文转换重新生成 Notice，把旧 CSV 事实覆盖进去。当前 [main/build.gradle](../../../main/build.gradle#L130) 声明 IDEA 和 standalone 共用 7.4.2；[Data Binding 专题](../../ai/02_compile_databinding.md#L129) 也说明插件内置 7.4.2。当前插件 ZIP 仅有四个 7.4.2 Data Binding JAR，没有 8.7.3；7.4.2 compiler-common 内含 ANTLR 重定位代码。
- 修复：依据发行 ZIP 与依赖声明，修正 CSV 第 21、22、79 行的备注，重新生成 Notice 和 [SPDX](../../../third_party/sbom/jugg-third-party.spdx.json#L338)。恢复 7.4.2 的插件与 cmd_line 分发范围、Java 11 兼容原因及 ANTLR 内嵌版本；明确 8.7.3 不在当前发行物中。许可证、版本和修改状态字段保持不变。

### R2 · Direct 两通道的整批部署条件被混同（中）

- 位置：[03_deploy_core.md](../../ai/03_deploy_core.md#L173)；基准 `docs/ai_knowledge/03_deploy_core.md:172`。
- 原意：普通 Direct 保留开关、调用方许可和 ready/force 门禁；Direct app sandbox 在 Android 8+ 按 sandbox 能力及应用不兼容 Apply Changes 判断。非安装、非空 payload 命中任一通道后整批部署。
- 现文 `Either channel deploys the whole batch when any application ... is incompatible with Apply Changes` 将 app sandbox 的不兼容条件套到两个通道上，使普通 Direct 看似也必须满足该条件。[JuggDeployOrchestrator.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployOrchestrator.kt#L83) 的两个 `canTry` 分支为逻辑或。
- 修复：分别写明两通道的准入条件，并说明非安装、非空 payload 命中任一通道后整批部署。

### R3 · “分片”译为“APK routing”（中）

- 位置：[03_deploy_core.md](../../ai/03_deploy_core.md#L27)；基准 `docs/ai_knowledge/03_deploy_core.md:27`。
- 基准的 `JuggDeployOrchestrator` 职责首项为“分片”；现文是 `APK routing`。本页后文仍明确由 orchestrator 决定是否进入 `SliceDeployHelper`。
- 修复：改为 `deployment slicing`。

### R4 · Benchmark CLI 章节指引格式损坏（低）

- 位置：[benchmark-instrument/README.md](../../skills/benchmark/benchmark-instrument/README.md#L10)；基准同路径第 10 行。
- 基准为“（§2 `instrument`）”；现文为 ``(`2 `instrument`)``，丢失 `§` 且反引号不匹配。当前 [CLI 参数清单](../../ai/08_cli_tools_list.md) 的 `instrument` 实际在 §6。
- 修复：改为“(§6, `instrument`)”；同时更正过时节号。

### R5 · 固定 include/exclude 规则措辞歧义（低）

- 位置：[05_utilities.md](../../ai/05_utilities.md#L127)；基准 `docs/ai_knowledge/05_utilities.md:128`。
- 原文说 `.gradle` 与 `build` 保留固定 **include/exclude 顺序**，默认排除目录同时放行 Jugg 必需路径。现文前句保留此意，后句却称 `these two fixed exclusions`，可能让读者以为固定规则只有排除部分。
- 修复：改为 `the two fixed include/exclude rule groups`。

## 已核实的非翻译改写

- [09_plugin_runtime_debug.md](../../ai/09_plugin_runtime_debug.md) 旧文重复、错位段落与过期类名经过后续整理；Git 补检、source DB、注解 crash、IDE freeze 等关键约束在新版 §4.1–4.8 保留。
- [10_wiki_architecture.md](../../ai/10_wiki_architecture.md) 的旧后台 `rsync` 发布段落在中文源文修订时已先行删除；`jugg_backend/deploy/nginx/README.md` 说明迁移后不再运行旧发布脚本。
- [10_wiki_authoring.md](../../ai/10_wiki_authoring.md) 与 Wiki writer skill 将内容基准由中文切换为英文，属于 English-first 有意规则变更。
- [04_engineering_ide.md](../../ai/04_engineering_ide.md#L184) 的报告上传 URL 展示规则由后续提交按现行实现修正；[constref_resource_compare.md](../../../tools/constref_resource_compare.md) 的默认 throttle 从旧文的 `500 ms/200 files` 更正为代码实际的 `3000 ms/50 files`，不算误译。

## 待确认的历史信息删减

- [constref_resource_compare.md](../../../tools/constref_resource_compare.md) 不再保留基准第 39 行所述历史旧值 `10000 ms/50 files`，也删去第 118–120 行以提交 `8a46f2415` 复现旧源码的步骤。新文改为同一版本下两组显式 throttle 参数对比，并说明如需比较源版本应使用独立 worktree；这属于范围调整，并非确认的误译。该旧提交在当前仓库无法解析。若仍需要保留当时的历史性能背景，可补一段经过核实的历史说明；否则无需改。

## 覆盖情况

五批均完成：A 知识库总览/编译 10 项，B 知识库部署/工程 11 项，C 知识库测试/MCP/维护 14 项，D 技能与基准 38 项，E 仓库规则/示例/第三方/工具 45 项，共 **118/118 审查单元**。其中 6 项为 `R100` 纯重命名，35 项为知识库原文与译文配对。临时逐项清单位于同目录 `progress.md`；其中所有条目均已勾选。本次修订只涉及上述五项文档/清单内容，没有改动运行代码。
