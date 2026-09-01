# Third-Party Integration Boundaries

本文记录 Jugg 对法务关注组件的实际集成方式和地址空间边界，仅作为技术事实供法务确认，不替代许可证解释或法律结论。

## 1. GPL、双许可证和例外条款组件

| 组件 | Jugg 采用的许可证口径 | 实际集成方式 | 是否与 Jugg 共享地址空间 | 技术结论 |
|---|---|---|---|---|
| Checker Qual 3.33.0 | MIT | 内嵌于官方 R8 8.4.21 JAR，由 JVM 加载 | 是 | 不依赖进程隔离；对应版本的 `checker-qual/LICENSE.txt` 明确提供 MIT License，发行清单和 SBOM 均选择 MIT。 |
| Checker Qual 3.5.0 | MIT | 作为 `cmd_line` 中 Guava 30.1-jre 的传递依赖，由 JVM 加载 | 是 | 不依赖进程隔离；对应版本的 `checker-qual/LICENSE.txt` 明确提供 MIT License，发行清单和 SBOM 均选择 MIT。 |
| OpenJDK JVMTI header | GPL-2.0-only WITH Classpath-exception-2.0 | 修改后的 `jvmti.h` 参与编译并进入 JVMTI native agent | 是 | 不应表述为进程隔离。Jugg 依据 Classpath Exception 使用该头文件，并提供上游基线、修改后源码和 patch；是否接受该例外条款口径由法务确认。 |
| rsync 3.4.1 | GPL-3.0-or-later | macOS 可执行文件由 shell 命令启动，通过命令行参数、stdin/stdout/stderr 和文件系统交互 | 否 | 作为独立操作系统进程运行，不与 IDE 插件 JVM 静态或动态链接，也不共享地址空间。 |
| sshpass 1.10 | GPL-2.0-or-later | macOS arm64 可执行文件作为 SSH 命令前缀由 shell 启动，通过命令行和进程 I/O 交互 | 否 | 作为独立操作系统进程运行，不与 IDE 插件 JVM 静态或动态链接，也不共享地址空间。 |
| JavaBeans Activation Framework 1.2.0 | CDDL-1.1 | 内嵌于官方 R8 8.4.21 JAR，由 JVM 加载 | 是 | 不依赖进程隔离；上游同时提供 CDDL-1.1/GPL-2.0，发行清单和 SBOM 固定选择 CDDL-1.1。 |

因此，不能笼统确认上述六类组件都通过 IPC 或命令行实现地址空间隔离。当前技术事实是：`rsync`、`sshpass` 满足独立进程边界；Checker Qual 和 JavaBeans Activation Framework 使用非 GPL 许可证分支；OpenJDK JVMTI header 使用带 Classpath Exception 的 GPL-2.0，并随发行提供修改源码与 patch。

## 2. LGPL 2.1 和 MPL 1.1 组件

| 组件 | 实际集成方式 | 隔离和修改状态 |
|---|---|---|
| Trove4J，JetBrains fork 1.0.20200330 | 以独立 `trove4j-1.0.20200330.jar` 随插件发行，由 JVM classloader 动态加载 | 与插件同一 JVM 地址空间，但保持独立 JAR，不静态合并到 Jugg JAR；Jugg 未修改该 JAR，并提供对应 source JAR 和 LGPL-2.1-or-later 文本。 |
| juniversalchardet 1.0.3 | 以独立 `juniversalchardet-1.0.3.jar` 随插件发行，由 JVM classloader 动态加载 | 与插件同一 JVM 地址空间，但保持独立 JAR，不静态合并到 Jugg JAR；Jugg 未修改该 JAR，按上游 POM 选择 MPL-1.1，并提供对应 source JAR 和许可证文本。 |

## 3. 仓库与发行证据

- 进程启动实现：`main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/CmdExecutor.kt`。
- rsync/sshpass 命令组装与资源复制：`main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/RsyncCommand.kt`。
- OpenJDK 修改基线和 patch：`third_party/sources/openjdk-jvmti-header/`。
- 许可证选择和使用关系：`third_party/components.csv`、`THIRD_PARTY_NOTICES.md`、`third_party/sbom/jugg-third-party.spdx.json`。
- 对应源码与校验值：`third_party/sources/README.md`、`third_party/sources/SHA256SUMS`。
- 插件产物边界：`idea/build/distributions/*.zip` 中的 `jugg/lib/` 和 `jugg/third_party/`。
