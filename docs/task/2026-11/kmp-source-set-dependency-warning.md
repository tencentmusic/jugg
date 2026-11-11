# KMP source set 悬空依赖告警

## 问题与现场

android_demo_project 在 Sync 后仍提示 kmpAnnotation.jvmMain / commonMain 不在 app 依赖中。2026-11-12 01:17～01:18 日志和两路 project info 确认：IDE app 保留 source set 引用但未提供对应模块，Gradle 提供 kmpAnnotation 工程模块。既有 merger 保留 IDE 非空依赖，再补 Gradle 工程依赖，导致悬空引用每次刷新都重新出现。实际 classpath 已含 JVM 输出，现场部署成功。

## 修复范围

在 JuggProjectInfoMerger 合并出口，仅移除同时满足下列条件的 `.jvmMain` / `.commonMain` 引用：引用在最终模块表中不存在，同一 owner 的 Gradle 依赖明确包含去掉 source set 后缀的工程模块，工程模块存在且依赖已成功接入最终图。复用已有补依赖和循环检测流程；可解析引用、未确认依赖、缺少目标和被循环检测拒绝的目标保持原样。

不修改输入 JSON、IDE 模块读取、classpath、告警等级或公共模型。Sync 与 Gradle fetch 共用出口。

## 验证

- 失败证据：新增回归修复前执行 19 项 merger 测试，1 项失败；实际依赖比预期多出两个 source set 引用。
- 测试价值：最终依赖图是编译消费者可观察的稳定契约，使用已有 L1 owner JuggProjectInfoMergerAndroidTestTest，不增加生产测试 seam。
- 定向回归：`./gradlew --offline :main:test --tests "com.sickworm.intellij.jugg.project.merger.JuggProjectInfoMergerAndroidTestTest" --tests "com.sickworm.intellij.jugg.project.ModulePathMergePolicyTest"` 通过；覆盖 merger 与模块路径策略。`git diff --check` 通过。
- 已安装 IDE 插件的现场运行：本次未替换插件，待安装包含修复的版本后确认告警消失。

## 文档

同步 04_engineering_project.md 的合并出口契约。Wiki 的用户操作、支持范围和恢复步骤未变化，无需更新。
