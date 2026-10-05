# English-first 阶段 E：历史资料与增量合入交接

**Purpose:** Preserve historical Chinese records while moving verified, durable facts into current English guidance.
**Decision:** Keep archives unchanged, distinguish implemented behavior from proposals, and review only incoming diff additions when branches merge into `develop/4.0`.
**Impact:** Maintainers gain an English landing path and a review record without treating every Chinese character or historical plan as a policy failure.

## E1：历史档案盘点与事实提炼

本阶段开始时，跟踪的 `docs/task/*/*.md` 有 215 篇，其中 1 篇是本轮迁移计划，旧任务档案为 214 篇；`docs/superpowers/{plans,specs}` 有 5 篇。起点含汉字的两处合计 210 篇（任务文档 208、superpowers 2）。本报告及逐文件索引提交后，中文任务文档会增加 2 篇。这是可复现的**目录/语言清单**，不是 210 项待翻译工作：

```bash
git ls-files 'docs/task/**/*.md' 'docs/superpowers/**/*.md'
rg -l '[\p{Han}]' docs/task docs/superpowers -g '*.md'
```

已按档案属性处理：原文全部保留；方案、报告、调研和失败现场都只代表写作时的证据。不能从标题或“已完成”字样直接推定当前代码事实。[逐文件盘点](english_first_stage_e_archive_inventory.md) 为 219 篇档案记录原文线索、分类和英文路由：5 篇核实后提炼具体约束（P），58 篇在英文专题找到相应条目（C），10 篇是原文标明的方案/未实施内容（D），36 篇是调查或执行证据（E），1 篇有实施声明但尚待核实（I），109 篇仅有历史方案或工作线索（H）。C 只表示具体主题有英文对应，不代表旧文所有结论均生效；H 只是未来核查入口。近期标记已完成/已实施的候选优先对照当前代码和英文专题，其中需要决策的高价值样本如下。没有逐字审核 219 篇全文，剩余 D/E/I/H 和 C 类的独有细节仍须在被引用时人工复核。

| 档案 / 类别 | 当前证据与处理 | 英文落点 / 疑点 |
|---|---|---|
| `2026-03/recompile_cascade_bug_analysis.md`，已修复问题分析 | 英文 `03_deploy_data_generator.md` §5 已记载 static 方法不得进入 step 2 子类遍历、step 3 仍需处理直接引用；与现行文档相符。 | 已覆盖；保留原始日志和问题分析。 |
| `2026-04/androidtest_support_design.md` 及 superpowers 的 androidTest Phase 2、按方法归属 logcat、Multi-APK ownership 计划 | 英文 `06_android_test.md` 已说明 `BuildTarget`、APK ownership、按方法日志归属和当前运行链路。原始设计的阶段/接口草案不能整体视为当前实现；`06_testing.md` 也明确把测试方案当历史背景。 | 已覆盖当前能力；若未来使用草案中的某项细节，重新核实现行实现。 |
| superpowers Gradle 多版本兼容设计及实施计划 | `main/buildReadProjectInfoScript.gradle` 仍提取部分 companion 成员、重写调用并注入跨内部类构造的顶层 factory；原英文专题只描述 trailing comma 边界。 | 将已核实的生成顺序和旧 Gradle Kotlin backend 约束补入 `docs/ai/04_engineering_project.md`；不照搬原方案的所有实现步骤。 |
| `2026-08/marketplace_remote_capability_consent_plan.md`，已实施记录 | `JuggManager` 把 `IssueReportUploader.JUGG_REPORT_URL` 同时传给 `ReportIssueDialog` 和上传/重试；Dialog 实际展示该 URL。原英文 `04_engineering_ide.md` 仍写“不展示 URL”，与实现冲突。 | 已按实现修正 `docs/ai/04_engineering_ide.md`；固定地址未变，Wiki 中英报告页也已说明展示地址，无需改写。 |
| `2026-09/marketplace_plugin_verifier_issue_analysis.md`，兼容性调查 | `CopyEmbeddedDistributionPaths` 以字符串解析两个已知的 downloader 类名；`JuggToolWindowFactory` 经 `toolWindow.contentManager.factory` 创建内容。直接引用失效 API 会留在已发布插件字节码中，运行时保护不能让该引用消失。 | 将当前代码可证实的 Plugin Verifier 静态引用约束补入 `docs/ai/04_engineering_compat.md`；历史报告中各版本的具体扫描结果仅保留为当时证据。 |
| `2026-09/default_method_incremental_abstract_method_error_guide.md`，调查指南 | `DexCompiler`、`CompileEffectAnalyzer` 与英文 `02_compile_source.md` 已覆盖默认接口 D8 classpath 机制；历史指南对“异常名不能单独定根因、需区分 debug desugar 与 release mapping”的调查边界仍有用。 | 将最小判别证据补入 `docs/ai/09_plugin_runtime_debug.md`；未把未取得的具体用户现场根因写成事实。 |
| `2026-09/flutter_engine_asset_manager_refresh_plan.md`，已实施记录 | `FlutterAssetRefresh` 与英文 `03_runtime_jvmti.md` 已说明 Engine 更新、package context 和同 key `rootBundle` 缓存边界。 | 已覆盖；档案中的 Flutter/AOSP 具体版本实验仅作当时证据。 |
| `2026-09/flutter_tool_vm_service_hot_reload_preliminary_plan.md` 与 `native_library_incremental_deploy_research.md`，未实施/调研 | 两文明确标注未进入实现；现行源代码及英文知识未见 Flutter Tool resident Hot Reload 或 SO 启动路径注入的现行能力。 | 不提升为产品能力；若未来实现，需重新验证外部工具协议、设备边界和兼容矩阵。 |

