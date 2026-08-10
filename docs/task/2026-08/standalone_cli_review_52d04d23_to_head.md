# Standalone Jugg CLI 提交审查问题记录

## 审查范围

- 提交范围：`52d04d23^..HEAD`，包含 `52d04d23` 到 `344bd1543`。
- 审查重点：IDEA 插件端功能逻辑是否被破坏，以及 Standalone Jugg CLI 是否达到完整实现标准。
- 本文只记录已经确认的问题，不扩展到代码风格、一般重构质量或其他非目标事项。

## 问题 1

### 标题

IDEA 远程 Gradle 编译初始化 Project Info 时发生项目锁互相等待

### 复现步骤

1. 在 IDEA 插件中选择远程 Gradle 编译。
2. 使用尚无本地 Project Info 的项目启动 Jugg；也可以修改远程编译命令，使 `isCompileCommandChanged` 返回 `true`。
3. 等待远程 Gradle 构建完成并进入增量编译初始化阶段。
4. 观察 Jugg Run 一直停留在 Project Info 更新或后续 classpath 初始化阶段，流程无法结束。
5. 检查线程状态，可以看到当前 Run 线程等待 `GradleProjectInfoLocalFetchManager` 的 completion latch，而 Project Info 后台任务等待同一项目写锁。

### 详细解释

`JuggRunningTask.run()` 使用 `TaskRunnerManager.runProjectWriteLocked("Run Jugg")` 包住整个运行流程，因此远程 Gradle 构建及构建后的增量编译初始化都在项目写锁内执行。

远程编译准备阶段，`JuggCompilerHelper.prepareRemoteProjectInfo()` 在 Project Info 不可用或编译命令变化时调用 `runUpdateIfNeeded(..., shouldWaitForRemoteInit = true)`。该方法通过 `TaskRunnerManager.runTaskSafe()` 提交 Project Info 更新任务，但没有显式传入 `isProjectWrite = false`，所以任务使用默认值 `isProjectWrite = true`。

IDEA 的 `HostTaskExecutor` 会把该任务排入另一个 `Task.Backgroundable`。由于它运行在另一个线程，项目锁的同线程重入能力不能生效；更新任务只能等待外层 `Run Jugg` 释放项目锁。

远程构建完成后，外层 Run 仍持有项目锁，并在 `JuggManager.initIncrementalCompileAfterFullBuild()` 中调用 `waitForRemoteInitUpdate()`。此时形成稳定的等待环：

1. 外层 Run 持有项目锁，等待 Project Info completion latch。
2. Project Info 后台任务等待外层 Run 持有的项目锁。
3. 外层 Run 只有在初始化结束后才会释放项目锁。

相关实现位置：

- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt:99`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompilerHelper.kt:276`
- `main/src/main/java/com/sickworm/intellij/jugg/project/dependency/GradleProjectInfoLocalFetchManager.kt:140`
- `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/TaskRunnerManager.kt:117`
- `idea/src/main/java/com/sickworm/intellij/jugg/runtime/HostTaskExecutor.kt:22`
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt:445`

现有 `JuggManagerFullBuildFlowTest` mock 了 `TaskRunnerManager` 和 `GradleProjectInfoLocalFetchManager`，只验证方法调用顺序，没有运行真实的跨线程项目锁，因此测试通过不能排除该问题。

### 实施结论

已修复。Project Runtime Lock 改为同一 `FileExecutionLockManager` 内跨线程共享的引用计数 lease，只负责不同 Runtime 之间的项目持有权互斥。同 Runtime 由 `RuntimeTaskCoordinator` 按逻辑 owner 串行独立事务；远程编译事务通过 TaskRunner 提交默认阻塞、非阻塞、后台或 async 子任务时，子任务自动继承父 owner 并共享外层 Runtime lease，因此可在父任务等待期间完成。另一个 Runtime 仍需等到本 Runtime 的最后一个 lease 引用释放。

调用方不需要识别顶层任务或事务内子任务。协调器自动捕获并传播当前 owner，同 owner 只有引用计数、没有等待边；无关 owner 继续串行。`status` 使用非阻塞 owner/lease 尝试保留“活跃事务期间不 refresh”的一致性快照语义。L2 回归覆盖同 Runtime lease 共享、最后引用释放、独立事务串行、默认阻塞/非阻塞/后台/async 子任务完成和非阻塞 `try`。

dispose 生命周期也已闭环。IDEA 工程关闭时不强制释放共享 lease；`GradleProjectInfoLocalFetchManager.close()` 会完成尚未结束的 remote-init completion，并让等待方得到取消结果。`JuggManager` 收到取消后立即终止后续 classpath 初始化，TaskRunner 事务随后通过既有 `finally` 自然释放 logical owner 与 Project Runtime lease。即使 IDEA 平台未启动已排队的 Project Info Host task，也不会再遗留永久等待或跨 Runtime 文件锁。

## 后续锁审查问题

### 标题

Global Lock 同时承担任务事务与资源提交，形成确定锁反序和父子进程互等

### 复现步骤

#### Settings 锁反序

1. 在一个线程中修改 `JuggSettings`，使其进入同步的 `write()` 或 `migrate()`，先持有 `JuggSettings` monitor，再等待 Global Lock 写入 `settings.json`。
2. 同时在 IDEA 中执行“Set custom server”。改造后的后台任务会先以 global write task 身份持有 Global Lock。
3. 该任务调用 `JuggServer.setCustomServer()`，最终进入 `JuggSettings.serverUrl` setter，等待 `JuggSettings` monitor。
4. 两个线程分别持有 monitor 与 Global Lock，并等待对方，形成永久等待。

#### Standalone 安装父子进程互等

1. 在 IDEA 的 Install Jugg Skills 对话框中选择需要安装 Standalone runtime 的选项。
2. IDEA Host task 以 global write task 身份持有 `~/.jugg/locks/global.lock`。
3. `StandaloneBundleInstallService` 启动独立安装器子进程并同步等待退出。
4. 子进程中的 `StandaloneRuntimeInstaller` 尝试取得同一个 Global Lock 后才能安装。
5. 父进程等待子进程，子进程等待父进程释放文件锁，安装永远无法完成。

### 详细解释

根因不是 Global Lock 本身，而是它同时承担了两种不兼容的职责：

1. TaskRunner 任务锁：调用方先取锁，再在锁内执行任意业务 callback、IDEA API、settings setter 或子进程等待。
2. 全局资源锁：具体资源 owner 在读改写 `~/.jugg` 共享文件时保证跨 Runtime 原子提交。

第一种职责会让 Global Lock 出现在任意业务锁之前，并允许锁内继续申请 monitor、Project Runtime Lock 或由子进程再次申请 Global Lock，无法形成单向锁等待图。第二种职责只需要保护资源 owner 的提交边界，可以固定为等待图叶子。

相关实现位置：

- `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggSettings.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JsonRuntimeSettingsRepository.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/MoreOptionsManager.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/InstallJuggSkillsDialog.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/StandaloneBundleInstallService.kt`
- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneRuntimeInstaller.kt`

### 实施结论

已按职责删除方案修复。`TaskRunnerManager` 删除 `isGlobalWrite`、`runGlobalWriteLocked()` 和 `IExecutionLockManager.withGlobalLock()`，只负责普通任务与 Project Runtime 事务。IDEA 业务层不能直接取得 Global Resource Lock；settings repository、hot update manager、CLI/skills installer、runtime resource manager 和 history store 等资源 owner 在内部提交共享文件变更。

因此 TaskRunner action、IDEA callback 和父进程等待不再持有 Global Lock。`JuggSettings` 只保留 monitor → Global Resource Lock 的单向关系，不再存在 Global Lock → monitor 的反向入口；Standalone 安装父进程也会在无锁状态启动并等待子进程，由子进程独占完成资源提交。静态架构守卫固定检查 TaskRunner 与 IDEA production source 不得重新引入 Global Resource Lock 所有权。

后续独立审查发现，`JuggHotUpdateManager.prepareUpdate()` 仍曾在 Global Resource Lock 内执行同步 HTTP 下载，慢速且永不结束的响应会永久占用全部全局资源。现已改为两阶段资源事务：短锁快照已校验缓存与当前 metadata，锁外下载和校验到唯一 staging 目录，最终短锁比较 metadata 基线并原子发布。下载期间 settings、Standalone installer 等其他资源 owner 不再等待；若其他 Runtime 已先完成更新，较慢请求会被判定为 superseded，不能覆盖更新后的 manifest 与 metadata。

