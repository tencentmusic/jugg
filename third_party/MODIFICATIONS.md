# Third-Party Modification Changelog

This changelog lists every redistributed third-party component marked as modified in `components.csv`. Each entry records the applicable license, the known Jugg changes, and the corresponding upstream reference.

## OpenJDK JVMTI header 无

- License: GPL-2.0-only WITH Classpath-exception-2.0
- Change summary: 作为 JVMTI agent 构建头文件使用；仅将 JNINativeInterface_ 改为 JNINativeInterface；适用 GPL-2.0-only WITH Classpath-exception-2.0；上游基线、修改后源码和 patch 由插件内 SOURCE.md 定位到公开提交提供。
- Upstream reference: https://github.com/openjdk/jdk8u/blob/jdk8u202-b08/jdk/src/share/javavm/export/jvmti.h

## Xerial SQLite JDBC 3.42.0.0

- License: Apache-2.0、BSD-2-Clause；SQLite 核心 Public Domain
- Change summary: 以裁剪版 JAR 随插件发行；仅删除不支持平台的 native library，保留 macOS、Linux 和 Windows 所需版本。
- Upstream reference: https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.42.0.0/sqlite-jdbc-3.42.0.0.jar

## AAPT2 inclink，Jugg 定制版 无

- License: Apache-2.0，另含静态链接第三方组件
- Change summary: 以三平台可执行文件随插件发行，并作为独立进程调用；基于 AOSP AAPT2 定制增量链接及 Android 14/Linux 兼容能力；静态链接组件另行列示；无法唯一确定版本，因此填“无”。
- Upstream reference: https://android.googlesource.com/platform/frameworks/base/+/a707013b78cea3586fdadf9a2f04932e823d7504/tools/aapt2/

## Android JVMTI Apply Changes / Slicer source 无

- License: Apache-2.0
- Change summary: 项目包含并修改 AOSP JVMTI Apply Changes/Slicer 源码，编译进入随插件发行的 JVMTI agent；Jugg 增加 Android 8-15 Application、ResourcesManager、ActivityThread 和 ClassLoader hook，增加可选 transform 的 Best-effort 降级与诊断日志；无法定位精确上游 commit，因此版本填“无”；修改后源码保留在仓库中。
- Upstream reference: https://android.googlesource.com/platform/tools/base/+archive/refs/heads/studio-main/deploy/agent/native.tar.gz

## Android Studio / Android Plugin APIs 无

- License: Apache-2.0
- Change summary: 根据 Android Studio/Android Plugin API 生成并裁剪为编译 Stub，仅用于构建兼容模块，不将上游实现代码打入插件发行包；仅保留所需 API 声明，版本无法完整还原，因此填“无”。
- Upstream reference: https://android.googlesource.com/platform/tools/base/+archive/refs/heads/studio-main.tar.gz / https://github.com/JetBrains/intellij-community/archive/refs/tags/idea/223.7571.182.zip

## AOSP framework class stubs 无

- License: Apache-2.0
- Change summary: 根据 AOSP framework API 编写 7 个最小编译声明，仅用于构建 JVMTI agent，不将 AOSP 实现代码打入插件发行包；API 声明经过挑选和简化，版本填“无”。
- Upstream reference: https://android.googlesource.com/platform/frameworks/base/+archive/refs/heads/master/core/java/android.tar.gz

## Gradle Wrapper launch files 7.0.2

- License: Apache-2.0
- Change summary: 随插件发行，并在目标项目已有 gradle-wrapper.properties 但缺少启动文件时复制；gradlew 和 gradlew.bat 仅移除默认 JVM 参数 -Dfile.encoding=UTF-8；不包含 Gradle Distribution。
- Upstream reference: https://github.com/gradle/gradle/archive/refs/tags/v7.0.2.zip

## Kotlin Android Extensions 1.9.23

- License: Apache-2.0
- Change summary: 以修改版 JAR 随插件发行；相对官方版本移除 AndroidComponentRegistrar 的 reportRemovedError 调用，另有 3 个 class 仅增加空 parameter-annotation attribute。
- Upstream reference: https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-android-extensions/1.9.23/kotlin-android-extensions-1.9.23.jar

## ASM 9.8

- License: BSD-3-Clause
- Change summary: 以 3 个重打包 JAR 随插件发行；将 org.objectweb.asm 重定位到项目私有包名，asm-commons 另移除 module-info.class。
- Upstream reference: https://repo1.maven.org/maven2/org/ow2/asm/asm/9.8/asm-9.8.jar
