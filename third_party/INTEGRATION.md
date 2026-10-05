# Third-Party Integration Boundaries

This document records how Jugg integrates components of legal interest and where their process address-space boundaries lie. It provides technical facts for legal review; it does not replace license interpretation or a legal conclusion.

## 1. GPL, dual-license, and exception components

| Component | License selected by Jugg | Actual integration | Shares Jugg's address space? | Technical conclusion |
|---|---|---|---|---|
| Checker Qual 3.33.0 | MIT | Embedded in the official R8 8.4.21 JAR and loaded by the JVM | Yes | Does not rely on process isolation. The version's `checker-qual/LICENSE.txt` explicitly offers the MIT License; the distribution inventory and SBOM both select MIT. |
| Checker Qual 3.5.0 | MIT | A transitive dependency of Guava 30.1-jre in `cmd_line`, loaded by the JVM | Yes | Does not rely on process isolation. The version's `checker-qual/LICENSE.txt` explicitly offers the MIT License; the distribution inventory and SBOM both select MIT. |
| OpenJDK JVMTI header | GPL-2.0-only WITH Classpath-exception-2.0 | The modified `jvmti.h` is compiled into the native JVMTI agent | Yes | This is not process-isolated. Jugg uses the header under the Classpath Exception and supplies the upstream baseline, modified source, and patch. Legal review must decide whether this treatment of the exception is acceptable. |
| rsync 3.4.1 | GPL-3.0-or-later | The macOS executable is started by a shell command and interacts via arguments, stdin/stdout/stderr, and the file system | No | Runs as a separate OS process; it is neither statically nor dynamically linked with the IDE plugin JVM and does not share its address space. |
| sshpass 1.10 | GPL-2.0-or-later | The macOS arm64 executable is started by a shell command as an SSH command prefix and interacts via the command line and process I/O | No | Runs as a separate OS process; it is neither statically nor dynamically linked with the IDE plugin JVM and does not share its address space. |
| JavaBeans Activation Framework 1.2.0 | CDDL-1.1 | Embedded in the official R8 8.4.21 JAR and loaded by the JVM | Yes | Does not rely on process isolation. Upstream offers CDDL-1.1/GPL-2.0; the distribution inventory and SBOM select CDDL-1.1. |

These six component categories cannot all be described as isolated through IPC or the command line. The technical facts are that `rsync` and `sshpass` run in separate processes; Checker Qual and JavaBeans Activation Framework use non-GPL license alternatives; and the OpenJDK JVMTI header is used under GPL-2.0 with the Classpath Exception, with modified source and a patch provided in the distribution.

## 2. LGPL 2.1 and MPL 1.1 components

| Component | Actual integration | Isolation and modification status |
|---|---|---|
| Trove4J, JetBrains fork 1.0.20200330 | Distributed with the plugin as a separate `trove4j-1.0.20200330.jar`, dynamically loaded by the JVM classloader | Shares the plugin JVM address space but remains a separate JAR, not statically combined with the Jugg JAR. Jugg does not modify the JAR and provides the corresponding source JAR and LGPL-2.1-or-later text. |
| juniversalchardet 1.0.3 | Distributed with the plugin as a separate `juniversalchardet-1.0.3.jar`, dynamically loaded by the JVM classloader | Shares the plugin JVM address space but remains a separate JAR, not statically combined with the Jugg JAR. Jugg does not modify the JAR, selects MPL-1.1 according to the upstream POM, and provides the corresponding source JAR and license text. |

## 3. Repository and distribution evidence

- Process startup implementation: `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/CmdExecutor.kt`.
- rsync/sshpass command assembly and resource copying: `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/RsyncCommand.kt`.
- OpenJDK modified baseline and patch: `third_party/sources/openjdk-jvmti-header/`.
- License selections and usage relationships: `third_party/components.csv`, `THIRD_PARTY_NOTICES.md`, `third_party/sbom/jugg-third-party.spdx.json`.
- Corresponding source and checksums: `third_party/sources/README.md`, `third_party/sources/SHA256SUMS`.
- Plugin distribution boundary: `jugg/lib/` and `jugg/third_party/` in `idea/build/distributions/*.zip`.

## 4. Items for legal review

- Confirm whether the Classpath Exception covers Jugg's use and modification of the OpenJDK JVMTI header in the native agent.
- Confirm the selected alternatives for the upstream dual-license components: MIT for both Checker Qual versions, CDDL-1.1 for JavaBeans Activation Framework, MPL-1.1 for juniversalchardet, and Apache-2.0 for JavaParser Core, Fast Infoset, and both JNA entries.
- Confirm the combined-license descriptions and SPDX expressions for SQLite JDBC, Apache XML Commons XML APIs, Apache Commons Compress, kXML2, and JDOM, including the stated public-domain portions.
- Confirm that the separate-process description of rsync and sshpass and the shared-JVM description of the embedded JARs match the intended legal analysis.
