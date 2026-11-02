# Kotlin 模块元数据兼容回归样本

这些 `.kotlin_module` 来自对应版本的真实 `kotlin-compiler-embeddable`，不是用新版 metadata 库伪造旧版本号。
每个版本分别编译 baseline 和 incremental 两组源码，包含普通顶层函数、扩展函数及 `@JvmMultifileClass` 分片。
源码、编译命令和 Maven 依赖解析见本目录 `regenerate.py`。常规 JVM 回归只读取已提交样本，不下载或运行旧编译器。

| Kotlin compiler | 实际 metadata 版本 |
|---|---|
| 1.3.72 | 1.1.16 |
| 1.4.32 | 1.4.2 |
| 1.5.31 | 1.5.1 |
| 1.6.21 | 1.6.0 |
| 1.7.22 | 1.7.1 |
| 1.8.21 | 1.8.0 |
| 1.9.23 | 1.9.0 |
| 2.0.21 | 1.9.9999 |
| 2.1.20 | 2.1.0 |
| 2.2.21 | 2.2.0 |
| 2.3.20 | 2.3.0 |
| 2.4.21 | 2.4.0 |

## 重新生成与交叉验证

在仓库根目录使用 JDK 21 运行 Python 脚本。优先复用本地 Gradle artifact cache，缺少的依赖从 Maven Central 下载到系统临时目录，不修改工程依赖。

```bash
python3 main/src/test/resources/kotlin-module-compat/regenerate.py
# Gradle 7.3.3 使用 JDK 17；按本机路径设置 JAVA_HOME。
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compile.KmModuleMergerTest'
python3 main/src/test/resources/kotlin-module-compat/regenerate.py --verify-merged
```

JUnit 在 `main/build/kotlin-module-compat/<version>/compat.kotlin_module` 写出合并产物。交叉验证脚本把该索引与原版本编译出的 class 放在同一个 classpath，使用对应原 compiler 编译调用方，确认旧、新顶层函数和扩展函数均可解析。未使用跳过 metadata 版本检查的参数。

## 向后兼容依据

以本地 `kotlin-metadata-jvm:2.4.10` sources JAR 为准：

- `org/jetbrains/kotlin/metadata/deserialization/MetadataVersion.kt`：兼容检查拒绝 `0.x`、`1.0` 和超过上限的版本，允许历史版本；不是只允许同一 major。
- `org/jetbrains/kotlin/metadata/jvm/deserialization/ModuleMapping.kt`：按版本识别 1.4 以前无 flags 的文件布局，版本读写均来自文件头。
- `kotlin/metadata/jvm/KotlinModuleMetadata.kt`：`write()` 使用调用方指定的 metadata 版本序列化 package parts。

本回归保护 Jugg 使用的 facade 和 multifile package-part 索引，不扩大已有合并器对 module annotations、optional annotation classes 的写入能力。每个 minor 系列选取一个真实版本，具体 patch 版本见上表。
