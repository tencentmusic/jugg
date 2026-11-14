# Kotlin 1.3～2.4 元数据回归与实现统一

## 问题与目标

旧实现使用 kotlinx-metadata-jvm 0.9.0，无法读取 2.4 元数据；新版实现与旧实现基本重复。确认新版读取、合并及写回覆盖旧格式后，统一实现，避免调用方选错读取器。

## 依据与方案

- 读取本地 kotlin-metadata-jvm 2.4.10 sources JAR 的 MetadataVersion、ModuleMapping、KotlinModuleMetadata：新版保留历史格式兼容；flags 字段按 1.4 分界读取和写入。
- 使用真实 Kotlin 1.3～1.9、2.0～2.4 各 minor compiler 生成 baseline/incremental 样本，覆盖普通 facade、扩展函数及 multifile 映射。
- 先验证新版合并器，再保留一个 KmModuleMerger.kt，移除 22 实现、版本选择接口及纯复制降级实现；编译、影响分析和版本错误处理均复用统一实现。
- 移除旧库依赖及旧 JvmMetadataVersion 类型，保留文件 metadata 的完整版本；损坏或不支持文件继续逐文件保留原字节。

## 测试价值与验证

稳定行为为旧、新声明在多轮合并后可解析，完整版本及 multifile 映射不丢失，损坏文件不影响有效文件；复用 L1 owner KmModuleMergerTest、CompileEffectAnalyzerTest，既有连续编译 Flow 由 IncrementalCompilerHelperTest 覆盖。不修改编译/部署编排。

- 精简前：12 组真实样本合并/写回/重载通过；12 个原 compiler 均能读取合并索引并编译调用方。
- 精简后：27 项定向测试通过（KmModuleMergerTest 8 项、CompileEffectAnalyzerTest 1 项矩阵、IncrementalCompilerHelperTest 18 项）；`:idea:compileKotlin` 通过。12 个原 compiler 再次读取统一实现写出的索引并编译旧、新声明调用方全部通过。
- `regenerate.py --verify-merged` 完成样本再生成及独立 compiler 检查；样本可重复生成，常规 JVM 回归无需网络或旧 toolchain。
- 静态检查：生产和测试源码不再引用旧 kotlinx.metadata 类型、22 实现、版本选择接口或复制实现；`git diff --check` 通过。
- 第一次精简后验证因 Gradle 7.3.3 在 JDK 21 下重新解析 build.gradle 报 Unsupported class file major version 65；改用本机 JDK 17 执行 Gradle，原 compiler 交叉验证仍使用 JDK 21。

## 文档范围

同步 02_compile_source 的实现、兼容原因和回归入口。没有新增用户操作、参数或支持范围，Wiki 无需修改。fixture 生成及独立 compiler 验证步骤保存在样本目录 README。
