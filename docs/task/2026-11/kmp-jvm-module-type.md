# KMP JVM 模块类型识别修复

## 问题与范围

android_demo_project 的 kmpAnnotation 应用了 Kotlin Multiplatform 并配置 JVM target，Gradle reader 只识别 Android 和 java-library，因而归类为 Unknown。同名 IDE 模块存在时，merger 的 Unknown 保护会保留 IDE 路径；错误目录还可能进入完整构建基线并在后续恢复。历史错误基线无法恢复，目前可用源码与构造输入验证分类缺口。

仅对应用 org.jetbrains.kotlin.multiplatform 且实际 target 的 platformType 为 jvm 的模块复用 JavaLibrary 类型。Android 模块类型优先；只有 common/JS/Native、未配置或无法读取 target 时保持 Unknown。利用既有反射读取边界兼容 Gradle plugin classloader，不增加公共模型、配置或生产测试 seam。

非 Android 模块仍沿用原有源码、任务与依赖读取；本次不扩展完整 KMP target 支持，不改变全量基线恢复规则。旧错误基线通过一次完整构建刷新。

按用户要求撤销 a31872a62 的 removeCoveredKmpSourceSetDependencies、两个专项测试和对应文档。source set 悬空引用告警可能继续出现，另行定位源头。

## 验证

- 测试价值：模块分类决定路径合并行为，是稳定的读取契约；复用 GradleProjectInfoReaderKotlinOptionsTest（L1）。
- 修复前：11 项定向测试中，KMP JVM 分类断言失败，实际 Unknown，预期 JavaLibrary；其余通过。
- 修复后：44 项定向测试全部通过（KotlinOptions 11、merger 17、脚本内容 14、Gradle 5/6 各 1），`git diff --check` 通过；覆盖 JVM 分类及自定义 build directory、非 JVM/未配置 target 保留 Unknown、Android 类型优先，已有 merger 路径回归，以及生成脚本内容与 Gradle 5/6 真实语法兼容。
- 用户现场：由用户安装修复插件、完整构建刷新基线后验证 KAPT/ARouter，当前未执行。

## 文档

同步 04_engineering_project.md 的类型识别与旧基线刷新说明。Wiki 操作入口和支持范围不变，无需更新。
