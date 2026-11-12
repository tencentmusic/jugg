# Quail/Rabbit 非 Android Gradle 模块读取

## 问题和证据

2026-11-12 android_demo_project 的 Rabbit 2 现场在 Sync 时把 kmpAnnotation 及 jvmMain/commonMain 记录为 not gradle module；app 的 ModuleOrderEntry 仍保存这两个依赖名。Gradle 快照分类已是 JavaLibrary，因此 Unknown 分类修复未覆盖此问题。

Rabbit 继承 Quail 的 getIdeModuleInfo：无 GradleAndroidModel 即返回 null，误将纯 KMP JVM 模块排除。读取 owner 是兼容层，不在 merger 删除引用。

## 实现边界

保留 Android 模型路径。缺少 Android 模型时，仅接受 External System 确认 GRADLE 且 externalProjectPath 为非空绝对路径的模块，返回最小 IdeModuleInfo。源码根、依赖、模块命名和 source set 身份继续由现有 CompileContextManager 流程读取。无可靠路径或 API 不可用时局部返回 null 并保留诊断，不强制整个流程回退 Gradle。

不猜 SDK、Manifest、build directory，不恢复按后缀清理引用补丁，不改其他兼容层。正常 Android 和已有增量流程保持原状；缺少必要模型时仍复用现有 Gradle 快照补充和用户主动完整构建入口，不增加自动完整构建。

## 验证

- 现场失败证据：12:04:57 IDE 模块过滤日志及 12:06:31 的悬空依赖警告。
- 测试价值：非 Android 模块的读取/排除是稳定行为，曾用临时 QuailModuleInfoTest 直接调用 Quail/Rabbit 的公开入口验证。修复前期望工程路径但返回 null；修复后两项测试通过，包含未关联、空路径、相对路径排除。
- 不保留该新自动化测试：通用 idea 测试 runtime 不含 Quail 专属类型；添加 Quail 编译桩虽使新测试通过，却与旧版 AS 模型及 SDK API 冲突，导致三个 dispatcher 用例和一个基线路径用例失败。已撤销该测试及 runtime 依赖，避免引入生产测试 seam 或扩建测试框架；保留临时验证结果，采用定向编译、现有回归与真实 API 签名检查作为替代验证。
- Quail/Rabbit 兼容模块与 idea 编译通过；通过 javap 核对当前 Rabbit 2 的 ExternalSystemApiUtil 真实 API 存在 String + Module 的 isExternalSystemAwareModule 和 getExternalProjectPath(Module)。使用反射避免扩大编译 Stub。撤回测试 runtime 改动后，42 项已有定向回归全部通过：merger 17、ModulePathMergePolicy 19、dispatcher 5、基线路径恢复 1。`git diff --check` 通过。
- 执行使用 JDK 17 启动 Gradle（避开 Gradle 7.3.3 Groovy 对 Java 21 的脚本解析限制），临时 init script 仅将 idea:test 的 executable 指向本机 JDK 21；仓库测试配置未改动。
- 已安装 Rabbit 的 Sync/增量现场验证待用户安装修复版本后执行。

## 文档

同步工程兼容层及项目模型知识库。用户操作入口、支持范围和恢复步骤未变，不更新 Wiki。
