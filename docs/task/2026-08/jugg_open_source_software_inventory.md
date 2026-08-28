# Jugg 开源软件清单基线

> 状态：附件字段与发行合规资产已完成；已收到法务补充要求，技术落实结果仍需法务确认
> 基线：Jugg `3.2.2-release` 插件包与 2026-08-08 当前工作树
> 目标：维护《附件1.开源软件信息表》的事实基线，并与插件发行包中的第三方合规资产保持一致
> 重要：本文件不是法律结论。机器清单见 `third_party/components.csv`，SPDX 2.3 SBOM 见 `third_party/sbom/jugg-third-party.spdx.json`；文档与代码或产物冲突时，以实际发布产物和上游许可证文件为准。

《附件1.开源软件信息表》实际只有 8 个字段：开源软件名称、版本号、开源协议、Copyright、协议链接、下载链接、是否修改、备注。本文件的 P0 仅表示“缺少这些字段会阻塞附件填写”，不代表完整的开源发布门禁。

## 1. 当前范围

本轮先覆盖两类最直接影响公开发布的组件：

1. `idea/build/distributions/jugg-3.2.2-release.zip` 实际携带的第三方 JAR、native executable 和静态链接组件。
2. 当前仓库直接提交、修改或再分发的第三方源码、JAR 和平台二进制。

本次附件明确不纳入以下仅用于开发、验证、文档或基础设施的组件：

- 测试依赖，如 JUnit、Mockito、kotlin-test、JSON-java。
- `android_demo_project`、测试 fixture 和 benchmark 使用的依赖。
- 仅用于开发或构建，且不随 Jugg 分发、不嵌入、不复制、也不由 Jugg 提供下载的 Gradle Distribution、Android Gradle Plugin、Kotlin Gradle Plugin、IntelliJ Gradle Plugin 等纯构建工具。
- Wiki/npm 依赖、CI action、脚本运行时工具和服务端/云服务。

这是本次附件的范围决策，不表示上述组件不是开源软件，也不作为以后其他审批或清单的自动排除依据。若它们进入 Jugg 对外发行物、随产品交付或成为产品运行时能力，应重新纳入附件判断。

Jugg 插件实际携带 `gradlew`、`gradlew.bat` 和 `gradle-wrapper.jar`，并会在用户项目已有 `gradle-wrapper.properties` 但启动文件缺失时复制这些文件。因此 Gradle Wrapper 属于再分发组件，不能按纯构建工具排除；Jugg 未携带 `gradle-wrapper.properties` 或完整 Gradle Distribution。

已完成当前基线的可见依赖核对：

- `cmd_line` 独立发行物相对 IDE 插件新增或替换的依赖已解析；Data Binding `7.4.2`、其传递依赖和 `slf4j-nop 1.7.36` 已在 §3.3 登记。
- 插件包 48 个第三方 JAR 的顶层包、许可证文件和主要内嵌代码已扫描；R8、Kotlin compiler、JSch 的内嵌组件已展开，Kotlin compiler 中的 PicoContainer 也已补列。
- AAPT2 的 `Android.bp` 与三平台二进制已交叉核对，补列了静态链接的 AOSP platform libraries、Expat、LLVM libc++、libpng、zlib 和 Protocol Buffers。
- JVMTI agent bundle 的嵌套 JAR、SO 动态依赖与源码构建配置已核对；自有 JAR 未发现新增第三方包，显式使用的 Android `liblog` 和 `libz` 已分别归入对应候选行。

本清单的附件字段和内部口径均已确认，可作为 Excel 回填基线；它仍不代表公司或法务已经批准开源。机器清单按协议义务组、修改状态、组件名称和版本排序；备注统一采用法务送审口径，仅说明使用关系、分发方式、实际修改内容，以及适用时的源码或许可证履行方式，不再重复“未修改”状态或记录开发核对过程。

发行合规资产已落地到根目录和 `third_party`：根目录使用法务提供的完整 `LICENSE`；`third_party` 包含 104 行机器清单、许可证文本、GPL/LGPL/MPL/CDDL 对应源码、第三方修改 changelog、集成边界说明和 SPDX 2.3 SBOM。`:idea:buildPlugin` 会将 LICENSE、NOTICE、许可证、公开源码 revision 与校验值、修改 changelog、集成边界说明、清单和 SBOM 复制到插件根目录，不重复打包 `sources` payload；`:idea:verifyThirdPartyCompliance` 在 LICENSE 副本不一致、缺失文件、仓库源码 SHA-256 或 CI Git 状态不匹配、插件重新携带源码 payload、许可证选择回退或 SBOM package 数量不为 104 时使构建失败。

法务补充关注的集成事实记录在 `third_party/INTEGRATION.md`。其中只有 `rsync`、`sshpass` 通过独立进程避免与插件 JVM 共享地址空间；Checker Qual 3.33.0/3.5.0 选择 MIT，JavaBeans Activation Framework 选择 CDDL-1.1，OpenJDK JVMTI header 使用带 Classpath Exception 的 GPL-2.0，不能把这四项描述为“均已通过进程隔离”。Trove4J 和 juniversalchardet 以独立 JAR 由 JVM 动态加载。上述内容是代码与产物事实，最终法律判断仍由法务确认。

附件说明中的“使用”范围比“进入插件发布包”更广，还包括动态或静态链接、随附文件、通过插件或服务器下载、网络或云服务，以及开源软件的 API、代码和文件。因此 Stub API 替换只改变仓库中的文件形态，不会自动免除对应上游 API 的填报判断。

## 2. 事实依据

| 证据 | 用途 |
|---|---|
| `idea/build/distributions/jugg-3.2.2-release.zip` | 确认实际插件包内的 62 个 JAR，其中 14 个为 Jugg 自有/定制模块 JAR、48 个为第三方 JAR；`main` JAR 还携带 Gradle Wrapper、rsync 和 sshpass |
| `main/build.gradle`、`idea/build.gradle` | 确认直接依赖、版本、排除项和编译期依赖 |
| `main/libs` | 确认 R8、SQLite JDBC lite、dex2jar、修改版 Kotlin Android Extensions、重打包 ASM 等预编译 JAR |
| `main/src/main/resources/tools/darwin` | 确认随包携带的 `rsync 3.4.1` 和 `sshpass 1.10` |
| `aapt2-inclink/src/main/resources/tools` | 确认三平台定制 AAPT2 inclink 二进制 |
| `jvmti_agent/src/main/cpp` | 确认直接包含并修改的 AOSP JVMTI、Slicer 源码及 Apache-2.0 文件头 |
| `deploy_compat/*/libs` | 确认仓库直接提交的多版本 Android Studio / Android Plugin JAR |
| `jvmti_agent/framework_class_stub` | 确认由挑选、简化的 Android framework 源码生成的编译桩 |
| 第三方 JAR 内 `LICENSE`、`NOTICE`、`pom.properties` | 核对许可证、Copyright 和 Maven 坐标 |
| `docs/task/2026-07/open_source_readiness_checklist.md` §3 P0-01 | 确认第三方二进制与知识产权归属是公开发布阻断项 |
| `LICENSE`、`third_party`、`THIRD_PARTY_NOTICES.md` | 法务提供的项目许可证，以及发行包实际分发的机器清单、许可证、公开源码 revision 与校验值、修改 changelog、集成边界说明、NOTICE 和 SPDX SBOM；对应源码 payload 保留在公开 Git revision |
| `idea/build.gradle` 的 `verifyThirdPartyCompliance` | 校验发行包合规资产完整性、仓库源码 SHA-256 与 CI Git 状态、源码 revision、插件内无源码 payload、固定许可证选择和 SBOM package 数量 |

## 3. 插件发布包中的 JVM 组件

下表的协议链接和下载链接是后续填表候选值。最终填写前必须逐项验证 URL、tag、Copyright 年份及所选许可证分支。

