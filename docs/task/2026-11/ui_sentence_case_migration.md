# UI 英文文案迁移到 develop/4.0

**Purpose:** Move the English UI capitalization change from commit `0a307e510` to `develop/4.0`.
**Decision:** Preserve target-branch behavior and English maintainer documentation while adapting display names at their current owners.
**Impact:** UI labels and their bilingual documentation use sentence case; action IDs and configuration/protocol fields stay unchanged.

## 范围与适配

在 `/Users/wormchen/IdeaProjects/jugg/jugg_f2` 的 `develop/4.0` 上迁移 `0a307e510`。源分支提交保留，由用户稍后 drop。本次不迁移其父提交 `49a65facd` 的 Gradle clean 行为，也不带入目标工作区已有的 Android style 调查改动。

保留目标分支的共享 Runtime、项目写锁和安装流程，只修改显示文案。迁移后的 behavior owner 包括 `main` 中的 `GitChangesCompileChecker`、`CopyGeneratedSourceHelper` 和 `JuggSettingsEditor`；后者承接旧 Controller 的设置确认文案。APK 重装按钮由目标分支的 `Clean And Reinstall` 变为 `Just clean reinstall APK`，其他普通英文文案统一为 sentence case，保留专有名词和缩写。

旧 `docs/ai_knowledge/04_engineering_ide.md` 保持删除，在目标分支现有英文 `docs/ai/04_engineering_ide.md` 和 `AGENTS.md` 中落地显示规则。英文 Wiki 为基准，同步中文入口引用，保留目标分支已有产品事实、路由和历史文章。

## 验证策略

纯文案变更不新增自动化测试。复用设置面板、动作注册、安装结果、更新弹窗、Android test gutter 入口和共享设置编辑器的现有测试；在目标工作区执行 Kotlin 编译、Wiki 镜像与链接验证、production build 和 diff 检查。静态比较确认 Kotlin 相对目标 HEAD 只存在大小写和 APK 按钮名称差异，插件 XML 去掉 text/description 后结构不变。

## 英文优先集成审查

已执行 `tools/check_english_first_diff.py --base 0a307e510^ --head 0a307e510`，报告 `/tmp/jugg-ui-incoming-language-review.md`。每项新增中文内容的处理如下；最终提交后已再次按目标原 HEAD `56cef4c1a` 到新提交检查，报告 `/tmp/jugg-ui-f2-final-language-review.md`。最终报告的 20 个中文文件均为下表覆盖的本地化页面或本文任务记录；当前规则及知识库新增内容均为英文，无未决事实。本文的中文主体属于面向当前用户的任务记录，已有英文 Purpose / Decision / Impact 摘要。

| 来源路径 | 分类 | 英文落点或例外 | 处理 | 未决事实 |
|---|---|---|---|---|
| `AGENTS.md` | 当前规则 | `AGENTS.md`（英文 sentence case 规则） | 迁移新增规则，保留目标分支其他规则。 | 无 |
| `docs/ai_knowledge/04_engineering_ide.md` | 当前知识 | `docs/ai/04_engineering_ide.md` | 只迁移已核实的显示规范和入口名称；旧目录保持删除。 | 无 |
| `docs/task/2026-11/gradle_fallback_clean.md` | 历史任务记录 | 本文 | 目标分支无该记录；以带英文摘要的本文记录文案迁移，不引入原分支 Gradle clean 实现结论。 | 无 |
| `docs/wiki/zh/capabilities/deploy/custom-apk-install-script.md` | 本地化 | `docs/wiki/capabilities/deploy/custom-apk-install-script.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/concepts/compatibility-layer.md` | 本地化 | `docs/wiki/concepts/compatibility-layer.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/guide/advanced-options.md` | 本地化 | `docs/wiki/guide/advanced-options.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/guide/android-test.md` | 本地化 | `docs/wiki/guide/android-test.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/guide/cli.md` | 本地化 | `docs/wiki/guide/cli.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/guide/control-panel.md` | 本地化 | `docs/wiki/guide/control-panel.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/guide/debug.md` | 本地化 | `docs/wiki/guide/debug.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/guide/downgrade-gradle.md` | 本地化 | `docs/wiki/guide/downgrade-gradle.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/guide/index.md` | 本地化 | `docs/wiki/guide/index.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/guide/remote-gradle.md` | 本地化 | `docs/wiki/guide/remote-gradle.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/guide/report-issue.md` | 本地化 | `docs/wiki/guide/report-issue.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/guide/restart-app.md` | 本地化 | `docs/wiki/guide/restart-app.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/guide/run-configuration.md` | 本地化 | `docs/wiki/guide/run-configuration.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/guide/run.md` | 本地化 | `docs/wiki/guide/run.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/troubleshooting/app-cannot-run.md` | 本地化 | `docs/wiki/troubleshooting/app-cannot-run.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/troubleshooting/changes-not-applied.md` | 本地化 | `docs/wiki/troubleshooting/changes-not-applied.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/troubleshooting/clear-data-and-cache.md` | 本地化 | `docs/wiki/troubleshooting/clear-data-and-cache.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/troubleshooting/compile-failed.md` | 本地化 | `docs/wiki/troubleshooting/compile-failed.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |
| `docs/wiki/zh/troubleshooting/runtime-crash.md` | 本地化 | `docs/wiki/troubleshooting/runtime-crash.md` | 入口文案与英文源页同步；产品事实不变。 | 无 |


## 最终验证结果

- 使用 JDK 17 与本机 Android SDK（`ANDROID_HOME`）执行 6 个现有测试 owner，共 97 项，90 项通过、7 项失败。首次执行缺少 Android SDK 环境变量，设置后重新执行。
- 临时还原全部迁移涉及的 Kotlin 文件与 plugin.xml 到目标原 HEAD `56cef4c1a`，再执行相同 6 个测试 owner，失败用例集合完全相同，原因仍为复选项数量、注入能力条件、旧重装 mock 预期、空协程上下文和安装路径隔离预期。原始结果保存在 `/tmp/jugg-ui-f2-changed-failures.json` 与 `/tmp/jugg-ui-f2-baseline-failures.json`；已恢复全部迁移改动。
- Kotlin 编译通过；Wiki validator 与 production build 通过，确认中英文 Gradle 回退页的 HTML 均包含 `Just clean reinstall APK`。
- 39 个 Wiki 文件只改入口名称及大小写；Kotlin diff 只改大小写和 APK 按钮名称；plugin.xml 排除 text/description 后结构完全相同；`git diff --check` 通过。未在真实 IDE 逐页验收。

依据：`AGENTS.md`、`docs/ai/00_overview.md`、`99_index.md`、`98_code_map.md`、`04_engineering_ide.md`、`06_testing.md`、`97_maintenance_manual.md` §13、`10_wiki_authoring.md`、`10_wiki_architecture.md`，以及目标分支 wiki-writer skill 和对应源码/测试 owner。
