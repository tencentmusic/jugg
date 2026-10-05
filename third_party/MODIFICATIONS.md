# Third-Party Modification Changelog

This changelog lists every redistributed third-party component marked as modified in `components.csv`. Each entry records the applicable license, the known Jugg changes, and the corresponding upstream reference.

## OpenJDK JVMTI header N/A

- License: GPL-2.0-only WITH Classpath-exception-2.0
- Change summary: Used as a build header for the JVMTI agent; only JNINativeInterface_ was changed to JNINativeInterface; subject to GPL-2.0-only WITH Classpath-exception-2.0; the distributed SOURCE.md identifies a public commit containing the upstream baseline, modified source, and patch.
- Upstream reference: https://github.com/openjdk/jdk8u/blob/jdk8u202-b08/jdk/src/share/javavm/export/jvmti.h

## Xerial SQLite JDBC 3.42.0.0

- License: Apache-2.0 and BSD-2-Clause; SQLite core is public domain
- Change summary: Distributed with the plugin as a trimmed JAR; only native libraries for unsupported platforms were removed, while those needed for macOS, Linux, and Windows were retained.
- Upstream reference: https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.42.0.0/sqlite-jdbc-3.42.0.0.jar

## AAPT2 inclink, Jugg custom build N/A

- License: Apache-2.0; includes statically linked third-party components
- Change summary: Distributed with the plugin as executables for three platforms and invoked as separate processes; based on AOSP AAPT2 with customized incremental linking and Android 14/Linux compatibility; statically linked components are listed separately; no unique version can be identified.
- Upstream reference: https://android.googlesource.com/platform/frameworks/base/+/a707013b78cea3586fdadf9a2f04932e823d7504/tools/aapt2/

## Android JVMTI Apply Changes / Slicer source N/A

- License: Apache-2.0
- Change summary: The project includes and modifies AOSP JVMTI Apply Changes/Slicer source, compiled into the distributed JVMTI agent; Jugg adds Android 8-15 Application, ResourcesManager, ActivityThread, and ClassLoader hooks, plus best-effort fallback for optional transforms and diagnostic logging; the exact upstream commit cannot be identified, so no version is specified; modified source remains in the repository.
- Upstream reference: https://android.googlesource.com/platform/tools/base/+archive/refs/heads/studio-main/deploy/agent/native.tar.gz

## Android Studio / Android Plugin APIs N/A

- License: Apache-2.0
- Change summary: Generated and trimmed compile stubs from Android Studio/Android Plugin APIs, used only to build compatibility modules; upstream implementation code is not bundled in the plugin distribution; only required API declarations remain, and the version cannot be fully reconstructed.
- Upstream reference: https://android.googlesource.com/platform/tools/base/+archive/refs/heads/studio-main.tar.gz / https://github.com/JetBrains/intellij-community/archive/refs/tags/idea/223.7571.182.zip

## AOSP framework class stubs N/A

- License: Apache-2.0
- Change summary: Seven minimal compile declarations written from AOSP framework APIs, used only to build the JVMTI agent; AOSP implementation code is not bundled in the plugin distribution; API declarations were selected and simplified, and no version is specified.
- Upstream reference: https://android.googlesource.com/platform/frameworks/base/+archive/refs/heads/master/core/java/android.tar.gz

## Gradle Wrapper launch files 7.0.2

- License: Apache-2.0
- Change summary: Distributed with the plugin and copied when a target project has gradle-wrapper.properties but lacks the launch files; gradlew and gradlew.bat only remove the default -Dfile.encoding=UTF-8 JVM argument; Gradle Distribution is not included.
- Upstream reference: https://github.com/gradle/gradle/archive/refs/tags/v7.0.2.zip

## Kotlin Android Extensions 1.9.23

- License: Apache-2.0
- Change summary: Distributed with the plugin as a modified JAR; compared with the official version, the reportRemovedError call in AndroidComponentRegistrar was removed, and three other classes only gained an empty parameter-annotation attribute.
- Upstream reference: https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-android-extensions/1.9.23/kotlin-android-extensions-1.9.23.jar

## ASM 9.8

- License: BSD-3-Clause
- Change summary: Distributed with the plugin as three repackaged JARs; org.objectweb.asm was relocated into a Jugg-private package, and module-info.class was additionally removed from asm-commons.
- Upstream reference: https://repo1.maven.org/maven2/org/ow2/asm/asm/9.8/asm-9.8.jar