索引中的 `I` 项是 2026-07 Control Panel 原生 mock 布局方案：原文称 mock 和截图已完成、尚待视觉 Review，且明确没有接入真实任务状态；它不是可提升为现行产品规则的证据。其余没有源码核查的条目也保留人工审核边界：未来具体任务先按索引定位原文和 `docs/ai/99_index.md`，核查当时实施状态、当前 owner 和分支差异，再把仍有效而英文专题缺失的独有事实补入对应专题。不能据此宣称档案内容都已覆盖或都已过时。新 `docs/task/YYYY-MM/` 文档按审核者语言写正文；中文正文以本报告开头的简短英文 `Purpose / Decision / Impact` 作检索与交接入口。任务结束后，只将核实过且长期有效的事实纳入英文专题，不维护第二份全文镜像。

## E2：维护指南与工具

`tools/constref_resource_compare.md` 已原位英译，命令仍是定向 `:main:test --tests ...ConstRefFullScanResourceBenchmarkTest.benchmarkFullScanColdAndWarm`，未运行耗时较高且依赖 JOOX 工程的真实基准。英译时单列一处**源文事实纠错**：旧指南称 `500ms/200 files` 是当前 full-scan 默认；`ConstRefEngine.kt` 的 `DEFAULT_FULL_SCAN_IO_THROTTLE_MS/EVERY` 和英文 `03_deploy_const_ref.md` 均为 `3000ms/50 files`。新版指南以此为 baseline，把 `500ms/200 files` 标成显式实验覆盖，并对两组命令都显式传入属性，避免 benchmark 自身 timeout 估算的旧 fallback 造成误读。

`tools/align_markdown_article.py` 只将默认差异报告标签译为英文；`IMAGE_PLACEHOLDER_RE` 仍识别历史中文 `图片`，且图片占位差异仍单列。`tools/*.md` 中其余维护指南目前为英文，无另一份仍被维护流程引用的中文专用指南。

## E3：分支合入增量审核

使用 `tools/check_english_first_diff.py` 对明确的 `base` 与集成结果执行：

```bash
python3 tools/check_english_first_diff.py \
  --base <pre-integration-commit> \
  --head <resulting-commit> \
  --report /tmp/jugg-language-review.md
```

若先检查候选分支，`head` 传候选 tip；合入并解决冲突后必须用原 base 和最终集成提交重跑。脚本只读取 Git diff 的新增/替换行，输出文件、行号、初始分类、英文落点与复核动作；旧中文档案未变化时不出现。报告模式退出 0，不以“出现汉字”直接否决 CI。人工应逐项登记最终分类、英文落点/豁免、未核实事实，再查两个分支的原始内容与当前实现，确保分支新增独有事实并入英文基准。本地化、真实诊断匹配和测试输入豁免语言迁移，但仍需普通功能审核。详见 `docs/ai/97_maintenance_manual.md` §13、`AGENTS.md`、`CLAUDE.md` 和 `CONTRIBUTING.md`。
