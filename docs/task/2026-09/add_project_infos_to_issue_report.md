# 问题报告补充工程快照

## 背景

当前问题报告采用白名单生成诊断包，只包含脱敏后的日志、环境摘要和设备日志。旧上报流程曾包含 `project_infos.db`，切换到白名单后，`project_infos.json`、`gradle_project_infos.json` 及 included build 的 Gradle 工程快照不再上传，导致部分工程模型、Manifest placeholder 和 applicationId 相关问题缺少定位依据。

## 已批准范围

- 将存在的 `project_infos.json` 和 `gradle_project_infos.json` 加入问题报告候选项。
- 将存在的 `include_build_*_gradle_project_infos.json` 一并加入候选项。
- 上述工程快照默认勾选，并排在可选项顶部；必选 Jugg 日志仍保持在整个列表最前。
- 工程快照使用结构化 JSON 脱敏，不直接打包原文件。保留 `applicationId`、字段存在性和普通工程信息；替换 SigningConfig 凭据、keystore、keyAlias、Manifest placeholders、APT/KAPT 参数和通用敏感键的值。JSON 解析失败时跳过对应候选项。
- 不加入 `gradle_include_builds.txt`、`is_dirty`、历史目录文件或其他 `project_infos.db` 内容。

## 实现方案

1. `JuggManager` 将 `JuggPathManager.projectInfosDir` 交给 `IssueReportBundleBuilder`。
2. `IssueReportBundleBuilder` 收集两个主工程快照，并以 `gradle_include_builds.txt` 记录的当前文件列表收集 included build 快照，避免上传目录中遗留的过期快照；存在且可解析的工程快照经过结构化脱敏后统一生成到 `diagnostics/project-info/`，设置为高敏感度且默认选中。
3. `ReportIssueDialog` 明确使用“必选 Jugg 日志、工程快照、其他候选项”的展示优先级，避免依赖输入列表的稳定排序。
4. 同步 AI 知识库以及中英文“报告问题”Wiki，说明默认勾选、可取消和脱敏行为。

## 验证方案

- 先扩展 `IssueReportBundleBuilderTest` 形成失败证据，覆盖主工程和 included build 快照、默认选中、结构化敏感字段脱敏、普通诊断字段保留、当前 included build 列表、残留或无效文件跳过及选择后入包。
- 执行定向测试：`./gradlew :main:test --tests com.sickworm.intellij.jugg.diagnostics.IssueReportBundleBuilderTest`。
- 执行 IDE 模块编译：`./gradlew :idea:compileKotlin`。
- 检查本次 diff、文档中英文一致性和 `git diff --check`。

## 非目标

- 不改变 Jugg 日志的必选行为。
- 不改变上传服务地址、重试策略、诊断包 manifest 格式或 Report ID。
- 不自动刷新或重建 project info；只上传报告发生时已经存在的快照。
