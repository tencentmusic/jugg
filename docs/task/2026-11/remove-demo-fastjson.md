# 移除 demo fastjson 依赖

## 目标与范围

安全扫描命中 demo 的 `com.alibaba:fastjson:2.0.2.android`。demo 无 fastjson API 调用，仅依赖变化回归使用该库；移除依赖并保留这些回归场景，不修改插件运行时行为。

## 实施

- 删除 app、library1 及 Kotlin/AGP 模板共 12 处依赖声明。
- 将 `LocalGradleCompileClientTest.testFetchLibraryChanges` 的第二个库切换为已有 RxJava，使用 3.1.8 → 3.1.0 → 3.1.9 → 3.1.8 验证版本切换、多库变化和回滚。
- RxJava 3.1.0 的传递依赖为 reactive-streams 1.0.3，3.1.8/3.1.9 为 1.0.4；保留传递依赖变化与回到全量基线时删除增量覆盖的断言。
- 用独立的 `testFetchRemovedLibraryChanges` 验证直接依赖及传递依赖移除、重复检查无变化、恢复基线。
- 两个用例建立基线前先初始化共享 demo 夹具，避免断言辅助方法首次访问 `context` 时隐式重建 demo、覆盖已记录的依赖基线。
- 远端子类沿用已有显式启用门禁，避免新增继承测试意外连接远端。
- 测试项目快照删除 fastjson 三项依赖并重排模块依赖索引，其他字段保持原值。

## 验证设计

这是依赖与测试夹具维护，无插件生产代码行为改动。已有依赖变更用例是回归 owner，新增移除用例保护有价值的可观察依赖变化结果，不引入生产代码测试 seam。

- 定向运行两个本地 Gradle client 集成测试，测试自身执行真实 demo Gradle 构建和依赖变化检测。
- 校验快照剩余依赖及模块引用与原快照语义一致。
- 核对全部模板不再声明 fastjson，检查 demo 解析后依赖树与 APK。
- 远端测试需要专用环境，本次不将门禁跳过视为远端验证通过。

## 验证结果

- 初轮测试缺少 ANDROID_HOME，补齐本机 SDK 环境后执行真实用例。
- demo 的 assembleDebug 构建成功；`testFetchRemovedLibraryChanges` 通过。
- 版本切换用例两次在 Gson 阶段发现额外的 `.gradle/jugg/jugg-runtime.jar`。确认 `checkChanges` 首次访问 `context` 会经 `AssembleAndroidProjectOnce` 隐式执行无 runtime 注入的构建，覆盖 client 已建立的基线；将共享夹具初始化前置后，两个定向集成测试均通过（0 failures / 0 errors）。
- 12 份 Gradle 配置均已移除目标声明；快照恰好删除 fastjson 三项，其他字段及模块依赖引用语义保持一致。
- APK 包含 20 个 DEX，均无 fastjson 包描述符。
- Gradle 快照中目标 2.0.2.android 及 fastjson2 运行时依赖已移除。ARouter 的构建期 KAPT/annotationProcessor 仍间接依赖 fastjson 1.2.69，未进入 APK；本次不扩大到处理器依赖治理。
- 最终命令：`ANDROID_HOME=<local-sdk> JAVA_HOME=<JDK17> ./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.gradle.LocalGradleCompileClientTest.testFetchLibraryChanges' --tests 'com.sickworm.intellij.jugg.gradle.LocalGradleCompileClientTest.testFetchRemovedLibraryChanges' --console=plain`；BUILD SUCCESSFUL（3m 15s）。
- `git diff --check` 通过。
- 远端环境未执行，漏洞扫描平台未重扫。

## 文档影响

仅清理 demo 依赖及更新回归载体，不改变产品功能、架构、使用入口或支持边界，无需修改 ai_knowledge 或 Wiki。保留历史任务记录中的 fastjson 描述。
