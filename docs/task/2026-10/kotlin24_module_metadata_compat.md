# Kotlin 2.4 模块索引兼容修复

关联 Issue：https://github.com/tencentmusic/jugg/issues/68

## 问题与修改

Kotlin 2.4 的 `.kotlin_module` 无法被插件使用的 `kotlin-metadata-jvm:2.2.21` 读取。合并器吞掉解析异常后，仍把空模块按默认 2.2 版本写回。无源码预热使用同一编译链，因此能够在首次业务增量前破坏索引，导致顶层或扩展函数 `unresolved reference`。

- 升级 `kotlin-metadata-jvm` 至 `2.4.10`；旧版本仍走现有 `kotlinx-metadata-jvm:0.9.0`，成功解析后保持输入 metadata 版本。
- 两套合并器均保存首次读取的原始字节。解析失败时仅该文件降级为原字节恢复，使用调用方传入的工程 logger 记录包含路径、异常和能力限制的 warning；其余索引正常合并。合并器不获取 global logger，避免跨工程分发日志。
- 保留既有预热、输出目录及 friend path 语义。即使编译器覆盖了原索引，后置保存也会恢复不可读文件的原始内容；降级文件中的新增声明不能合并，已经清空的索引仍需完整 Gradle 构建恢复。
- 将已有 `KmModuleMergerTest` 从 `idea` 移到 `main`，使用临时目录和独立索引数据，保留旧格式往返验证，去掉不必要的 Android demo 构建前置。

## TDD 与测试价值

行为 owner 为 `KmModuleMergerForCompilation22` / `KmModuleMergerForCompilation`，测试统一归入 `com.sickworm.intellij.jugg.compile.KmModuleMergerTest`（L1）。断言最终文件内容、声明集合和 metadata 版本，保护独立的序列化兼容与产物保全契约，不新增生产代码测试 seam。

修改生产代码前执行 6 个测试，3 个失败：

1. Kotlin 2.4 预热后，索引期望版本 `2.4.0`，实际为 `2.2.0`。
2. 不可读文件被覆盖为 20 字节空索引。
3. 旧合并器未能在编译器覆盖后保留原始字节。

修改后 6 个测试全部通过，包含 2.4 预热与连续增量、2.2/2.3 版本保留、旧格式合并往返、损坏/不支持格式的逐文件保全，以及前置成功而后置解析失败的恢复。

## 验证结果

```bash
./gradlew :main:test --tests com.sickworm.intellij.jugg.compile.KmModuleMergerTest
./gradlew :idea:compileKotlin
./gradlew :main:dependencyInsight --dependency kotlin-stdlib --configuration runtimeClasspath
```

- 使用本机 JDK 17 验证，以上命令通过。仓库 Gradle 7.3.3 在 JDK 21 下重新解析修改后的构建脚本时报 `Unsupported class file major version 65`，因此仅对验证命令设置 `JAVA_HOME`，未修改仓库工具链。
- 依赖检查确认 `kotlin-stdlib` 解析为 `2.4.10`；Jugg 自身仍由 Kotlin 1.9.23 编译，main 测试和 idea 编译均成功。
- 在独立临时目录使用 Maven Central 的真实 Kotlin 2.4.10、2.3.20、2.2.21 编译器，围绕每次 CLI invocation 调用本次合并器，完成基线 → 无源码预热 → 引用已有扩展 → 新增扩展 → 同时引用两个扩展。三个版本均成功；基线索引为 48 字节，连续增量索引为 71 字节，metadata 分别保持 2.4、2.3、2.2。临时验证程序及输出位于 `/tmp/jugg-issue68-verification/` 和 `/tmp/jugg-issue68-runtime*.log`。
- `git diff --check` 通过。

未复现报告中的 Windows、Android Studio Quail、AGP 9.2.1、Gradle 9.4.1 完整工程现场；上述验证覆盖实际失败的 metadata 读写和扩展函数解析边界，不据此扩大全部环境组合的兼容声明。

## 文档同步

已更新 `docs/ai_knowledge/02_compile_source.md` 的元数据版本、逐文件保全和回归 owner。现有 Wiki 操作说明仍适用，本次不扩大整体 Kotlin/AGP/Gradle 兼容范围，不修改 Wiki 和 README 的范围表。