| 开源软件名称 | 版本 | 开源协议 | Copyright 初稿 | 协议链接候选 | 下载链接候选 | 修改 | 备注 |
|---|---|---|---|---|---|---|---|
| Kotlin compiler/runtime suite | 1.9.23 | Apache-2.0 | Copyright © 2010-2023 JetBrains s.r.o. and respective authors and developers | [LICENSE](https://github.com/JetBrains/kotlin/blob/v1.9.23/license/LICENSE.txt) | [source zip](https://github.com/JetBrains/kotlin/archive/refs/tags/v1.9.23.zip) | 否 | 合并记录 compiler、daemon、reflect、script runtime、stdlib-jdk7/jdk8；版本和 NOTICE 已核对；compiler embeddable 内嵌依赖已在 §3.2 展开 |
| Kotlin Android Extensions | 1.9.23 | Apache-2.0 | Copyright © 2010-2023 JetBrains s.r.o. and respective authors and developers | [LICENSE](https://github.com/JetBrains/kotlin/blob/v1.9.23/license/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-android-extensions/1.9.23/kotlin-android-extensions-1.9.23.jar) | 是 | 与官方 JAR 相比有 4 个 class 不同：`AndroidComponentRegistrar` 移除 `reportRemovedError` 调用；另外 3 个 class 仅多出空的 parameter-annotation attribute，方法字节码未变化 |
| Kotlin Standard Library | 2.2.21 | Apache-2.0 | Copyright © 2010-2024 JetBrains s.r.o. and respective authors and developers | [LICENSE](https://github.com/JetBrains/kotlin/blob/v2.2.21/license/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.jar) | 否 | 由 Kotlin Metadata JVM 传递进入发布包；manifest 已核对 |
| Kotlin Metadata JVM | 2.2.21 | Apache-2.0 | Copyright © 2010-2024 JetBrains s.r.o. and respective authors and developers | [LICENSE](https://github.com/JetBrains/kotlin/blob/v2.2.21/license/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-metadata-jvm/2.2.21/kotlin-metadata-jvm-2.2.21.jar) | 否 | 直接依赖；manifest 已核对 |
| kotlinx-metadata-jvm | 0.9.0 | Apache-2.0 | Copyright © 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-metadata-jvm/0.9.0/kotlinx-metadata-jvm-0.9.0.jar) | 否 | POM 和 0.9.0 source JAR 文件头已核对；该版本没有可稳定使用的独立 tag LICENSE 链接 |
| kotlinx-coroutines-core-jvm | 1.6.4 | Apache-2.0 | Copyright © 2016-2022 JetBrains s.r.o. | [LICENSE](https://github.com/Kotlin/kotlinx.coroutines/blob/1.6.4/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar) | 否 | 版本、协议和 source JAR 文件头已核对 |
| IntelliJ IDEA Annotations | 13.0 | Apache-2.0 | Copyright © 2000-2012 JetBrains s.r.o. | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.jar) | 否 | Maven POM 和 source JAR 文件头已核对 |
| Android Tools Annotations | 31.7.3 | Apache-2.0 | Copyright © 2005-2013 The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Google Maven JAR](https://dl.google.com/dl/android/maven2/com/android/tools/annotations/31.7.3/annotations-31.7.3.jar) | 否 | 坐标为 `com.android.tools:annotations:31.7.3`；原清单误合并为 JetBrains Annotations，已按 JAR NOTICE 更正 |
| Trove4J，JetBrains fork | 1.0.20200330 | LGPL-2.1-or-later | Copyright © 2002 Eric D. Friedman and Trove contributors | [LGPL-2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html) | [Maven JAR](https://repo1.maven.org/maven2/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar) | 否 | source JAR 文件头声明 LGPL 2.1 或后续版本；上游没有版本 tag，协议链接使用固定许可证文本 |
| OkHttp | 4.12.0 | Apache-2.0 | Copyright © 2012-2019 Square, Inc., The Android Open Source Project and contributors | [LICENSE](https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar) | 否 | 版本、协议和 source JAR 文件头已核对 |
| Okio | 3.6.0 | Apache-2.0 | Copyright © 2014-2021 Square, Inc. and others | [LICENSE](https://github.com/square/okio/blob/parent-3.6.0/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar) | 否 | OkHttp 传递依赖；source JAR 文件头已核对 |
| Gson | 2.10.1 | Apache-2.0 | Copyright © 2008-2021 Google Inc., The Android Open Source Project and Gson authors | [LICENSE](https://github.com/google/gson/blob/gson-parent-2.10.1/LICENSE) | [Maven JAR](https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar) | 否 | 版本、协议和 source JAR 文件头已核对 |
| Eclipse JGit | 6.8.0.202311291450-r | EDL-1.0 | Copyright © 2007 Eclipse Foundation, Inc. and its licensors; JGit contributors | [LICENSE](https://github.com/eclipse-jgit/jgit/blob/v6.8.0.202311291450-r/LICENSE) | [Maven JAR](https://repo1.maven.org/maven2/org/eclipse/jgit/org.eclipse.jgit/6.8.0.202311291450-r/org.eclipse.jgit-6.8.0.202311291450-r.jar) | 否 | JAR `about.html` 和版本 tag 均为 EDL-1.0；原 EPL-2.0 已更正 |
| JavaEWAH | 1.2.3 | Apache-2.0 | Copyright © 2009-2016 Daniel Lemire, Cliff Moon, David McIntosh and contributors | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/com/googlecode/javaewah/JavaEWAH/1.2.3/JavaEWAH-1.2.3.jar) | 否 | JGit 传递依赖；POM 和 source JAR 文件头已核对 |
| JSch，mwiede fork | 0.2.16 | BSD-3-Clause | Copyright © 2002-2015 Atsuhiko Yamanaka, JCraft, Inc. | [LICENSE](https://github.com/mwiede/jsch/blob/jsch-0.2.16/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/com/github/mwiede/jsch/0.2.16/jsch-0.2.16.jar) | 否 | JAR 还内嵌 JZlib 和 jBCrypt 代码，已拆为下两行 |
| JZlib，JSch 内嵌代码 | 无 | BSD-3-Clause | Copyright © 2000-2011 ymnk, JCraft, Inc. | [LICENSE](https://github.com/mwiede/jsch/blob/jsch-0.2.16/LICENSE.JZlib.txt) | [JSch JAR](https://repo1.maven.org/maven2/com/github/mwiede/jsch/0.2.16/jsch-0.2.16.jar) | 否 | JSch JAR 未声明内嵌 JZlib 的独立版本，按附件规则版本填“无” |
| jBCrypt，JSch 内嵌代码 | 无 | ISC | Copyright © 2006 Damien Miller | [LICENSE](https://github.com/mwiede/jsch/blob/jsch-0.2.16/LICENSE.jBCrypt.txt) | [JSch JAR](https://repo1.maven.org/maven2/com/github/mwiede/jsch/0.2.16/jsch-0.2.16.jar) | 否 | JSch JAR 未声明内嵌 jBCrypt 的独立版本，按附件规则版本填“无” |
| JavaParser Core | 3.17.0 | Apache-2.0 | Copyright © 2007-2010 Júlio Vilmar Gesser; 2011-2020 The JavaParser Team | [LICENSE](https://github.com/javaparser/javaparser/blob/javaparser-parent-3.17.0/LICENSE) | [Maven JAR](https://repo1.maven.org/maven2/com/github/javaparser/javaparser-core/3.17.0/javaparser-core-3.17.0.jar) | 否 | 本发行选择 Apache-2.0；上游允许用户在 Apache-2.0 与 LGPL-3.0-or-later 中选择 |
| ClassGraph | 4.8.110 | MIT | Copyright © 2019 Luke Hutchison | [LICENSE](https://github.com/classgraph/classgraph/blob/classgraph-4.8.110/LICENSE-ClassGraph.txt) | [Maven JAR](https://repo1.maven.org/maven2/io/github/classgraph/classgraph/4.8.110/classgraph-4.8.110.jar) | 否 | JAR 内 LICENSE 已核对 |
| Android Data Binding compiler suite | 8.7.3 | Apache-2.0 | Copyright © 2005-2017 The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [compiler JAR](https://dl.google.com/dl/android/maven2/androidx/databinding/databinding-compiler/8.7.3/databinding-compiler-8.7.3.jar) | 否 | 合并记录 compiler、compiler-common、common、baseLibrary；JAR NOTICE 已核对；内嵌 ANTLR 另列 |
| Android Data Binding compiler suite | 7.4.2 | Apache-2.0 | Copyright © 2005-2017 The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [compiler JAR](https://dl.google.com/dl/android/maven2/androidx/databinding/databinding-compiler/7.4.2/databinding-compiler-7.4.2.jar) | 否 | `cmd_line` 独立发行物使用的版本；合并记录 compiler、compiler-common、common、baseLibrary；与 8.7.3 是不同版本 |
| ANTLR 4 Runtime，Data Binding 内嵌重定位代码 | 4.5.3 | BSD-3-Clause | Copyright © 2015 Terence Parr, Sam Harwell | [LICENSE](https://github.com/antlr/antlr4/blob/4.5.3/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/antlr/antlr4-runtime/4.5.3/antlr4-runtime-4.5.3.jar) | 否 | `databinding-compiler-common-8.7.3.jar` 内包含 `android.databinding.internal.org.antlr` 重定位类；由上游 Data Binding 打包，非 Jugg 修改 |
| Jetifier Core | 1.0.0-beta10 | Apache-2.0 | Copyright © 2018 The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Google Maven JAR](https://dl.google.com/dl/android/maven2/com/android/tools/build/jetifier/jetifier-core/1.0.0-beta10/jetifier-core-1.0.0-beta10.jar) | 否 | Data Binding 传递依赖；source JAR 文件头已核对 |
| JavaPoet | 1.10.0 | Apache-2.0 | Copyright © 2014-2016 Google, Inc. and Square, Inc. | [LICENSE](https://github.com/square/javapoet/blob/javapoet-1.10.0/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/com/squareup/javapoet/1.10.0/javapoet-1.10.0.jar) | 否 | source JAR 文件头已核对 |
| ArscBlamer | 1.2.0 | Apache-2.0 | Copyright © 2016 Google Inc. | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/io/github/shiqos/arscblamer/1.2.0/arscblamer-1.2.0.jar) | 否 | 上游仓库未提交独立 LICENSE 文件，但发布 POM 声明 Apache-2.0，仓库和 source JAR 中的实际源码均保留 Google 2016 Apache-2.0 文件头 |
| R8 | 8.4.21 | BSD-3-Clause，并含第三方许可证 | Copyright © 2016 The R8 project authors | [LICENSE](https://r8.googlesource.com/r8/+/refs/tags/8.4.21/LICENSE) | [R8 JAR](https://storage.googleapis.com/r8-releases/raw/8.4.21/r8.jar) | 否 | 仓库 JAR 与官方 JAR SHA-256 完全一致；JAR `LICENSE` 列出 53 个 distributed library 条目，已在主表及 §3.1 逐项归并或单列 |
| Xerial SQLite JDBC | 3.42.0.0 | Apache-2.0、BSD-2-Clause；SQLite 核心 Public Domain | Copyright © Taro L. Saito and Xerial contributors; Copyright © 2006 David Crawshaw; SQLite authors | [LICENSE](https://github.com/xerial/sqlite-jdbc/blob/3.42.0.0/LICENSE) | [官方 JAR](https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.42.0.0/sqlite-jdbc-3.42.0.0.jar) | 是 | `lite` 版仅删除不支持平台的 native library，保留 macOS 双架构、Linux x86_64、Windows x86_64；未新增文件 |
| dex2jar dex-reader/dex-writer | 2.1 | Apache-2.0 | Copyright © 2009-2014 Panxiaobo and contributors | [LICENSE](https://github.com/pxb1988/dex2jar/blob/v2.1/LICENSE.txt) | [source zip](https://github.com/pxb1988/dex2jar/archive/refs/tags/v2.1.zip) | 否 | 合并记录 dex-reader、dex-reader-api、dex-writer；JAR NOTICE 已核对 |
| ASM | 9.8 | BSD-3-Clause | Copyright © 2000-2011 INRIA, France Telecom | [LICENSE](https://gitlab.ow2.org/asm/asm/-/blob/ASM_9_8/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/ow2/asm/asm/9.8/asm-9.8.jar) | 是 | 三个 JAR 将 `org.objectweb.asm` 重定位到 Jugg 包名；class 数量基本一致，`asm-commons` 还移除了 `module-info.class` |
| Apache Commons IO | 2.13.0 | Apache-2.0 | Copyright © 2002-2023 The Apache Software Foundation | [LICENSE](https://github.com/apache/commons-io/blob/rel/commons-io-2.13.0/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/commons-io/commons-io/2.13.0/commons-io-2.13.0.jar) | 否 | JAR NOTICE 已核对 |
| Apache Commons Codec | 1.16.0 | Apache-2.0 | Copyright © 2002-2023 The Apache Software Foundation | [LICENSE](https://github.com/apache/commons-codec/blob/rel/commons-codec-1.16.0/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/commons-codec/commons-codec/1.16.0/commons-codec-1.16.0.jar) | 否 | JGit 传递依赖；JAR NOTICE 已核对 |
| juniversalchardet | 1.0.3 | MPL-1.1 | Copyright © 1998 Netscape Communications Corporation; Kohei TAKETA and Java port contributors | [MPL-1.1](https://www.mozilla.org/MPL/1.1/) | [Maven JAR](https://repo1.maven.org/maven2/com/googlecode/juniversalchardet/juniversalchardet/1.0.3/juniversalchardet-1.0.3.jar) | 否 | 本发行按上游 POM 选择 MPL-1.1；源码允许 MPL/GPL/LGPL 三选一 |
| Jakarta XML Binding API | 2.3.2 | EDL-1.0（BSD-3-Clause） | Copyright © 2017-2018 Oracle and/or its affiliates | [EDL-1.0](https://www.eclipse.org/org/documents/edl-v10.php) | [Maven JAR](https://repo1.maven.org/maven2/jakarta/xml/bind/jakarta.xml.bind-api/2.3.2/jakarta.xml.bind-api-2.3.2.jar) | 否 | JAR LICENSE/NOTICE 已核对 |
| JAXB Runtime、TXW2 | 2.3.2 | EDL-1.0（BSD-3-Clause） | Copyright © 2018 Oracle and/or its affiliates | [EDL-1.0](https://www.eclipse.org/org/documents/edl-v10.php) | [runtime JAR](https://repo1.maven.org/maven2/org/glassfish/jaxb/jaxb-runtime/2.3.2/jaxb-runtime-2.3.2.jar) | 否 | 两个 artifact 的 JAR LICENSE/NOTICE 已核对；可按审批口径拆行 |
| Fast Infoset | 1.2.16 | Apache-2.0 | Copyright © 2012-2018 Oracle and/or its affiliates | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/com/sun/xml/fastinfoset/FastInfoset/1.2.16/FastInfoset-1.2.16.jar) | 否 | 本发行选择 Apache-2.0；上游同时提供 Apache-2.0 和 EDL-1.0 |
| StAX-Ex | 1.8.1 | EDL-1.0（BSD-3-Clause） | Copyright © 2017 Oracle and/or its affiliates | [EDL-1.0](https://www.eclipse.org/org/documents/edl-v10.php) | [Maven JAR](https://repo1.maven.org/maven2/org/jvnet/staxex/stax-ex/1.8.1/stax-ex-1.8.1.jar) | 否 | JAR LICENSE/NOTICE 已核对 |
| istack-commons-runtime | 3.0.8 | EDL-1.0（BSD-3-Clause） | Copyright © 2017 Oracle and/or its affiliates | [EDL-1.0](https://www.eclipse.org/org/documents/edl-v10.php) | [Maven JAR](https://repo1.maven.org/maven2/com/sun/istack/istack-commons-runtime/3.0.8/istack-commons-runtime-3.0.8.jar) | 否 | JAR LICENSE/NOTICE 已核对 |
| Jakarta Activation API | 1.2.1 | EDL-1.0（BSD-3-Clause） | Copyright © 2018 Oracle and/or its affiliates | [EDL-1.0](https://www.eclipse.org/org/documents/edl-v10.php) | [Maven JAR](https://repo1.maven.org/maven2/jakarta/activation/jakarta.activation-api/1.2.1/jakarta.activation-api-1.2.1.jar) | 否 | JAR LICENSE/NOTICE 已核对 |
| Auto Common | 0.10 | Apache-2.0 | Copyright © 2013-2017 Google, Inc.; Copyright © 2013 Square, Inc. | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/com/google/auto/auto-common/0.10/auto-common-0.10.jar) | 否 | Data Binding 传递依赖；POM 和 source JAR 文件头已核对 |

### 3.1 R8 8.4.21 内嵌依赖新增候选行

以下组件不作为独立 JAR 出现在 Jugg 插件目录，但由官方 R8 `8.4.21` 打入 `r8.jar`。仓库中的 R8 JAR 与官方发布 JAR SHA-256 完全相同，因此“修改”统一按 Jugg 未修改填写。

| 开源软件名称 | 版本 | 开源协议 | Copyright 初稿 | 协议链接候选 | 下载链接候选 | 修改 | 备注 |
|---|---|---|---|---|---|---|---|
| Guava | 32.1.2-jre | Apache-2.0 | Copyright © 2005-2021 The Guava Authors | [版本 POM](https://github.com/google/guava/blob/v32.1.2/guava/pom.xml) | [Maven JAR](https://repo1.maven.org/maven2/com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar) | 否 | R8 内嵌；source JAR 文件头和 R8 源码版本常量已核对 |
| Guava FailureAccess | 1.0.1 | Apache-2.0 | Copyright © 2018 The Guava Authors | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar) | 否 | Guava 传递依赖；R8 内嵌 |
| JSR-305 Annotations | 3.0.2 | Apache-2.0 | Copyright © 2005 Brian Goetz and contributors | [版本 POM](https://repo1.maven.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom) | [Maven JAR](https://repo1.maven.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar) | 否 | Guava 传递依赖；POM、source JAR 和 R8 LICENSE 均按 Apache-2.0 记录 |
| Checker Qual | 3.33.0 | MIT | Copyright © 2004-present Checker Framework developers | [LICENSE](https://github.com/typetools/checker-framework/blob/checker-framework-3.33.0/checker-qual/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/checkerframework/checker-qual/3.33.0/checker-qual-3.33.0.jar) | 否 | Guava 传递依赖；JAR LICENSE 已核对 |
| Error Prone Annotations | 2.18.0 | Apache-2.0 | Copyright © 2014-2021 The Error Prone Authors | [LICENSE](https://github.com/google/error-prone/blob/v2.18.0/COPYING) | [Maven JAR](https://repo1.maven.org/maven2/com/google/errorprone/error_prone_annotations/2.18.0/error_prone_annotations-2.18.0.jar) | 否 | Guava 传递依赖；source JAR 文件头已核对 |
| J2ObjC Annotations | 2.8 | Apache-2.0 | Copyright © 2012 Google Inc. | [版本 POM](https://repo1.maven.org/maven2/com/google/j2objc/j2objc-annotations/2.8/j2objc-annotations-2.8.pom) | [Maven JAR](https://repo1.maven.org/maven2/com/google/j2objc/j2objc-annotations/2.8/j2objc-annotations-2.8.jar) | 否 | Guava 的 optional 编译依赖，但 R8 LICENSE 明确列为 distributed library；source JAR 已核对 |
| fastutil | 7.2.1 | Apache-2.0 | Copyright © 2002-2017 Sebastiano Vigna, Paolo Boldi and contributors | [LICENSE](https://github.com/vigna/fastutil/blob/7.2.1/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/it/unimi/dsi/fastutil/7.2.1/fastutil-7.2.1.jar) | 否 | R8 内嵌；source JAR 文件头已核对 |
| ASM | 9.6 | BSD-3-Clause | Copyright © 2000-2011 INRIA, France Telecom | [LICENSE](https://gitlab.ow2.org/asm/asm/-/blob/ASM_9_6/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/ow2/asm/asm/9.6/asm-9.6.jar) | 否 | 合并记录 R8 内嵌的 core、tree、analysis、commons、util；与 Jugg 重打包的 ASM 9.8 是不同版本和修改状态 |
| Kotlin Standard Library | 1.9.21 | Apache-2.0 | Copyright © 2010-2023 JetBrains s.r.o. and Kotlin contributors | [LICENSE](https://github.com/JetBrains/kotlin/blob/v1.9.21/license/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.21/kotlin-stdlib-1.9.21.jar) | 否 | R8 内嵌，由 kotlinx-metadata-jvm 0.9.0 引入；与插件顶层 2.2.21 并存 |
| Kotlin Reflect | 1.9.0 | Apache-2.0 | Copyright © 2010-2023 JetBrains s.r.o. and Kotlin contributors | [LICENSE](https://github.com/JetBrains/kotlin/blob/v1.9.0/license/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-reflect/1.9.0/kotlin-reflect-1.9.0.jar) | 否 | Android Tools SDK Common 传递依赖，R8 内嵌 |
| AAPT2 Proto | 8.2.0-rc01-10154469 | Apache-2.0 | Copyright © 2005-2008 The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Google Maven JAR](https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2-proto/8.2.0-rc01-10154469/aapt2-proto-8.2.0-rc01-10154469.jar) | 否 | R8 源码直接声明 alpha10，完整依赖解析选中 rc01；JAR NOTICE 已核对 |
| Protocol Buffers Java | 3.19.3 | BSD-3-Clause | Copyright © 2008 Google Inc. and protobuf contributors | [LICENSE](https://github.com/protocolbuffers/protobuf/blob/v3.19.3/LICENSE) | [Maven JAR](https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/3.19.3/protobuf-java-3.19.3.jar) | 否 | R8 Resource Shrinker 内嵌；不同于 AAPT2 inclink 中版本尚不明确的 protobuf |
| Android Tools libraries | 31.2.0-rc01 | Apache-2.0 | Copyright © 2005-2013 The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [sdk-common JAR](https://dl.google.com/dl/android/maven2/com/android/tools/sdk-common/31.2.0-rc01/sdk-common-31.2.0-rc01.jar) | 否 | 合并记录 annotations、common、sdk-common、layoutlib-api、analytics shared/protos、ddmlib、sdklib、repository、dvlib；版本一致且均为 AOSP Apache-2.0 |
| Java Native Access / JNA Platform | 5.6.0 | Apache-2.0 | Copyright © 2007-2020 Timothy Wall, Olivier Chafik and contributors | [LICENSE](https://github.com/java-native-access/jna/blob/5.6.0/LICENSE) | [Maven JAR](https://repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.6.0/jna-platform-5.6.0.jar) | 否 | 本发行选择 Apache-2.0；Android Tools Common 传递依赖，R8 内嵌 |
| kXML2 | 2.3.0 | BSD-style AND Public Domain | Copyright © 2002-2004 Stefan Haustein | [版本 POM](https://repo1.maven.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.pom) | [Maven JAR](https://repo1.maven.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.jar) | 否 | Android Tools 传递依赖；版本 POM 同时声明 BSD-style 和 Public Domain |
| Jimfs | 1.1 | Apache-2.0 | Copyright © 2013-2016 Google Inc. | [LICENSE](https://github.com/google/jimfs/blob/v1.1/LICENSE) | [Maven JAR](https://repo1.maven.org/maven2/com/google/jimfs/jimfs/1.1/jimfs-1.1.jar) | 否 | Android Tools Repository 传递依赖；source JAR 文件头已核对 |
| JavaBeans Activation Framework | 1.2.0 | CDDL-1.1 | Copyright © 1997-2017 Oracle and/or its affiliates | [LICENSE](https://github.com/javaee/activation/blob/JAF-1_2_0/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/com/sun/activation/javax.activation/1.2.0/javax.activation-1.2.0.jar) | 否 | 本发行选择 CDDL-1.1；Android Tools Repository 传递依赖，与 Jakarta Activation API 1.2.1 是不同 artifact |
| Apache Commons Compress | 1.21 | Apache-2.0；部分 sevenz 代码来自 Public Domain LZMA SDK | Copyright © 2002-2021 The Apache Software Foundation | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.21/commons-compress-1.21.jar) | 否 | JAR NOTICE 已核对；R8 内嵌 |
| Apache HttpCore | 4.4.16 | Apache-2.0 | Copyright © 2005-2022 The Apache Software Foundation | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/org/apache/httpcomponents/httpcore/4.4.16/httpcore-4.4.16.jar) | 否 | Android Tools SDK Common 传递依赖；JAR NOTICE 已核对 |
| Apache HttpClient / HttpMime | 4.5.6 | Apache-2.0 | Copyright © 1999-2018 The Apache Software Foundation | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [HttpClient JAR](https://repo1.maven.org/maven2/org/apache/httpcomponents/httpclient/4.5.6/httpclient-4.5.6.jar) | 否 | 合并记录 HttpClient 和 HttpMime；JAR NOTICE 已核对 |
| Apache Commons Logging | 1.2 | Apache-2.0 | Copyright © 2003-2014 The Apache Software Foundation | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/commons-logging/commons-logging/1.2/commons-logging-1.2.jar) | 否 | HttpClient 传递依赖；JAR NOTICE 已核对 |
| Apache Commons Codec | 1.10 | Apache-2.0 | Copyright © 2002-2014 The Apache Software Foundation | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/commons-codec/commons-codec/1.10/commons-codec-1.10.jar) | 否 | HttpClient 传递依赖；与插件顶层 1.16.0 并存 |
| javax.inject / JSR-330 | 1 | Apache-2.0 | Copyright © 2009 The JSR-330 Expert Group | [版本 POM](https://repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.pom) | [Maven JAR](https://repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar) | 否 | Android Tools SDK Common 传递依赖；source JAR 文件头已核对 |
| Bouncy Castle Provider / PKIX | 1.67 | MIT | Copyright © 2000-2020 The Legion of the Bouncy Castle Inc. | [LICENSE](https://github.com/bcgit/bc-java/blob/r1rv67/LICENSE.html) | [Provider JAR](https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15on/1.67/bcprov-jdk15on-1.67.jar) | 否 | 合并记录 bcprov 和 bcpkix；R8 内嵌 |
| Xerces2 Java | 2.12.0 | Apache-2.0 | Copyright © 1999-2018 The Apache Software Foundation; IBM, Sun and other contributors | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/xerces/xercesImpl/2.12.0/xercesImpl-2.12.0.jar) | 否 | JAR NOTICE 已核对；R8 内嵌 |
| Apache XML Commons XML APIs | 1.4.01 | Apache-2.0、SAX License、W3C Software Notice and License | Copyright © 1999-2009 The Apache Software Foundation; Copyright © 2000 W3C and others | [版本 POM](https://repo1.maven.org/maven2/xml-apis/xml-apis/1.4.01/xml-apis-1.4.01.pom) | [Maven JAR](https://repo1.maven.org/maven2/xml-apis/xml-apis/1.4.01/xml-apis-1.4.01.jar) | 否 | Xerces 传递依赖；JAR LICENSE/NOTICE 已核对 |

R8 的 ListenableFuture `9999.0-empty-to-avoid-conflict-with-guava` 是无 class 的冲突占位 artifact，本轮不单独作为开源代码行；如果审批口径要求连空 artifact 也登记，可在备注中追加。

### 3.2 Kotlin compiler 1.9.23 内嵌依赖新增候选行

以下组件由官方 `kotlin-compiler-embeddable-1.9.23.jar` 合并并大多重定位包名。Jugg 使用的 compiler JAR 本身未修改，因此这些行的“修改”按 Jugg 未修改填写；`kotlin-android-extensions-1.9.23_modified.jar` 仍按前表单独填“是”。

| 开源软件名称 | 版本 | 开源协议 | Copyright 初稿 | 协议链接候选 | 下载链接候选 | 修改 | 备注 |
|---|---|---|---|---|---|---|---|
| JLine 3 | 3.3.1 | BSD-3-Clause | Copyright © 2002-2017 the original author or authors | [LICENSE](https://github.com/jline/jline3/blob/jline-3.3.1/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/jline/jline/3.3.1/jline-3.3.1.jar) | 否 | Kotlin compiler 内嵌并重定位；source JAR 文件头已核对 |
| Jansi | 1.16 | Apache-2.0 | Copyright © 2009-2017 the original authors | [版本 POM](https://repo1.maven.org/maven2/org/fusesource/jansi/jansi/1.16/jansi-1.16.pom) | [Maven JAR](https://repo1.maven.org/maven2/org/fusesource/jansi/jansi/1.16/jansi-1.16.jar) | 否 | Kotlin compiler 内嵌并重定位；source JAR 文件头已核对 |
| Protocol Buffers，Kotlin relocated fork | 2.6.1-1 | BSD-3-Clause | Copyright © 2008 Google Inc. | [upstream LICENSE](https://github.com/protocolbuffers/protobuf/blob/v2.6.1/LICENSE) | [Kotlin compiler JAR](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.23/kotlin-compiler-embeddable-1.9.23.jar) | 否 | Kotlin 源码声明 `org.jetbrains.kotlin:protobuf-relocated:2.6.1-1`，公开 Central 无独立 JAR，实际代码位于 compiler JAR |
| Javaslang | 2.0.6 | Apache-2.0 | Copyright © 2014-2017 Javaslang contributors | [版本 POM](https://repo1.maven.org/maven2/io/javaslang/javaslang/2.0.6/javaslang-2.0.6.pom) | [Maven JAR](https://repo1.maven.org/maven2/io/javaslang/javaslang/2.0.6/javaslang-2.0.6.jar) | 否 | Kotlin compiler 内嵌，class 保留 `javaslang.*` 包名；source JAR 已核对 |
| kotlinx.collections.immutable JVM | 0.3.1 | Apache-2.0 | Copyright © 2016-2019 JetBrains s.r.o. | [LICENSE](https://github.com/Kotlin/kotlinx.collections.immutable/blob/v0.3.1/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-collections-immutable-jvm/0.3.1/kotlinx-collections-immutable-jvm-0.3.1.jar) | 否 | Kotlin compiler 内嵌并重定位；source JAR 文件头已核对 |
| IntelliJ Platform Core / JPS Model | 213.7172.53 | Apache-2.0 | Copyright © 2000-2021 JetBrains s.r.o. and contributors | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [JPS Model JAR](https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/platform/jps-model/213.7172.53/jps-model-213.7172.53.jar) | 否 | Kotlin compiler 合并 IntelliJ Core 和 JPS Model；Core 无稳定独立下载 URL，最终可使用 compiler JAR 地址并在备注说明 |
| PicoContainer，IntelliJ 精简 fork | 无 | BSD-3-Clause | Copyright © PicoContainer Organization; Copyright © 2003 NanoContainer Organization | [IntelliJ 固定版本 LICENSE](https://github.com/JetBrains/intellij-community/blob/idea/213.7172.25/license/picoContainer_license.txt) | [Kotlin compiler JAR](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.23/kotlin-compiler-embeddable-1.9.23.jar) | 否 | compiler JAR 实际包含重定位后的 `org.jetbrains.kotlin.org.picocontainer` API；其协议和 Copyright 与 IntelliJ Platform 不同，按已确认规则单列；未保留可可靠映射的独立版本，填“无” |
| IntelliJ fastutil fork | 8.5.4-9 | Apache-2.0 | Copyright © 2002-2021 Sebastiano Vigna, Paolo Boldi and contributors | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [JetBrains JAR](https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/intellij/deps/fastutil/intellij-deps-fastutil/8.5.4-9/intellij-deps-fastutil-8.5.4-9.jar) | 否 | Kotlin compiler 内嵌在 relocated IntelliJ 包下；compiler JAR 保留 fastutil LICENSE |
| Java Native Access，JetBrains dependency build | 5.9.0.26 | Apache-2.0 | Copyright © 2007-2021 Timothy Wall, Olivier Chafik and contributors | [upstream LICENSE](https://github.com/java-native-access/jna/blob/5.9.0/LICENSE) | [JetBrains JAR](https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/intellij/deps/jna/jna/5.9.0.26/jna-5.9.0.26.jar) | 否 | 本发行选择 Apache-2.0；合并记录 jna、jna-platform，Kotlin compiler 内嵌并重定位 |
| LZ4 Java | 1.7.1 | Apache-2.0 | Copyright © 2011-2019 Adrien Grand, Yann Collet and contributors | [LICENSE](https://github.com/lz4/lz4-java/blob/1.7.1/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/lz4/lz4-java/1.7.1/lz4-java-1.7.1.jar) | 否 | Kotlin compiler 内嵌并重定位至 `org.jetbrains.kotlin.net.jpountz` |
| ASM all，JetBrains dependency build | 9.0 | BSD-3-Clause | Copyright © 2000-2011 INRIA, France Telecom | [ASM LICENSE](https://gitlab.ow2.org/asm/asm/-/blob/ASM_9_0/LICENSE.txt) | [JetBrains JAR](https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/intellij/deps/asm-all/9.0/asm-all-9.0.jar) | 否 | Kotlin compiler 内嵌并重定位至 `org.jetbrains.org.objectweb.asm`；与 R8 9.6、Jugg 重打包 9.8 并存 |
| Guava | 29.0-jre | Apache-2.0 | Copyright © 2005-2020 The Guava Authors | [版本 POM](https://repo1.maven.org/maven2/com/google/guava/guava/29.0-jre/guava-29.0-jre.pom) | [Maven JAR](https://repo1.maven.org/maven2/com/google/guava/guava/29.0-jre/guava-29.0-jre.jar) | 否 | Kotlin compiler 内嵌并重定位；与 R8 内嵌 32.1.2-jre 并存 |
| Aalto XML | 1.3.0 | Apache-2.0 | Copyright © 2006-present Tatu Saloranta and contributors | [版本 POM](https://repo1.maven.org/maven2/com/fasterxml/aalto-xml/1.3.0/aalto-xml-1.3.0.pom) | [Maven JAR](https://repo1.maven.org/maven2/com/fasterxml/aalto-xml/1.3.0/aalto-xml-1.3.0.jar) | 否 | Kotlin compiler 内嵌并重定位至 `org.jetbrains.kotlin.com.fasterxml.aalto` |
| StAX2 API | 4.2.1 | BSD License | Copyright © 2005-present Tatu Saloranta and contributors | [版本 POM](https://repo1.maven.org/maven2/org/codehaus/woodstox/stax2-api/4.2.1/stax2-api-4.2.1.pom) | [Maven JAR](https://repo1.maven.org/maven2/org/codehaus/woodstox/stax2-api/4.2.1/stax2-api-4.2.1.jar) | 否 | Kotlin compiler 内嵌并重定位至 `org.jetbrains.kotlin.org.codehaus.stax2` |
| JDOM | 2.0.6 | JDOM License（BSD-style） | Copyright © 2000-2012 Jason Hunter, Brett McLaughlin and JDOM contributors | [LICENSE](https://github.com/hunterhacker/jdom/blob/JDOM-2.0.6/LICENSE.txt) | [JetBrains JAR](https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/intellij/deps/jdom/2.0.6/jdom-2.0.6.jar) | 否 | Kotlin compiler 内嵌并重定位 |
| Apache Log4j，JetBrains dependency build | 1.2.17.2 | Apache-2.0 | Copyright © 2007 The Apache Software Foundation | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [JetBrains JAR](https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/intellij/deps/log4j/1.2.17.2/log4j-1.2.17.2.jar) | 否 | Kotlin compiler 内嵌并重定位；JetBrains JAR NOTICE 已核对 |
| StreamEx | 0.7.2 | Apache-2.0 | Copyright © 2015, 2019 StreamEx contributors | [版本 POM](https://repo1.maven.org/maven2/one/util/streamex/0.7.2/streamex-0.7.2.pom) | [Maven JAR](https://repo1.maven.org/maven2/one/util/streamex/0.7.2/streamex-0.7.2.jar) | 否 | Kotlin compiler 内嵌并重定位至 `org.jetbrains.kotlin.one.util.streamex` |

`javax.inject:1` 已在 R8 内嵌表中登记，同一版本也被 Kotlin compiler 内嵌，不重复新增行。PicoContainer 因协议和 Copyright 不同已单列；其余 IntelliJ Core 代码仍按相同版本、协议、Copyright 和修改状态合并登记。

### 3.3 cmd_line Data Binding 7.4.2 差异依赖

`cmd_line:runtimeClasspath` 已通过 Gradle 解析。与插件发行包的 Data Binding `8.7.3` 依赖树相比，以下组件以不同版本或新增 artifact 进入独立命令行发行物；相同版本的 Gson、Jetifier、JAXB、ANTLR、JavaPoet、Guava 的 failureaccess/JSR-305 等不重复登记。

| 开源软件名称 | 版本 | 开源协议 | Copyright 初稿 | 协议链接候选 | 下载链接候选 | 修改 | 备注 |
|---|---|---|---|---|---|---|---|
| Android Tools Annotations | 30.4.2 | Apache-2.0 | Copyright © 2005-2013 The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Google Maven JAR](https://dl.google.com/dl/android/maven2/com/android/tools/annotations/30.4.2/annotations-30.4.2.jar) | 否 | Data Binding 7.4.2 传递依赖；与插件包中的 31.7.3 不同版本 |
| Guava | 30.1-jre | Apache-2.0 | Copyright © 2005-2021 The Guava Authors | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/com/google/guava/guava/30.1-jre/guava-30.1-jre.jar) | 否 | Data Binding 7.4.2 传递依赖；与 R8 32.1.2-jre、Kotlin compiler 29.0-jre 并存 |
| Checker Qual | 3.5.0 | MIT | Copyright © 2004-present by the Checker Framework developers | [LICENSE](https://github.com/typetools/checker-framework/blob/checker-framework-3.5.0/checker-qual/LICENSE.txt) | [Maven JAR](https://repo1.maven.org/maven2/org/checkerframework/checker-qual/3.5.0/checker-qual-3.5.0.jar) | 否 | Guava 30.1-jre 传递依赖；与 R8 的 3.33.0 不同版本 |
| Error Prone Annotations | 2.3.4 | Apache-2.0 | Copyright © 2014-2019 The Error Prone Authors | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/com/google/errorprone/error_prone_annotations/2.3.4/error_prone_annotations-2.3.4.jar) | 否 | Guava 30.1-jre 传递依赖；与 R8 的 2.18.0 不同版本 |
| J2ObjC Annotations | 1.3 | Apache-2.0 | Copyright © 2012 Google Inc. | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/com/google/j2objc/j2objc-annotations/1.3/j2objc-annotations-1.3.jar) | 否 | Guava 30.1-jre 传递依赖；与 R8 的 2.8 不同版本 |
| Apache Commons IO | 2.4 | Apache-2.0 | Copyright © 2002-2012 The Apache Software Foundation | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Maven JAR](https://repo1.maven.org/maven2/commons-io/commons-io/2.4/commons-io-2.4.jar) | 否 | Data Binding 7.4.2 传递依赖；与插件包中的 2.13.0 不同版本；原 GitHub LICENSE URL 返回 404，已改用固定协议文本 |
| SLF4J API / NOP binding | 1.7.36 | MIT | Copyright © 2004-2017 QOS.ch | [LICENSE](https://www.slf4j.org/license.html) | [SLF4J NOP JAR](https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/1.7.36/slf4j-nop-1.7.36.jar) | 否 | `cmd_line` 为避免无实现告警直接加入 `slf4j-nop`；API 与 binding 同版本，按同一上游项目合并登记 |

## 4. Native、内嵌源码与仓库再分发组件

| 开源软件名称 | 版本 | 开源协议 | Copyright 初稿 | 协议链接候选 | 下载/源码链接候选 | 修改 | 备注 |
|---|---|---|---|---|---|---|---|
| AAPT2 inclink，Jugg 定制版 | 无 | Apache-2.0，另含静态链接第三方组件 | Copyright © The Android Open Source Project; Jugg modifications | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [AOSP 基线源码](https://android.googlesource.com/platform/frameworks/base/+/a707013b78cea3586fdadf9a2f04932e823d7504/tools/aapt2/) | 是 | `aapt2_jugg` 根提交 `6361ca8` 记录从 AOSP `a707013b78cea3586fdadf9a2f04932e823d7504` 导入并定制；项目方说明后续迁移至 Android 14 AOSP 适配，当前仓库 HEAD `b5b59b2` 也记录 Android 14 Linux 兼容；三平台二进制自报/文件名不一致，按附件规则版本填“无” |
| AOSP native support libraries suite | 无 | Apache-2.0 | Copyright © The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [AOSP source](https://android.googlesource.com/platform/) | 否 | AAPT2 `Android.bp` 静态链接 `libandroidfw`、`libutils`、`liblog`、`libcutils`、`libziparchive`、`libbase`、`libbuildversion`、`libidmap2_policies`；JVMTI agent 另动态链接设备提供的 `liblog`。这些源码不在 `aapt2_jugg` 仓库中，Jugg 修改历史没有改动它们，按相同协议、Copyright 和修改状态合并，版本填“无” |
| Expat | 2.6.0 | MIT | Copyright © 1998-2000 Thai Open Source Software Center Ltd.; Expat contributors | [LICENSE](https://github.com/libexpat/libexpat/blob/R_2_6_0/expat/COPYING) | [source tarball](https://github.com/libexpat/libexpat/archive/refs/tags/R_2_6_0.tar.gz) | 否 | AAPT2 `Android.bp` 静态链接 `libexpat`，三平台二进制均含 `expat_2.6.0`；源码不在 `aapt2_jugg` 仓库中，未发现 Jugg 修改 |
| LLVM libc++，Android toolchain build | 无 | Apache-2.0 WITH LLVM-exception | Copyright © LLVM Project contributors | [固定版本 LICENSE](https://android.googlesource.com/toolchain/llvm-project/+/477610d4d0d988e69dbc3fae4fe86bff3f07f2b5/LICENSE.TXT) | [Android LLVM source](https://android.googlesource.com/toolchain/llvm-project/+/477610d4d0d988e69dbc3fae4fe86bff3f07f2b5/libcxx/) | 否 | AAPT2 `Android.bp` 指定 `libc++_static`；Linux 二进制记录 Android clang `r510928` 和 LLVM commit `477610d4d0d988e69dbc3fae4fe86bff3f07f2b5`，但不能单独还原 libc++ release 版本，按附件规则填“无”；Jugg 未修改 toolchain 源码 |
| Android JVMTI Apply Changes / Slicer source | 无 | Apache-2.0 | Copyright © 2017-2018 The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [AOSP tools/base](https://android.googlesource.com/platform/tools/base/) | 是 | 仓库文件头已核对；现有历史只能确认 2024-07-28 引入，无法定位精确 AOSP commit，按附件规则版本暂填“无” |
| OpenJDK JVMTI header | 无 | GPL-2.0-only WITH Classpath-exception-2.0 | Copyright © 2003, 2011 Oracle and/or its affiliates | [GPLv2 + Classpath Exception](https://openjdk.org/legal/gplv2+ce.html) | [OpenJDK 8u202 jvmti.h](https://github.com/openjdk/jdk8u/blob/jdk8u202-b08/jdk/src/share/javavm/export/jvmti.h) | 是 | 与 `jdk8u202-b08`、`jdk8u302-b08`、`jdk8u402-b06`、`jdk8u442-b06` 的 header 比较结果相同，均只有 `JNINativeInterface_` 改为 `JNINativeInterface` 一处差异；无法唯一反推原始 tag，按附件规则版本填“无”，下载链接使用已验证内容一致的固定 tag |
| Gradle Wrapper launch files | 7.0.2 | Apache-2.0 | Copyright © 2015 the original author or authors | [LICENSE](https://github.com/gradle/gradle/blob/v7.0.2/LICENSE) | [source zip](https://github.com/gradle/gradle/archive/refs/tags/v7.0.2.zip) | 是 | Jugg 随插件携带 `gradlew`、`gradlew.bat` 和 `gradle-wrapper.jar`，并在目标项目已有 `gradle-wrapper.properties` 但启动文件缺失时复制；wrapper JAR 与 Gradle v7.0.2 官方文件 SHA-256 一致，两个启动脚本相对 v7.0.2 仅移除默认 JVM 参数 `-Dfile.encoding=UTF-8`；不携带 `gradle-wrapper.properties` 或 Gradle Distribution |
| rsync | 3.4.1 | GPL-3.0-or-later | Copyright © 1996-2025 Andrew Tridgell, Wayne Davison, and others | [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html) | [source tarball](https://download.samba.org/pub/rsync/src/rsync-3.4.1.tar.gz) | 否 | 仓库二进制执行 `--version` 明确自报 3.4.1；该文件为 macOS x86_64/arm64 通用二进制，项目方确认对应源码未修改 |
| sshpass | 1.10 | GPL-2.0-or-later | Copyright © 2006-2011 Lingnu Open Source Consulting Ltd.; 2015-2016, 2021-2022 Shachar Shemesh | [GPL-2.0](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html) | [source tarball](https://sourceforge.net/projects/sshpass/files/sshpass/1.10/sshpass-1.10.tar.gz/download) | 否 | 仓库二进制执行 `-V` 明确自报 1.10；该文件为 macOS arm64 二进制，项目方确认对应源码未修改 |
| libpng | 1.6.40 | Libpng-2.0 | Copyright © 1995-2023 libpng authors | [LICENSE](https://github.com/pnggroup/libpng/blob/v1.6.40/LICENSE) | [source tarball](https://github.com/pnggroup/libpng/archive/refs/tags/v1.6.40.tar.gz) | 否 | AAPT2 `Android.bp` 静态链接 `libpng`，三平台二进制均明确包含 `libpng version 1.6.40`；`aapt2_jugg` 不包含 libpng 源码，根提交至当前 HEAD 的历史也没有相关文件，未发现 Jugg 修改 |
| zlib，AOSP motley 变体 | `1.3.0.1-motley` | zlib License | Copyright © 1995-2023 Jean-loup Gailly and Mark Adler | [AOSP 固定版本 zlib.h](https://android.googlesource.com/platform/external/zlib/+/932a58803c3d13295215446fa2bbf1aba5d327ef/zlib.h) | [AOSP source archive](https://android.googlesource.com/platform/external/zlib/+archive/932a58803c3d13295215446fa2bbf1aba5d327ef.tar.gz) | 否 | AAPT2 `Android.bp` 静态链接 `libz`，三平台二进制均含 `1.3.0.1-motley`；固定 AOSP commit 的 `zlib.h` 版本字符串一致；`aapt2_jugg` 不包含 zlib 源码，未发现 Jugg 修改 |
| zlib，Android platform API | 无 | zlib License | Copyright © Jean-loup Gailly and Mark Adler | [zlib License](https://zlib.net/zlib_license.html) | [AOSP external/zlib](https://android.googlesource.com/platform/external/zlib/) | 否 | JVMTI agent 的 CMake 显式链接 `z`，两份 SO 的 ELF `NEEDED` 均包含设备侧 `libz.so`；运行时版本由 Android 设备决定且不随 Jugg 分发，按附件规则版本填“无” |
| Protocol Buffers，AOSP native runtime | 无 | BSD-3-Clause | Copyright © Google Inc. and protobuf contributors | [BSD-3-Clause](https://spdx.org/licenses/BSD-3-Clause.html) | [AOSP external/protobuf](https://android.googlesource.com/platform/external/protobuf/) | 否 | AAPT2 `Android.bp` 静态链接 `libprotobuf-cpp-full`，三平台二进制均包含 protobuf runtime 和生成代码；二进制未暴露可可靠映射的版本，按附件规则填“无”；`aapt2_jugg` 不包含 protobuf runtime 源码，未发现 Jugg 修改 |
| Android Studio / Android Plugin APIs | 无 | Apache-2.0 | Copyright © Google LLC、JetBrains s.r.o. 及各组件贡献者 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [Android Studio archive](https://developer.android.com/studio/archive) | 是 | 附件明确把开源 API 纳入“使用”，因此改成源码 Stub 后仍需登记；精确 build 是 `223.7571.182` 这类内部构建号，不是 Giraffe 等代号，现有 42 个 JAR 无法完整还原，按附件规则版本填“无”；生成 Stub 实际仅保留 `com.android`、`com.google.devrel`、`com.intellij` 和 `org.jetbrains` 下的编译所需 API 声明，按相同协议、版本和修改状态合并填一行 |
| AOSP framework class stubs | 无 | Apache-2.0 | Copyright © The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | [frameworks/base](https://android.googlesource.com/platform/frameworks/base/) | 是 | 7 个手写最小声明没有上游版本和许可证头；按附件包含 API 的规则保守登记 AOSP，版本暂填“无” |

## 5. 附件 1 填表 P0 完成状态

| 状态 | 事项 | 完成标准 |
|---|---|---|
| 已确认 | 使用范围完整性 | 本次覆盖插件发行包、`cmd_line` 独立发行物、仓库直接再分发的第三方代码/文件、Gradle Wrapper 和 Stub API；明确不纳入测试/demo/fixture，以及不随产品分发、嵌入、复制或提供下载的纯构建工具、Wiki/npm、CI action、脚本运行时及服务端/云服务 |
| 已确认 | 软件名称和拆分粒度 | 按上游项目合并：版本、协议、Copyright 和修改状态全部相同时合并为一行；任一字段不同时拆行，不按 Maven artifact 机械拆分 |
| 已确认 | 精确版本 | AAPT2 inclink 和无法还原的 Android Studio API build 按附件规则填“无”；AOSP JVMTI/Slicer、OpenJDK header、framework stub 及 AAPT2 内嵌 protobuf 无法确认时同样填“无”；rsync 3.4.1、sshpass 1.10 已由程序自身输出确认 |
| 已完成 | 开源协议和 Copyright | 已按对应版本的 LICENSE、NOTICE、POM 和源码文件头形成候选值；多权利人保留在同一单元格，`present` 仅在上游自身采用该表达时保留 |
| 已完成 | 双许可证选择 | JavaParser、JNA 和 Fast Infoset 固定选择 Apache-2.0；juniversalchardet 固定选择 MPL-1.1；JavaBeans Activation Framework 固定选择 CDDL-1.1，发行清单、NOTICE 和 SBOM 使用同一选择 |
| 已完成 | 协议链接和直接下载链接 | 已完成 URL 可达性扫描；已知版本使用固定 tag、固定 Maven/Google Maven 坐标或 source archive，版本填“无”的组件使用标准协议文本和可追溯上游源码入口；已修复 Commons IO 2.4 的 404 链接并移除 Trove4J 的 `master` 协议链接 |
| 已确认 | AAPT2 外部构建树修改状态 | 项目方确认完整 AOSP build tree 不存在针对 libpng、zlib、protobuf、Expat、LLVM libc++ 与 AOSP support libraries 的未入库 patch；本次直接按“未修改”填写，不再深入追溯 |
| 已完成 | 内嵌组件展开 | R8 `LICENSE` 的 53 个条目已逐项归并；无 class 的 ListenableFuture 冲突占位不单列；Kotlin compiler 中协议/Copyright 不同的 PicoContainer 已拆行；AAPT2 的显式静态依赖已按协议和修改状态拆分 |
| 已确认 | 平台标准运行库 | 本次附件不单列编译器自动注入或操作系统提供的 glibc、Bionic libc/libm/libdl、macOS libSystem 等标准运行库；仍保留源码或构建配置显式使用的 Android `liblog`、`libz` 候选项 |
| 已确认 | Stub API 的填报归属 | 按 Android Studio / Android Plugin 上游项目合并登记一行，版本填“无”，生成/裁剪后的 Stub 填修改“是”，备注说明仅保留编译所需 API 声明 |

附件允许无法确认版本时填“无”，因此精确 commit/build 不是所有行的硬阻断。内部来源材料只在无法据此判断“是否修改”或无法确定上游软件归属时成为必需证据。清单以最终拟开源产物为填报基线：来源不明的旧二进制可以在开源前替换为从明确上游版本重新构建的产物，随后按新产物填写，不要求为了附件追溯已经被替换的旧产物历史。

其中 `deploy_compat/*/libs` 已确认采用版本化源码 Stub API，但这只是工程替换方案。附件仍要求判断实际使用的上游 API，并填写对应的 8 个字段。详细工程方案见：[open_source_stub_api_and_clean_public_repo_plan.md](open_source_stub_api_and_clean_public_repo_plan.md)。

以下事项不属于附件 1 的字段范围，应在开源发布工作流中另行推进：公司开源审批、Git 历史清理、MCP 鉴权、SSH 凭据、诊断包、远程代码供应链、CI 和发布门禁。

### 5.1 推进责任

| 工作 | Codex 可自行完成 | 需要项目方补充 |
|---|---|---|
| 使用范围扫描 | 扫描源码、Gradle、发布包、仓库二进制、脚本及网络调用，形成候选全集 | 代码和配置中不可见的人工下载、外部服务或未入库发布步骤 |
| 名称与拆分粒度 | 已按“字段相同则按上游项目合并，字段不同则拆行”执行 | N/A；后续只有审批方明确要求按 artifact 拆分时才调整 |
| 精确版本 | 读取依赖元数据、JAR/native 字符串、Git 历史、哈希并与公开上游匹配 | 无公开来源且仓库中没有构建记录的内部定制版本 |
| 协议、Copyright 与链接 | 从对应版本的官方仓库、发布页、LICENSE/NOTICE 中核对并填写 | 上游材料自身不完整时的权利人确认 |
| 是否修改 | 对比上游 artifact/source、分析重定位和裁剪内容，给出修改摘要 | 找不到原始输入、patch 或构建脚本时确认修改意图和内部来源 |
| Stub API 填报 | 根据真实 IDE build、生成来源和差异判断候选填法 | 审批方对“API/生成声明”的最终法律口径 |

因此，附件 1 的事实梳理和内部口径已经收口，后续无需继续补充内部事实即可回填。

### 5.2 本轮逐项核对结果

- 已对插件包中的 48 个第三方 JAR 建立依赖树，并结合 `cmd_line` 的 Data Binding `7.4.2` suite 形成 39 个顶层 JVM 候选行。
- R8 内嵌依赖新增 26 个候选行，Kotlin compiler 内嵌依赖新增 17 个候选行，`cmd_line` Data Binding 差异依赖新增 7 个候选行；当前 JVM 候选共 89 行。
- 已核对 15 个 native、内嵌源码、Gradle Wrapper 和 API/Stub 候选行，当前总候选为 104 行。
- 已修正 JGit 许可证、Trove4J 许可证范围、ASM Copyright、双许可证选择、AAPT2/zlib 版本等错误。
- 已拆出 Android Tools Annotations、Kotlin Android Extensions、ANTLR Runtime、JZlib、jBCrypt 和 OpenJDK JVMTI header 等原清单遗漏或错误合并项。
- R8 与 Kotlin compiler embeddable 的插件内 JAR 均与对应官方 JAR SHA-256 相同；SQLite lite 的差异已确认仅为删除部分平台 native library；ASM 的包重定位范围和 Kotlin Android Extensions 的 4 个差异 class 已定位。
- 项目方已确认 AAPT2 完整外部 build tree 没有未入库 patch，并决定不单列编译器自动注入或操作系统提供的标准运行库；附件范围内已无待确认项。

### 5.3 内嵌依赖反查进度

R8 `8.4.21` 源码 tag 已能确认以下构建版本：

| 内嵌组件 | 版本证据 |
|---|---|
| Guava | `32.1.2-jre` |
| fastutil | `7.2.1` |
| ASM | `9.6` |
| Gson | `2.10.1` |
| kotlinx-metadata-jvm | `0.9.0` |
| AAPT2 Proto | 源码直接声明 `8.2.0-alpha10-10154469`，完整依赖解析后实际选中 `8.2.0-rc01-10154469` |
| Protocol Buffers Java | `3.19.3` |
| Android Tools layoutlib-api/common/sdk-common | `31.2.0-rc01` |

R8 的 `LICENSE` 实际列出 53 个 distributed library 条目。基于上述根版本完成依赖解析后，已确认 failureaccess `1.0.1`、ListenableFuture empty artifact `9999.0-empty-to-avoid-conflict-with-guava`、JSR-305 `3.0.2`、Checker Qual `3.33.0`、Error Prone Annotations `2.18.0`、J2ObjC Annotations `2.8`、Kotlin stdlib `1.9.21`、JNA/JNA Platform `5.6.0`、kXML2 `2.3.0`、Jimfs `1.1`、Commons Compress `1.21`、Apache HttpCore `4.4.16`、HttpClient/HttpMime `4.5.6`、Commons Logging `1.2`、Commons Codec `1.10`、javax.inject `1`、Bouncy Castle `1.67`、Xerces2 Java `2.12.0` 和 XML APIs `1.4.01`。去重已有 Gson、Kotlin、JAXB 等行后，新增组件已转换为 §3.1 的 26 个附件候选行；53 个条目已完成一对一归并核对，无 class 的 ListenableFuture 冲突占位已明确不单列。

Kotlin `1.9.23` 源码 tag 已确认 compiler embeddable 使用的主要内嵌版本包括：IntelliJ Platform Core `213.7172.53`、IntelliJ fastutil fork `8.5.4-9`、Guava `29.0-jre`、ASM `9.0`、JNA `5.9.0.26`、relocated protobuf `2.6.1-1`、Javaslang `2.0.6`、Aalto XML `1.3.0`、StAX2 API `4.2.1`、JDOM `2.0.6` 和 StreamEx `0.7.2`。以实际 `kotlin-compiler-embeddable-1.9.23.jar` 的 class 为准，排除了源码构建脚本中声明但最终未进入 JAR 的 JSR-305 `1.3.9` 和 Apache ORO `2.0.8`；去重 `javax.inject:1` 后，已转换为 §3.2 的 17 个附件候选行，其中 PicoContainer 因独立 BSD-style 许可证单列。

## 6. 后续步骤

1. 以 `third_party/components.csv` 的 104 个组件为附件和发行合规基线；法务送审排序版无表头 CSV 已生成，可直接粘贴到 Excel，行顺序和备注与发行合规资产使用同一事实口径。
2. 将 `third_party/INTEGRATION.md` 的地址空间、双许可证选择和 Classpath Exception 技术事实回传法务确认，不把共享 JVM 的组件误报为进程隔离。
3. 每次依赖或第三方二进制变化后更新 `components.csv`，运行 `ruby tools/generate_third_party_compliance.rb`，再执行 `./gradlew :idea:buildPlugin`。
4. 公司审批、历史清理和安全治理继续留在独立开源发布清单，不再混入附件填表 P0。

## 7. 当前结论

- Jugg `3.2.2-release` 插件包包含数量较多的 JVM 第三方组件，且存在传递依赖和 JAR 内嵌许可证，不能只登记 `build.gradle` 中的直接依赖。
- 就附件 1 而言，104 个组件、版本填法、协议/Copyright/链接、修改状态和范围口径均已收口，法务送审版 CSV 已生成；备注不再包含“已核对”“原清单”等开发过程语言。
- 发行包构建已要求包含与仓库一致的法务 LICENSE、NOTICE、许可证、对应源码定位、第三方修改 changelog、集成边界说明、机器清单和 SPDX SBOM，并通过构建门禁验证。
- 再分发义务、公司审批、仓库历史和安全问题仍然重要，但不应被称为“附件 1 填表 P0”。
- 当前 Markdown 与 `third_party/components.csv` 是 Excel 回填基线；最终提交件仍以回填并复核后的附件为准。
