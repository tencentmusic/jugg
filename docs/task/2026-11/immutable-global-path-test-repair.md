# 固定全局路径后的测试编译修复

Purpose: Restore test compilation and verify the refreshed AAPT2 prebuilt.
Decision: Adapt tests to immutable Runtime paths; isolate startup tests in child JVMs and restore settings per test.
Impact: Test infrastructure and regressions only; production behavior is unchanged.

## 问题与修复

`c85337942` 将全局路径改为启动时固定，但测试仍给 `rootDir` 赋值并调用已删除的路径参数，导致 `:main:compileTestKotlin` 失败。

- 设置测试使用 Gradle 已配置的隔离根目录，在每个用例前后保存、清空和恢复设置文件。
- 路径选择、迁移、资源解包与进程租约测试在临时用户目录的子 JVM 中运行，保留启动时路径不可变的设计。
- 更新设置仓库、资源管理器、全局锁和租约的调用；锁测试子进程继承测试根目录。
- Runtime 切换测试使用可持久化的 `compileOnSave`，替代已经成为常量的兼容部署开关。
- 移除纯路径拼接与私有实现测试，保留公开入口的配置目录选择、回退和不可写失败断言。

默认 JDK 21 下还观察到 `:jvmti_agent:buildInstrumentJar` 的旧版 D8 NPE；按 `docs/ai/06_testing.md` 使用 JDK 17 后成功构建，无需排除任务或修改生产逻辑。本次未实现 JDK 21 构建兼容性修复。

## 验证

使用本机 Corretto 17.0.17、Android SDK：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :main:test --no-daemon \
  --tests 'com.sickworm.intellij.jugg.project.runtime.*Test' \
  --tests 'com.sickworm.intellij.jugg.ide.bean.JuggSettings*Test' \
  --tests 'com.sickworm.intellij.jugg.compiler.IncrementalCompilerHelperTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.TooManyChangesTest' \
  --tests 'com.sickworm.intellij.jugg.ai.mcp.actions.ReportIssueMcpToolActionTest' \
  --tests 'com.sickworm.intellij.jugg.server.JuggHotUpdateManagerTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.overlay.ResourceCompile*Test'
```

结果：182 个用例通过，0 失败、0 跳过；其中 `ResourceCompileTest` 15 个、`ResourceCompileAabResGuardTest` 16 个。现有回归保护持久化、进程锁、迁移和真实资源编译结果，无需添加生产测试接口。

实际解包到 `main/build/test-jugg-home/resources/tools/darwin/aapt2-inclink-2.19.16` 的二进制与 `93e52cd23` 更新的仓库文件 SHA-256 一致：`d60b83e8736d77c8990450eff8f700e3ce2ba6d99a7b00929bde0aba758d7413`。

`git diff --check` 通过。没有功能或架构变更，无需更新知识库与 Wiki；Linux/Windows 未做运行验证。