Windows CLI 安装也曾在 Global Resource Lock 内通过 `reg.exe` 读改写用户 PATH，并在读取完整 stdout 后无超时等待进程退出；子进程不关闭输出或不退出时会永久持有 G。现已将 PATH 更新移到 CLI 文件提交之后的锁外阶段，`reg.exe` 输出重定向到临时文件并设置 5 秒硬超时，超时后强制终止并返回安装失败，不影响其他全局资源 owner。

当前保留资源 owner 直接使用 `withGlobalResourceLock` 的轻量模型，并在 API 注释中固定临界区契约：action 必须同步完成，只能进行有界本地资源读改写，不得启动异步工作、等待外部资源、调用业务 callback 或申请业务 monitor。锁底层记录当前线程的 G 持有深度；若同线程在 G 内尝试阻塞式或 try Project Runtime Lock，立即抛出锁顺序错误，避免约定被直接调用路径破坏。

## 问题 2

### 标题

Standalone Bundle 安装未形成 manifest 提交点控制的完整原子事务

### 复现步骤

1. 安装一个可正常运行的 Standalone Bundle A，确认 active manifest 指向 A。
2. 使用调试器运行 Standalone Bundle B 的安装流程。
3. 在 `StandaloneRuntimeInstaller.installValidated()` 即将执行 `writeAtomically(activeManifestFile, bundle.manifest)` 时暂停。此时 Python CLI 和 launcher 已经被替换。
4. 直接终止安装进程，模拟进程退出、断电或 active manifest 写入失败。
5. 检查安装目录：active manifest 仍指向 A，但 `~/.jugg/bin`、`~/.jugg/standalone/bin` 或 bootstrap 已经来自 B。
6. 启动 `jugg` 或 `jugg-standalone`，可以得到旧 runtime 与新 tooling 的半激活组合；如果进程终止发生在删除 live CLI 后、移动 staging CLI 前，CLI 目录还可能直接缺失。

### 详细解释

设计要求把 bootstrap、Python CLI 和 launcher 资源先 stage 到 `~/.jugg/standalone/releases/<releaseBuildId>/`，由稳定 selector 和 CLI wrapper 根据 active manifest 中的 `toolingReleaseBuildId` 选择同一版本 tooling。只有 active manifest 原子替换成功后，新 tooling 和 runtime 才能同时生效。

当前实现虽然最后才写 active manifest，但在提交前已经执行以下不可回滚的 live 路径修改：

1. `publishTooling()` 把 bootstrap 发布到 `~/.jugg/standalone/bootstrap/<toolingReleaseBuildId>`，而不是版本化 release 闭包。
2. `installPythonCli()` 先递归删除当前 `~/.jugg/bin`，再移动 staging 目录。
3. `installLaunchers()` 直接覆盖稳定 launcher 文件，并把 bootstrap 绝对路径固化到脚本中。
4. 上述操作全部完成后，才替换 active manifest。

因此 active manifest 只是 runtime manifest 的最后写入点，并不是整个 tooling/runtime 组合的事务提交点。安装过程在该窗口内失败时，旧版本无法继续通过稳定入口选择完整的旧 tooling。

相关实现位置：

- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneRuntimeInstaller.kt:58`
- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneRuntimeInstaller.kt:75`
- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneRuntimeInstaller.kt:171`
- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneRuntimeInstaller.kt:182`
- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneRuntimeInstaller.kt:191`
- `docs/task/2026-08/standalone_jugg_cli_design.md:755`

现有 `StandaloneRuntimeInstallerTest` 覆盖正常安装和 Bundle 预校验失败，但没有在 CLI、launcher 已发布而 active manifest 尚未提交的窗口注入失败。Standalone 已明确不保留 previous manifest，也不提供启动失败自动 rollback；安装或启动失败由用户重新安装恢复。

### 实施结论

已按主流程简化，不实现 tooling/runtime 的完整 crash-safe 事务。安装器仍以 active manifest 作为 Runtime 最后提交点；提交成功并释放 Global Resource Lock 后，扫描同一 Jugg 根目录下主类为 `StandaloneBootstrap` 的进程并强制终止，确保下一次 CLI 调用按新 manifest 启动 Runtime。安装失败由用户重新安装恢复，启动失败直接返回异常且不自动 rollback。

## 问题 3

### 标题

Standalone 安装器验证的 Java/Python 命令与最终 launcher 实际使用的命令不一致

### 复现步骤

#### Python 场景

1. 在 macOS/Linux 环境中准备 Python 3.7+，只提供 `python` 命令，不提供 `python3` 命令。
2. 执行 Standalone 安装。`PythonRuntimeResolver` 会回退到 `python`，环境验证通过。
3. 安装完成后运行 `~/.jugg/bin/jugg`。
4. wrapper 固定执行 `python3`，命令立即因找不到 `python3` 而失败。
5. Windows 可使用反向环境复现：只提供 `python3` 而不提供 `python`，验证通过后 `.cmd` wrapper 固定调用 `python` 并失败。

#### Java 场景

1. 设置有效的 `JAVA_HOME`，但让 PATH 中没有 `java`，或让 PATH 中的 `java` 指向不满足要求的版本。
2. 直接执行 Bundle 的 `install.sh`。
3. 安装脚本固定调用 PATH 中的 `java`，不会使用 `$JAVA_HOME/bin/java`，因此无法启动安装器或使用了错误 JVM。
4. 即使通过 `$JAVA_HOME/bin/java` 手工启动安装器，安装后的 `jugg-standalone` 仍固定调用 PATH 中的 `java`，daemon 启动继续失败。

### 详细解释

`PythonRuntimeResolver.requireCommand()` 按 `python3`、`python` 顺序返回实际可用命令，但 `StandaloneRuntimeInstaller.validateEnvironment()` 只检查返回过程是否成功，没有保存该命令。安装后的 POSIX wrapper 固定写入 `python3`，Windows wrapper 固定写入 `python`，使环境验证与实际运行形成两套选择规则。

Java 也存在同类问题。设计要求优先使用 `JAVA_HOME`，再回退 PATH，并验证是完整 JDK。当前 Bundle 的 `install.sh`、`install.cmd` 和安装后的 daemon launcher 都直接执行 `java`，没有统一的 Java 解析结果，也没有把安装阶段验证过的运行方式传递给稳定 launcher。

相关实现位置：

- `main/src/main/java/com/sickworm/intellij/jugg/ai/skills/PythonRuntimeResolver.kt:11`
- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneRuntimeInstaller.kt:105`
- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneRuntimeInstaller.kt:171`
- `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneRuntimeInstaller.kt:191`
- `cmd_line/cmdline-distribution.gradle:91`
- `docs/task/2026-08/standalone_jugg_cli_design.md:761`

该问题会导致安装器在官方声明支持的环境组合中报告成功，但安装产物无法正常运行。

## 问题 4

### 标题

Standalone 资源 HOT RELOAD 仍会重启 Activity，未达到最终 Flow 验收标准

### 复现步骤

1. 使用 Standalone Jugg CLI 对 demo 工程执行 Gradle baseline，并把应用安装到真实 emulator 或 device。
2. 在目标 Activity 中观察生命周期日志，或记录 `onCreate()` 调用次数。
3. 修改一个会影响当前页面的 Android 资源，例如 string、layout 或 drawable，不修改业务代码。
4. 通过 Standalone CLI 执行增量编译和 deploy。
5. 资源能够更新，但 Activity 会被重启，可以观察到新的 `onCreate()` 调用。
6. 对照最终 L3 验收目标，预期应是 UI 生效且 Activity 不重启。

### 详细解释

共享 `JuggDeployer.fullSwap()` 固定调用 `optimisticSwap(..., argRestart = true, ...)`。Standalone 的 `StandaloneApplyChangesExecutor.optimisticSwap()` 又把该值原样传给 Quail `OptimisticApkSwapper` 的 `restartActivity` 参数，因此资源 full swap 的当前行为必然包含一次 Activity restart。

这与 Step 9 验证固定 Quail deploy 闭包时的预期一致，但不是 Step 10～12 定义的最终结果。Standalone 设计文档明确把“资源更新时 Activity 不重启且 UI 生效”列为完整 Flow 的最终验收目标，并明确说明 Step 9 的 Activity restart 不能代表该目标已经完成。

相关实现位置：

- `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt:131`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt:208`
- `deploy_compat/standalone_deployer/src/main/java/com/sickworm/intellij/jugg/deploy/run/StandaloneApplyChangesExecutor.java:124`
- `docs/task/2026-08/standalone_jugg_cli_design.md:1363`
- `docs/task/2026-08/standalone_jugg_cli_design.md:1378`

现有 Standalone deploy 单元和设备流程测试证明固定 Quail 路径可以完成部署，但没有提供真实资源变更后 Activity 生命周期保持不变的 L3 证据。因此当前 Standalone CLI 的编译部署主链已经建立，但资源 HOT RELOAD 仍不能判定为完整实现。
