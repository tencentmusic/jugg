# Standalone Jugg CLI 设计方案

## 1. 背景与目标

当前 Jugg 包含三种运行形态：

- Android Studio / IntelliJ 插件运行时，负责编译、部署、设备状态和 MCP runtime。
- `cmd_line` CI 运行时，负责 Gradle 基线构建与指定 changed files 的增量 APK 构建。
- Python `jugg` CLI，通过 MCP Server 调用已发现的 IDEA 或 standalone 运行时。

本方案新增完全脱离 IDEA 的 standalone CLI runtime，并满足以下首期目标：

- 支持真正的 HOT RELOAD，不以完整 APK 重装或仅 Direct Overlay 代替。
- 支持 macOS、Linux、Windows。
- 现有 `cmd_line` CI 行为保持独立和兼容。
- Python `jugg` CLI 继续作为 MCP 客户端。
- IDEA 插件继续使用 IDEA 内运行时，首期不改造成 standalone daemon 的薄客户端。
- IDEA Runtime 与 Standalone Runtime 复用同一套核心编译、部署编排和持久化协议。

## 2. 核心决策

### 2.1 首期采用双 Runtime

首期运行结构：

```text
                         ┌─ IDEA Runtime Environment
Jugg Runtime Engine ─────┤
                         └─ Standalone Runtime Environment
                                  │
                                  └─ MCP daemon / Python jugg CLI
```

IDEA Runtime 与 Standalone Runtime：

- 独立进程运行。
- 不共享内存状态。
- 共享 `build/jugg` 项目状态和 `~/.jugg` 全局状态。
- 通过跨进程锁保证写操作串行。
- 通过相同 Runtime Engine 避免形成两套编译、部署和 recover 逻辑。

首期不采用“AS 只作为调用窗口、所有业务均运行在 daemon”的方案，原因是该方案要求同时迁移 Gradle Sync、VFS、Run Configuration、设备选择、console、progress、cancel、Debug attach 等大量 IDE Host 能力，会显著扩大 HOT RELOAD 首期改造范围。

### 2.2 CI 与 standalone 保持单模块、独立入口

保留单一 `:cmd_line` Gradle 模块和现有 CI 源码、包名、任务与产物语义；不拆分 `:cmd_line:ci` 子模块。Standalone 只在该模块中新增独立包：

```text
cmd_line/src/main/java/.../cmdline/
├── <现有 CI 源码和包名保持不变>
└── standalone/
    ├── JuggDaemon
    ├── StandaloneProjectRegistry
    ├── StandaloneProjectRuntime
    ├── StandaloneRunConfigurationManager
    └── StandalonePlatformApi
```

CI 命令继续保持一次性进程、显式参数和现有产物语义。Standalone 使用常驻 daemon、项目恢复、自动文件变化检测和设备部署，不复用 CI 的 `.dirty` 一次性约束。`:cmd_line` 与 standalone 代码均以 Java 11 编译和运行。

### 2.3 Standalone Apply Changes 使用独立 Gradle 模块

新增模块：

```text
:standalone_deployer
```

该模块只由 standalone runtime 依赖，不进入 IDEA 插件 classloader。模块固定使用 Android Studio Quail 版本的 deployer 实现和二进制协议，不承担 Android Studio 多版本兼容。

Quail 的现成 deployer class 为 Java 21 字节码，不能被 Java 11 daemon 直接加载。`:standalone_deployer` 与 `:cmd_line` 均固定以 Java 11 编译和运行：standalone 通过受控反编译回迁实现实际所需 deployer 闭包，而不是加载 Quail 的现成 class。

## 3. Runtime 能力现状与领域划分

### 3.1 尚未下沉到 main 的能力

以下能力当前位于 `idea`，`cmd_line` 无法直接复用：

| 能力 | 当前实现 | 下沉方向 |
|---|---|---|
| 项目 Runtime 生命周期 | `JuggManager` | 当前由 IDEA 协调初始化、恢复、关联和释放；先下沉项目模型、文件变化、配置与编译部署领域，具备真实复用对象后再建立具体 `JuggProjectRuntime` 聚合 |
| IDEA 项目注册与 MCP 路由 | `JuggInitializer`、`JuggManagerCreator` | IDEA 保留项目入口；standalone 使用独立 registry |
| 任务调度、进度、串行和上报 | `TaskRunnerManager` | `TaskRunnerManager` 直接下沉 `main`，保留共享串行/取消/上报语义；IDEA 仅以 adapter 提供 `Task.Backgroundable`、进度与 EDT 表现，事件上报直接使用共享 `JuggServer` |
| Compile Context 管理 | `CompileContextManager` | 拆出共享 `CompileContextManager` 核心和 IDEA project model 读取逻辑 |
| Gradle project info 更新调度 | `GradleProjectInfoLocalFetchManager` | 下沉 Gradle 调度主体，移除 IDEA `Project` 依赖，继续使用共享 `TaskRunnerManager` 保留项目锁和 Host task 语义 |
| IDEA VFS 监听 | `FileChangesDetector` | 保留 IDEA 实现；standalone 使用 WatchService |
| Git 变化补偿 | `GitFileChangesDetector` | 下沉，在共享文件变化域中使用 |
| 远端 generated source 回写 | `CopyGeneratedSourceHelper` | 下沉，关联共享任务管理器 |
| deployment cache 实现 | `JuggDeploymentService`、`JuggDeploymentCacheStore` | 随部署域一起下沉 |
| 部署状态 | `DeployStateManager` | 以 `IDeployStateManager` 作为 Runtime 依赖，补齐部署可行性、build-file 状态和 pending file-processing barrier；默认实现随部署域下沉 |
| 插件 hot update | `IdeaHotUpdateCoordinator`、`JuggHotUpdateManager` | IDEA 保留检查调度、通知、插件安装和重启；共享下载校验、文件发布与 standalone 下次启动加载 |
| UI、设置页和诊断入口 | `MoreOptionsManager`、IDE dialogs | IDEA 保留表现层，调用共享领域服务 |
| Run/Debug/Test UI | `JuggConfigurationRunner`、Debug attach、SM Runner | IDEA 保留，不进入 standalone runtime |

### 3.2 已在 main 但尚未具备 standalone 语义的能力

#### Runtime Settings

`JuggSettings` 位于 `main`，但当前通过 IntelliJ `PropertiesComponent` 持久化。`platform_compat` 中的实现只是进程内 Map，不能在进程退出后保存，也不能在 IDEA 与 standalone 间共享。

新版统一使用 `~/.jugg/settings.json`。首次新版 IDEA 启动时读取旧 `PropertiesComponent`：仅回填 JSON 中缺失字段，JSON 已有字段永远优先。standalone 当前只读取已存在的 JSON；文件不存在时使用内存默认值，不创建该文件。

JSON 写入使用统一全局写锁、临时文件和原子替换，避免 IDEA 的迁移或后续设置修改产生半写入文件。

共享设置直接由 `JuggSettings` 持有默认值和内存 effective fields，底层只保留通用 JSON 字段仓储：

```text
JuggSettings
└── JsonRuntimeSettingsRepository
```

IDEA 旧 `PropertiesComponent` 仅由 `JuggManager.init()` 提交的 `Init Jugg` 后台任务通过 `JuggSettings.migrateLegacyJuggSettings()` 扩展入口转换为字段 Map，并回填缺失字段；成功后记录迁移完成标记，迁移失败不阻断启动、不清理旧属性，下次启动继续重试。IDEA 与 standalone 均在首次 persisted setting get/set 时自动读取 JSON，文件缺失时直接使用 `JuggSettings` 默认值。

共享运行设置必须覆盖会影响 compile/deploy/history 语义的开关，包括 Direct Overlay、兼容部署、project Kotlin compiler、backup classpath、device compat record 和 slice deploy record。

#### Dependency Change

`DependencyChangeManagerByGradle` 已在 `main`，但确认流程依赖 `PlatformApi.showChangeConfirmDialog()`。下沉后复用现有编译交互边界：

```text
CompileUiHandler.confirmDependencyChanges()
├── JuggCompileUiHandler（IDEA dialog）
└── Standalone CompileUiHandler（配置、命令参数或 MCP confirmation）
```

`IDependencyChangeManager` 只应用确认结果，不感知 dialog 或 Host 类型。CLI handler 返回继续增量、Gradle rebuild、取消等确定结果，不模拟 IDEA dialog。

#### Project Info Merge

`JuggProjectInfoMerger` 已在 `main`，但当前以 IDE project info 为 base。项目模型域需要支持两种明确模式：

```text
IDEA Runtime: IDE model + Gradle model
Standalone Runtime: Gradle model only
```

Standalone 不构造假的 IDE project info。

#### Jugg Server 与 MCP

`JuggServer`、MCP 协议和多数 action 已在 `main`，但仍存在 plugin version、`Project.basePath`、设备和 IDEA run runner 等 host 语义。需要关联运行时元数据、项目目录和共享领域服务，不能仅依赖 platform mock。

#### Custom Compiler

`ProjectCustomConfigManager`、`CustomCompilerManager` 已在 `main`，主体可复用；前者隐藏项目配置文件存储并统一负责 reload、server default 更新、custom classpath 和 embedded APK，后者负责 jar 下载、SPI 装载与 compiler 生命周期，再纳入后续 `JuggProjectRuntime` 聚合。

共享 `main` 组件持有 classloader、流或其他可关闭资源时，生命周期接口优先使用 `AutoCloseable`，不向 standalone 领域 API 暴露 IntelliJ `Disposable`。只有第三方/SPI 签名明确要求 `Disposable` 时，才允许在组件内部保留最小 compatibility scope，并由组件的 `close()` 统一释放；IDEA adapter 在自身 dispose 生命周期中调用 `close()`。

### 3.3 已基本可直接复用的能力

- `JuggPathManager`
- `ProjectCustomConfigManager`
- `FileChangesHandler`
- `DeployHistoryManager`
- `JuggRunningTaskStatusManager`
- Gradle project info reader/serializer
- `JuggProjectInfo` / `ModuleInfo`
- `JuggServerChooser`
- `McpFetchCleaner`
- `JuggCliAutoUpdater`
- dependency diff 纯计算
- Git manager
- compile/deploy 数据模型

`JuggCliAutoUpdater` 等全局写任务必须进入统一全局写锁；`McpFetchCleaner` 只清理项目级目录，继续作为普通后台任务执行。

### 3.4 项目运行域

共享 Runtime 聚合后置到项目模型、文件变化、配置与编译部署领域完成下沉之后。`JuggProjectRuntime` 使用具体类和组合，不通过继承扩展，也不预建 lifecycle、controller、binder 等单实现接口。

目标聚合对象：

```text
JuggProjectRuntime
├── CompileContextManager
├── FileChangeManager
├── DependencyChangeManager
├── JuggSettings
├── ProjectCustomConfigManager
├── CustomCompilerManager
├── TaskRunnerManager
├── JuggCompileOrchestrator
├── JuggDeployOrchestrator
├── IDeployStateManager
└── McpToolInvoker
```

`JuggProjectRuntime` 负责领域对象生命周期和协作顺序，不包含 Swing、RunManager、IDE notification、IDE project service 等表现层能力。

只在 IDEA 与 standalone 已存在真实差异实现时保留 Host 接口：

```text
IHostTaskExecutor
IHostDeployStateResolver
IProjectModelSource
IFileChangeMonitor
IApplyChangesExecutor
IRuntimeConfigRepository（仅配置来源确有差异时）
```

关键生命周期：

```text
initialize
→ load settings/custom config
→ recover project model and compile context
→ recover APK/deploy/file state
→ bind compiler/custom compiler/file rules
→ start file monitor and background maintenance
→ ready
```

project info 变化后的重新关联顺序保持现有语义：

```text
DeployFileManager
→ JuggCompiler
→ FileChangesHandler
→ GitFileChangesDetector
→ CustomCompilerManager
```

### 3.5 IDEA Manager 薄化

`JuggManager` 最终只承担：

- 持有由 `IdeaRuntimeAssembler` 创建的具体 `JuggProjectRuntime`，不继承 Runtime。
- 把 Gradle Sync、VFS、Run、Debug、AndroidTest 等 IDEA 事件转换为领域事件。
- 把 IDEA UI 操作转成领域命令。
- 关联 IDEA console、dialog、notification、Debug attach、SM Test Runner。
- 随 IDEA project 生命周期初始化和销毁 runtime。

IDEA Runtime：

```text
JuggManager
└── JuggProjectRuntime
    ├── IdeaProjectModelSource
    ├── IdeaFileChangeMonitor
    ├── JuggSettings（启动时迁移 IDEA legacy fields）
    ├── JuggCompileUiHandler
    ├── HostTaskExecutor
    └── IdeaApplyChangesExecutor
```

Standalone 对应：

```text
StandaloneJuggRuntimeAssembler
├── GradleProjectModelSource
├── WatchServiceFileChangeMonitor
├── JuggSettings（直接读取共享 JSON）
├── Standalone CompileUiHandler
├── StandaloneHostTaskExecutor
├── StandaloneApplyChangesExecutor
└── JuggProjectRuntime
```

领域对象通过具体 assembler 完成一次性构建和关联，assembler 不抽接口。业务代码不使用无业务语义的依赖获取器，也不扩展一个包揽所有能力的 `PlatformApi`。

### 3.6 文件变化一致性

共享 `FileChangeManager` 负责：

- changed/deleted/rename 归一化。
- `FileChangesHandler` 过滤。
- `DeployFileManager` 状态更新。
- build file 变化通知。
- Git reconcile。
- compile-on-save。
- pending file-processing barrier。

IDEA 使用 VFS monitor，standalone 使用 WatchService monitor；两者都必须保留 `beginFileProcessing/endFileProcessing` 的一致性屏障。WatchService overflow 后执行完整 Git reconcile。

### 3.7 后台任务和全局资源 owner

`TaskRunnerManager` 统一管理无锁、项目锁和固定全局写锁任务，并跟踪 Job 以便 Runtime dispose 时取消尚未执行的任务。当前任务包括：

后台任务只保留 `runBackgroundSafe()`：`isProjectWrite`、`isGlobalWrite` 默认均为 `false`，普通后台任务不获取写锁；两者互斥，分别选择项目锁或统一全局写锁。`runTaskSafe()` 使用相同的锁选择语义；其默认仍为项目写任务，设置 `isGlobalWrite=true` 时会自动关闭项目锁与增量编译阻塞默认值。业务调用不再传递全局锁名字，也不需要在 action 内手工组合锁。

- compiler warm-up。
- project info 延迟复查。
- deployment cache 预加载。
- MCP fetch 清理。
- CLI/skills 更新。
- server update/custom config 检查。
- standalone runtime/deployer resource 校验。

双 Runtime 下，CLI/skills 更新、全局 runtime resource 解压、settings 写入、library Test APK build history 和 hot update 写入统一使用 `~/.jugg/locks/global.lock`。IDEA 与 standalone 共享 `jars/` 和新的 `load_manifest.json` 完整快照，不兼容旧 `load_list.txt`。启动 Loader 不进入任务域或全局锁，只校验 manifest 的 embedded build time、读取 jar 列表并刷新所用 jar 的修改时间，不删除任何文件。IDEA hot update 初始化仅在 `hot_update` 目录已经存在时，于全局锁内将当前 packaged jars 发布到同一 manifest；目录不存在时不得主动创建。服务器更新继续原子替换该 manifest。未引用且超过 90 天的 jar 由下载端在写锁内清理。IDEA 保留插件安装/重启；standalone 在下一次 daemon 启动时加载同一快照，不在当前进程热更新。若更新标记 `isNeedReinstall=true`，只下载和记录，不更新 load manifest。

锁能力统一从 `TaskRunnerManager` 暴露：常规任务使用实例 API；runtime resource 首次解压、history 持久化和手册导出等尚未进入 TaskRunner 生命周期、但确实需要写全局文件的基础设施入口使用 `TaskRunnerManager.runGlobalWriteLocked()` 静态入口。hot update Loader 是无锁只读 bootstrap，不依赖 TaskRunner。`ExecutionLockManager.kt` 中的 Runtime identity、owner、锁接口和文件锁实现全部为 `internal` / `private`，调用方不再感知任何锁实现类型。

### 3.8 诊断和运维能力

本阶段不预建统一 diagnostics/maintenance manager。IDEA 继续使用现有 `ProjectInfoReader`、logcat dump 和 `JuggServer.reportAndUploadLogs()`；TaskRunner 不为尚未落地的 doctor 命令增加 job/task 观测状态。

standalone 的 `doctor/report`、clean/reset 等命令在出现真实调用入口时，再从 IDEA 与 standalone 的共同数据需求提取聚焦服务。custom server、CLI/skills update 和 MCP fetch cleanup 当前继续由既有业务对象负责，调用方通过 `TaskRunnerManager` 选择项目锁或全局锁。

## 4. 锁与并发模型

### 4.1 TaskRunnerManager 改造结论

改造前的 `TaskRunnerManager` 使用 `synchronized(this)`：

- 只能串行同一个 JVM 内、同一个 `TaskRunnerManager` 实例的任务。
- 无法协调 IDEA 与 standalone 两个进程。
- 无法协调不同项目实例对 `~/.jugg` 全局文件的并发写入。

当前已保留“所有项目写任务通过 TaskRunnerManager 执行”的设计，并将 `TaskRunnerManager` 下沉到 `main`，移除 `Project`、`Task.Backgroundable`、ProgressIndicator 和 EDT 依赖，形成可被 IDEA 与 standalone 共用的跨进程任务与锁框架。IDEA 表现层通过 adapter 关联后台任务和进度；任务完成事件直接交给两端已有的 `JuggServer`，不额外定义 reporter adapter。

建议职责：

```text
TaskRunnerManager
  -> IExecutionLockManager (internal)
       -> project lock
       -> fixed global write lock
```

锁实现使用 JVM 进程内可重入锁 + NIO `FileChannel.tryLock/lock`。外层第一次进入时获取文件锁，嵌套调用只增加当前进程持有计数，避免相同任务链重复获取同一个文件锁。不同 Runtime/classloader 可能各自持有一份 JVM 锁表；遇到同进程 `OverlappingFileLockException` 时按短间隔等待重试，仍以文件锁作为最终串行依据。

### 4.2 项目运行锁

项目锁路径：

```text
<projectDir>/build/jugg/runtime.lock
```

锁范围覆盖完整写事务：

```text
refresh changes
→ compile / Gradle build
→ build deploy data
→ deploy / recover / retry
→ commit deploy history
→ write CLI run configuration
```

需要进入项目写锁的任务包括：

- 项目初始化和 project info 更新。
- Gradle build。
- 增量编译。
- deploy、clean-reinstall、instrument。
- deploy history、compile context、classpath 和配置写入。
- 影响后续编译判断的文件变化批处理。

纯查询命令可以读取稳定快照；若查询依赖正在写入的多文件状态，应等待项目锁或返回当前 job 状态，不能读取半提交状态。

锁元数据单独写入诊断文件或锁文件旁路文件，包含：

- runtimeType：`idea` / `standalone`
- pid
- runtimeVersion
- jobId
- command
- acquiredAt
- projectDir

获取失败时 MCP 返回结构化错误：

```text
PROJECT_RUNTIME_BUSY
```

CLI 原有 `--if-compiling wait|interrupt` 继续保留：

- `wait` 等待当前 owner 释放项目锁。
- `interrupt` 只允许中断同 runtime 管理的任务；不能直接终止另一个进程的任务。
- 另一个 runtime 持锁时，`interrupt` 返回 owner 信息，由用户显式决定是否切换 runtime。

### 4.3 设备 + package 锁

首期不增加独立的 host 侧设备/package 文件锁。

依据：

- Direct Overlay 写脚本在设备端修改前原子校验 expected overlay id，并在最后写入新 overlay id。
- Apply Changes installer/swapper 也以 deployment cache 和 overlay id 作为一致性检查点。
- 同一项目的并发任务已经由项目锁串行。

不同项目同时向同一 device + applicationId 部署时，首期接受其中一方因 overlay mismatch 失败并进入 recover，不承诺两个任务均成功。

方案中保留并发验证任务，必须证明：

- 两个项目基于同一旧 overlay id 并发部署时，不会产生无法恢复的半提交状态。
- 最多一个任务成功，另一个任务明确返回 mismatch/recover 结果。
- deployment cache、项目 deploy history 和设备 overlay id 最终能够重新收敛。

若 Quail legacy optimistic swap 不能提供同等级别的原子校验，再增加：

```text
~/.jugg/locks/deploy/<deviceSerial>/<applicationId>.lock
```

### 4.4 项目级 deployment cache

`JuggDeploymentCacheStore` 改为项目级存储：

```text
<projectDir>/build/jugg/deploy_cache/.deploy_cache.db
```

IDEA 与 standalone 对同一项目共享该 cache，不同项目不再读写同一个全局文件，因此不增加全局 deployment cache 锁。`JuggDeploymentService` 必须改为项目级 Runtime 服务，不能再是全局 singleton。

cache 读写受项目锁保护，写入采用临时文件、flush 后原子替换。旧 `~/.jugg/deploy_cache` 无法可靠归属到项目，不迁移；首次读取按 cache miss 进入现有 recover 流程。

首期不增加设备/package 锁；若后续增加，锁顺序固定为：

```text
project lock
→ device/package lock
```

### 4.5 Runtime 切换

项目锁释放后允许另一个 runtime 接管。接管时检查：

- 上次 runtime owner。
- runtime version。
- 持久化 project / compile / deploy context 的兼容性。
- deployment cache entry。
- 设备 overlay id。

owner 变化后：

1. 丢弃 runtime 内存 cache。
2. 从磁盘恢复 compile context、deploy history 和 APK 信息。
3. 刷新 Git changed files。
4. deploy 前复用现有 recover 状态机校验 cache/history/device。
5. 不因 owner 变化直接重装；只有现有 recover 判断失败时才 reinstall。

## 5. Standalone Apply Changes 实现

### 5.1 固定 Quail 实现

Standalone deployer 实现以以下安装目录作为实现和二进制事实来源：

```text
/Applications/Android Studio Quail 1.app
```

当前已确认的事实来源：

```text
Android Studio build: AI-261.23567.138.2611.15503007
Deployer jar: Contents/plugins/android/lib/sdk-tools.jar
OptimisticApkSwapper class major version: 65
AdbInstaller class major version: 65
Installer resources: Contents/plugins/android/resources/installer
Installer resources size: about 23 MB
```

首期不查找和适配多个源码版本，不依赖完整 Android Studio runtime jar，不分“完整 jar PoC”和“依赖裁剪正式版”两个阶段。

实施方式：

1. 梳理当前 `JuggDeployer` 实际调用的 deployer 类型和方法。
2. 从 Quail 对应 class 直接反编译所需实现和传递依赖闭包，并以 Java 11 源码重编译。
3. 迁入 `:standalone_deployer`，只允许进入 `IApplyChangesExecutor` 实际调用链上的 class 和显式第三方依赖；禁止按 package 或整 jar 无边界迁入。
4. 保留原始 `com.android.tools.deployer` 包名，避免 package-private、反射和协议代码因 relocation 失效。
5. 只引入实际需要的第三方基础依赖，不携带完整 IDEA/Android Studio jar。
6. installer、agent、app-server 直接复用 Quail 二进制产物。

由于 standalone deployer 运行在独立 daemon JVM，保留原包名不会与 IDEA 插件内的 Android Studio deployer class 发生 classloader 冲突。

Standalone deployer 必须在 Java 11 JVM 中完成 install、class HOT RELOAD 和 resource HOT RELOAD 验证。若反编译闭包存在 Java 11 不可用的 JDK API 或依赖，必须在受控闭包内完成等价实现；不得退回为加载 Quail 的 Java 21 class，也不得扩大迁入范围规避问题。

### 5.2 二进制资源

资源按版本存放：

```text
standalone_deployer/src/main/resources/deployer/quail/
├── metadata.json
└── installer/
    ├── armeabi-v7a/installer
    ├── arm64-v8a/installer
    ├── x86/installer
    └── x86_64/installer
```

`metadata.json` 记录：

- Android Studio product/build version。
- 源安装路径。
- 每个二进制文件 SHA-256。
- installer/deployer 协议版本。
- 反编译 class 清单和 SHA-256。
- license/source notice。

Standalone runtime 首次启动时将资源释放到：

```text
~/.jugg/runtime/<juggVersion>/deployer/quail/
```

运行时必须校验 metadata 和文件 SHA-256，禁止 deployer Java 实现与 installer 二进制版本混用。

### 5.3 接口拆分

现有 `IAsDeployerCompat` 同时承载 IDEA 集成和 deployer transport。新增部署领域接口：

```text
IApplyChangesExecutor
├── createInstallSession
├── install
├── parseApks
├── dumpApks
├── getPackageName
├── createBaseOverlayId
├── buildOverlayId
├── createOverlayUpdate
├── optimisticSwap
└── exception mapping
```

实现：

```text
IdeaApplyChangesExecutor
  → 委托现有 AsDeployerCompat

StandaloneApplyChangesExecutor
  → 使用 :standalone_deployer 固定 Quail 实现
```

IDEA 专属能力继续保留在 `IAsDeployerCompat` 或拆出的 `IIdeaDeployEnvironment`：

- IDE 设备选择。
- IDE module info。
- IDEA Run Configuration。
- Debug attach。
- Android Studio DeploymentService 桥接。
- Android Studio 版本兼容分发。

### 5.4 部署编排下沉

以下能力需要在替换 IDEA 类型后下沉为共享 Runtime Engine：

- `JuggDeployTask`
- `JuggDeployer`
- `LaunchContext`
- `DeployStateRecover`
- `DeployRetryHandler`
- Direct Overlay transport 和 request builder
- deployment cache store/service

保留在 IDEA 的内容：

- IDEA 设备选择 UI。
- `Project`/console/prompt 关联。
- Android Studio Debug attach。
- AndroidTest SM Test Runner UI。

`JuggDeployerHelper` 拆成：

```text
JuggDeployOrchestrator        // main，共享状态机和 lifecycle
IdeaDeployEnvironment         // idea，关联 IDE prompt/UI/device
StandaloneDeployEnvironment   // cmd_line/standalone，关联 CLI log/device
```

### 5.5 HOT RELOAD 边界

首期必须跑通完整 Quail Apply Changes：

```text
compile incremental output
→ parse APK/deploy files
→ load deployment cache
→ build OverlayUpdate
→ OptimisticApkSwapper
→ installer/agent/app-server
→ update overlay id/cache/history
```

Direct Overlay 继续作为 recover/非 ready 旁路，不能替代在线 HOT RELOAD。

Quail `makeDebuggerRedefiners()` 当前为空映射，Standalone 不实现 IDEA debugger redefiner；普通 HOT RELOAD 使用 Quail 现代 deployer pipeline。

## 6. platform_compat 边界

保留 `platform_compat/base_api`，但不继续模拟完整 IDEA runtime。

首期允许：

- standalone 直接依赖真实 ddmlib，继续使用 `IDevice`。
- main 中保留必要 IntelliJ API 签名兼容桩。
- 使用 `PlatformApi` 接入 host 环境能力。

首期优先消除的依赖：

- MCP runtime 对 `Project.basePath` 的依赖，改为 `projectDir`。
- 部署核心对 IDEA `Project`、IDE prompt 和 IDE service container 的依赖。
- Run 编排对 `RunManager`、Swing、IDE console 的依赖。

禁止为了 standalone 给 mock `Project` 增加 RunManager、ServiceManager、VFS 等完整行为。

## 7. Standalone Run Configuration

### 7.1 配置来源与导入

`SuggestRunConfiguration` 已废弃，本方案不再复用。CLI Run Configuration 是项目级配置集合，模拟 IDEA 的 Jugg Run Configuration 行为；不提供 `.local` 用户覆盖层。

新版 IDEA 首次访问项目配置集合时，仅导入现有 Jugg Run Configuration，并以 IDE 当前选中的 Jugg 配置更新当前指针；普通 Android Run Configuration 不导入。之后 IDEA 选择的 Jugg 配置持续更新指针，standalone 始终按指针读取当前配置。

没有可导入配置时，Gradle project info 读取链生成一个确定性的默认配置。默认识别顺序：

1. 最近一次成功 Gradle build 实际使用的 module、variant、task 和 APK 输出。
2. Gradle project info 中名称为 `app` 的 application module。
3. 其他 application module 按稳定排序选择第一个。
4. variant 优先使用 project info 的当前 buildVariant。
5. 缺失时使用 `debug`。

生成结果必须记录推断来源，不能静默把 fallback 结果描述为 IDE 配置。

Gradle Sync 完成后，IDEA 先刷新 effective `JuggProjectInfo`，再为每个 application module reconcile 当前 `buildVariant`。同 module + variant 已存在时保留全部用户字段，只在缺失时确定性创建。当前选择是 Jugg 配置时切换到同 module 的 active variant；当前选择不是 Jugg 时不改变 IDEA 选择和 CLI current pointer。该流程不恢复 `SuggestRunConfiguration`，也不导入普通 Android Run Configuration。

### 7.2 JSON 配置集合

```text
build/jugg/config/
├── run_configurations/
│   └── <id>.json
└── current_run_configuration.json
```

`<id>` 是稳定 UUID；重命名配置不改变 id。`current_run_configuration.json` 只保存 `schemaVersion` 和 `configId`。每个配置独立写入，避免维护一份配置影响其他配置。首期不新增 Python CLI 的配置选择命令；用户可通过 IDEA 当前选择或维护指针文件切换配置。

建议配置字段：

```json
{
  "schemaVersion": 1,
  "id": "a8f3...",
  "name": "app debug",
  "generatedBy": "idea|standalone|gradle-project-info",
  "generatedAt": 0,
  "moduleName": "app",
  "variant": "debug",
  "buildTarget": "APP",
  "compileCommand": "./gradlew :app:assembleDebug",
  "outputApkName": "app/build/outputs/apk/debug/*.apk",
  "isRemoteCompile": false,
  "remoteSshPassword": "",
  "environmentVariables": ""
}
```

远端编译密码直接保存于该配置文件，以便与 IDEA 配置互通。密码、token、命令和环境变量不得写入日志或诊断报告；配置文件应尽量限制为当前用户可读写。配置位于 `build/jugg`，接受 `./gradlew clean` 后重新导入或生成。

### 7.3 写入时机

IDEA Runtime：

- 首次访问时导入全部现有 Jugg Run Configuration，并以当前选择更新指针。
- Gradle build 成功并确认 APK 输出后，更新实际生效配置，而不是开始前的候选值。
- IDEA 切换 Jugg Run Configuration 时更新指针。
- 普通增量编译成功不重写配置。

Standalone Runtime：

- `jugg init` 在没有当前配置时根据 Gradle project info 生成默认配置和指针。
- standalone Gradle build 成功后更新当前配置的实际生效字段。

CI Runtime：

- 默认不读写该配置，保持现有 CI 产物语义。
- 如未来需要复用，必须通过显式参数开启。

### 7.4 跨平台命令

配置保留逻辑 Gradle command/task；执行前按宿主系统解析 wrapper：

- macOS/Linux：`gradlew`
- Windows：`gradlew.bat`

禁止依赖 `/bin/bash -c` 执行配置命令。

## 8. Standalone Runtime

### 8.1 daemon

Standalone daemon 负责：

- MCP HTTP Server。
- 项目 registry。
- project runtime 生命周期。
- 编译/deploy job。
- 项目锁和固定全局写锁。
- 设备发现。
- runtime resource 解压和版本校验。
- 4 小时无外部有效活动后的自动退出。

Standalone daemon 使用 Java 11。正式发行提供独立 distribution，例如 `jugg-standalone-<version>-<os>-<arch>.zip`，包含 daemon launcher、应用及依赖 jar、Java 11 runtime image（含编译所需模块）、Java 11 standalone deployer、installer 资源和 metadata；避免依赖用户机器预装正确 JDK。macOS、Linux、Windows 分别构建对应 distribution。

Python CLI 的命令与 MCP 参数保持不变，但内部端口发现改为识别 IDEA MCP runtime 和 standalone runtime。目标项目未被任何 runtime 持有时，普通用户 CLI 拉起 standalone daemon，再走 MCP 初始化和调用。hook 子进程必须传递 `JUGG_CALLER=hook`：仅当 `<projectDir>/build/jugg/database/compile_context.db/complete_flag` 存在时才允许拉起 daemon；标记不存在时直接跳过，禁止因 hook 产生意外 daemon。

`version` 增加：

```text
runtimeType=idea|standalone
runtimeVersion
capabilities
```

当两种 runtime 同时存在时，CLI 根据 `projectDir` 查询已初始化项目；同一项目同时存在时，默认使用当前持有项目锁或最近成功运行的 runtime，并允许显式指定 runtime。

daemon 的 idle 定义为“4 小时没有外部有效活动”。任意 MCP 请求到达时刷新 idle timer；WatchService 事件、后台轮询和定时 update check 不刷新 timer。计时器到期时若仍有 compile/deploy/Gradle job、持有项目写锁或正在下载更新，不退出，而是延长 1 分钟后再次判断；条件解除后才停止 MCP server、dispose 全部 project runtime、释放资源并退出进程。

### 8.2 设备层

Standalone 使用真实 ddmlib 初始化 `AndroidDebugBridge`，实现：

- 设备发现和授权状态。
- serial 选择。
- shell、streaming shell、push、pull。
- pid、ABI、API level。
- install server 使用的 ADB client。

生产实现统一使用 `ProcessBuilder` / ddmlib API，不复用 test 中依赖 `/bin/bash` 的 `CmdAdb`。

### 8.3 Windows

设计阶段同时覆盖：

- `adb.exe`、Java 11 runtime、`gradlew.bat` 发现。
- Windows 路径、MSYS/Cygwin/WSL 归一化。
- NIO 文件锁。
- daemon launcher/pid 管理。
- Python/批处理 wrapper。
- long path 和临时目录。

Windows 独立验收，不以 macOS/Linux 通过代替。

## 9. 分会话实施步骤

每个 Step 独立会话实施、验证和提交。除明确说明外，不在同一会话同时推进相邻 Step。实际修改业务代码前，必须先按 `06_testing.md` 确定 L1/L2/L3 测试路径并写失败测试。

### 9.1 当前进度

| 阶段 | 状态 | 说明 |
|---|---|---|
| 方案设计 | 进行中 | 保留既有 docs commits，方案随实施反馈持续修订 |
| Step 0 | 已跳过 | 当前决策不单独实施 Java 11 deployer PoC，后续进入 deployer Step 时再完成对应可行性验证 |
| Step 1 | 已完成 | 已下沉部署状态边界；review 后删除预建的共享 Runtime 与 IDEA 转发 adapters，Runtime 聚合调整到具体领域能力下沉之后 |
| Step 2 | 已完成 | 任务域、项目/全局锁、IDEA task adapter、后台 Job 生命周期和项目级 deployment cache 已落地；review 改进已合入 |
| Step 3 | 已完成 | 项目模型、Compile Context 核心与本地 Gradle project info 调度已下沉；IDEA/Gradle-only source 已落地 |
| Step 4 | 已完成 | 文件变化处理与 Git reconcile 已下沉，共享 WatchService monitor 与 dependency policy 已落地 |
| Step 5 | 已完成 | 共享 Runtime Settings、IDEA 旧设置迁移、统一 custom config 生命周期与 custom compiler reload/dispose 已落地 |
| Step 6 | 已完成 | Server RuntimeInfo 与共享 JuggHotUpdateManager 已落地；诊断、运维门面和资源版本策略延后到真实 standalone 调用出现时 |
| Step 7 | 已完成 | 共享 CLI Run Configuration schema、确定性默认推断、配置集合/指针、IDEA 导入/选择监听和 Gradle 成功回写已落地 |
| Step 8 | 已完成 | Java 11 daemon/registry/MCP/status 骨架、last owner、idle 生命周期、Python 双 Runtime 发现与 hook 门禁已落地；编译部署仍待 Step 10～11 |
| Step 9–12 | 待实施 | 按本文顺序在独立会话中推进 |

### 9.2 Commit 规范

- 每个完成并通过验证的 Step squash 为单一两行 commit message。
- 第一行固定使用：`[feature][WIP] supports standalone cli`
- 第二行使用：`[feature] stepN <summary>`
- Step 1 使用：

  ```text
  [feature][WIP] supports standalone cli
  [feature] step1 establish project runtime
  ```

- Step 内部的 TDD、重构、文档同步和修正提交在阶段完成后统一 squash，不保留零散 `[refactor]`、`[docs]` 提交。

### Step 0：Java 11 Standalone Deployer 可行性门槛

实现状态：已跳过独立实施。Java 11 字节码、真实 HOT RELOAD 和二进制协议兼容性要求继续保留，并入 Step 9/10 的 deployer 实现与验收。

目标：在下沉 Runtime 前证明 Java 11 路线可完成真正 HOT RELOAD，避免后续架构改造建立在不可运行的 deployer 上。

任务：

- 以 `IApplyChangesExecutor` 的实际调用面建立 Quail deployer 的 class/dependency allowlist。
- 反编译并回迁该闭包，以 Java 11 源码重新编译；禁止按 package 或整 jar 无边界迁入。
- 打包最小 installer/agent/app-server 二进制和 metadata 校验。
- 在 Java 11 JVM 中完成 install、class HOT RELOAD 与 resource HOT RELOAD PoC。

验证性任务：

- Java 11 JVM 不加载任何 Quail Java 21 class。
- Java 11 PoC 可完成真实 base install、class HOT RELOAD、resource HOT RELOAD，且 App 进程与 Activity 不发生非预期重启。
- deployer Java 实现和 installer/agent/app-server 二进制不匹配时明确失败。

### Step 1：建立项目运行边界，校准 Runtime 聚合时机

实现状态：已完成。部署状态计算已下沉 `main`，并通过 `IHostDeployStateResolver` 隔离 IDEA 设备状态读取。初版曾建立抽象 `JuggProjectRuntime` 和五个 IDEA lifecycle/controller/binder adapters；review 后确认这些边界只有单一转发实现，已全部删除。当前配置刷新、历史恢复、Compile Context 关联、文件变化与 dispose 继续由 `JuggManager` 直接表达，待 Step 3-6 的具体领域能力下沉后，再以组合方式建立共享 Runtime。

当前保留的真实 Host 边界：

- `IHostDeployStateResolver`
- `IHostTaskExecutor`

已完成验证：

- `DeployStateManagerTest`（L1/L2）
- `TopLevelFlowTest#testInstallAndLaunch`（L3）
- `:idea:compileKotlin`

目标：先下沉已有真实平台差异，不为尚未下沉的领域逻辑预建 Runtime 接口，不接 standalone，不修改现有用户行为。

任务：

- 将 `DeployStateManager` 下沉 `main`，以 host deploy state resolver 隔离 IDEA 设备状态读取。
- 保留 `IJuggManagerCaller` 对外行为兼容。
- 配置、Compile Context、文件变化等逻辑在共享具体实现出现前继续由 `JuggManager` 协调。
- Runtime 聚合待项目模型、文件变化、配置和编译部署领域下沉后建立，并采用组合而非继承。

验证性任务：

- IDEA 项目初始化、历史恢复、Run、关闭行为与改造前一致。
- 现有 MCP project routing 不变。
- 现有 IDEA 顶层 Flow 定向回归。

### Step 2：任务域、项目锁和后台任务

实现状态：已完成。`TaskRunnerManager` 已下沉 `main`，通过 `IHostTaskExecutor` 隔离 IDEA `Task.Backgroundable` 执行 / `ProgressIndicator` 与 CLI 无 UI 执行；`FileExecutionLockManager` 使用进程内可重入锁和 NIO 文件锁串行项目写事务，并写入 owner metadata，诊断读取会清理异常退出遗留的 stale metadata。完整 `JuggRunningTask`、项目初始化/更新、文件变化处理和 deployment cache 读写已进入项目锁。后台与 Host task 通过互斥的 `isProjectWrite` / `isGlobalWrite` 直接选择项目锁或固定全局写锁；CLI 自动更新、skills 安装、hot update 下载写入、runtime resource 解压与 library Test APK build history 已补齐全局写协调。hot update Loader 保持无 TaskRunner 的只读 bootstrap，IDEA 与 standalone 共享新的 load manifest；embedded packaged jars 的同步内聚在 hot update 初始化并受全局锁保护。下载端原子发布快照并按 90 天保留期清理未引用 jar。TaskRunner 统一跟踪 Job，并在 `JuggManager.dispose()` 时取消尚未执行的任务。已进入写事务的任务允许完成，避免强制中断造成半提交状态。

原用于隔离 IDEA 模块的 `IBackgroundTaskRunner` / `CoroutineBackgroundTaskRunner` 已删除。`TaskRunnerManager` 下沉后成为唯一任务编排入口，IDEA 与现有 CLI 都直接持有完整实例；Host task 的实际提交由 `IHostTaskExecutor` 完成，IDEA 使用 `HostTaskExecutor`，CLI 使用无 UI executor，并通过 `runtimeType=standalone`、`runtimeVersion` 参与同一套项目/全局锁。命令结束时先 dispose TaskRunner、再取消 CoroutineScope。`TaskRunnerManager.dispatcher` 固定返回其实际 CoroutineScope dispatcher，供 ConstRef 等共享组件调度子任务。`ITaskEventReporter` 与 IDEA 的单行转发实现已删除，TaskRunner 直接通过注入的 `JuggServer` 上报完成事件。

`runTaskSafe` 已拆分 `isProjectWrite` 与 `isBlockIncrementalCompile`：只有获取项目锁后才按需设置 `isInitializingIncrementalCompile`，无项目锁却要求阻塞增量编译的组合会被直接拒绝。`ExecutionLockManager.kt` 不再提供公共声明，Runtime identity 由 TaskRunner 构造参数在内部创建；无 TaskRunner 生命周期但需要写全局文件的基础设施调用 TaskRunner 静态全局写入口，纯读取 bootstrap 不引入该依赖。`JuggManager` 与 `JuggDeploymentCacheStore` 都通过 `TaskRunnerManager` 使用项目锁。`JuggDeploymentService` 是项目级实例，保留 Runtime 本地 `memoryCache`，磁盘 cache 固定保存到 `<projectDir>/build/jugg/deploy_cache/.deploy_cache.db`，锁内同步刷新磁盘快照并使用临时文件原子替换。

已完成验证：

- `ProjectExecutionLockTest`（L2，双 JVM 项目锁竞争、正常/异常退出、重入与 metadata，以及固定全局锁跨项目/跨入口/跨 classloader 锁表串行）
- `JuggDeploymentCacheStoreTest`（L1/L2，原子替换、项目隔离、双 Runtime 串行写入）
- `TaskRunnerManagerTest`（L2，锁内 initializing、参数约束、实际 Scope dispatcher、项目/全局后台锁、Host task 直接上报 `JuggServer` 及 dispose）
- `JuggHotUpdateManagerTest`（L1，hot update 快照原子替换与 90 天 jar 清理）
- `JuggDeployerInstallTest#hot update bootstrap does not depend on task runner`（L2，Loader bootstrap 依赖边界）
- `JuggDeployerInstallTest#production code uses task runner instead of execution lock types`（L2，生产调用点不引用锁实现类型）
- `LibraryTestApkBuildHistoryTest`（L1，跨实例并发写不丢记录）
- `JuggDeployerInstallTest`、`JuggDeployerHelperDeployFlowTest`（L2）
- `TopLevelFlowTest#testInstallAndLaunch`（L3）
- `CmdLineTest`（CLI 使用统一 TaskRunner 完成增量构建）、`:idea:compileKotlin`

目标：让 IDEA 与后续 standalone 使用同一任务串行、锁和后台任务语义。

任务：

- 将 `TaskRunnerManager` 下沉到 `main`，建立 `ProjectExecutionLock` 和固定全局写锁。
- IDEA 以 adapter 提供 `Task.Backgroundable`、ProgressIndicator 和 EDT 表现。
- 项目写任务使用跨进程项目锁。
- `JuggDeploymentCacheStore` 改为项目级服务，使用项目锁和原子替换。
- 由 `TaskRunnerManager` 统一管理 warm-up、延迟复查、MCP fetch cleanup 等后台任务及其 Job 生命周期。
- 拆分项目写锁和增量编译阻塞语义，状态只在获取项目锁后变更。
- 保留项目 Runtime 本地 deployment `memoryCache`，Step 10 直接适配 Runtime 切换失效。
- 定义锁顺序、任务取消和 dispose 语义。

验证性任务：

- IDEA Task UI 和任务串行行为不变。
- 两个 JVM 进程竞争同一项目锁，最多一个进入写事务。
- owner 正常退出和异常退出后锁均可释放。
- 不同项目的 deployment cache 文件隔离；同一项目的 IDEA/standalone 写入由项目锁串行。

### Step 3：项目模型与 Compile Context 下沉

实现状态：已完成。新增共享 `IProjectModelSource`、`ProjectModelResult` 与 `GradleProjectModelSource`；`CompileContextManager` 已整体下沉 `main`，直接持有 source model，并应用 module custom classpath 得到 effective model。仅把 IDEA Module/VFS/JDK model 读取保留在 `IdeaProjectModelSource`。IDEA 继续以 IDE model 为 base 合并 Gradle/include-build 快照，Gradle-only source 不构造假的 IDE model，并按 `BuildTarget` 过滤 androidTest module。

当前没有依赖跨 Runtime project model identity 的生产消费者，因此不新增 fingerprint/generation 和对应状态文件；如后续 runtime cache 失效策略需要，再由具体消费者与恢复协议共同引入。`GradleProjectInfoLocalFetchManager` 已下沉 `main`，移除 IDEA `Project` 依赖，并继续通过共享 `TaskRunnerManager` 异步执行以保留项目锁、进度和任务上报语义。`ICompileEnvironmentSource` 让 Compile Context 在创建时读取 Android SDK、本地 Gradle fetch 在每次执行时读取 Gradle 环境，避免 Runtime 构造期缓存。`CopyGeneratedSourceHelper` 已位于 `main`，本 Step 保持其共享 TaskRunner 与 remote generated/custom sync 回写语义不变。

IDEA project info 更新后仍由 `JuggManager.rebindCompileContext()` 按以下顺序重新关联：

```text
DeployFileManager
→ JuggCompiler
→ FileChangesHandler
→ GitFileChangesDetector
→ CustomCompilerManager
```

已完成验证：

- `ProjectModelSourceTest`（L1/L2，Gradle-only model、androidTest target 过滤、`BaseCompileContext` 创建）
- `ProjectModelFlowTest`（L2，local fetch 经共享 TaskRunner 提交并保留项目任务边界）
- `JuggProjectInfoMergerAndroidTestTest`（L1，IDE/Gradle merge 行为）
- `CompileContextManagerBuildPathInfoTest`（L2，full-build path、custom sync path、merge rebind 边界与环境读取时机）
- `CompileEnvironmentSourceTest`（L1/L2，IDE Gradle 环境按调用实时读取）
- `CompileContextManagerAndroidTestFilterTest`（L1）
- `CopyGeneratedSourceHelperTest`（L1，remote generated/custom sync 路径映射）
- `TopLevelFlowTest#testInstallAndLaunch`（L3）
- `:idea:compileKotlin`

目标：让共享 Runtime 可以从 IDEA model 或纯 Gradle model 建立相同领域项目模型。

任务：

- 拆分 `CompileContextManager` 的领域核心与 IDEA model 读取逻辑。
- 建立 IDEA/Gradle-only `IProjectModelSource` 边界，由 `CompileContextManager` 管理 effective model。
- IDEA 使用 IDE model + Gradle model；standalone 模式支持 Gradle model only。
- 下沉 `GradleProjectInfoLocalFetchManager` 的 Gradle 调度主体。
- 下沉 `CopyGeneratedSourceHelper`。
- 明确 project info 刷新和重新关联顺序。

验证性任务：

- IDEA Sync 后 project info、classpath、module/APK 归属不变。
- Gradle model only 可建立有效 `BaseCompileContext`。
- project info 变化后各组件按既定顺序重新关联。
- remote generated source 回写行为不变。

### Step 4：文件变化与依赖变化领域下沉

实现状态：已完成。新增共享 `FileChangeManager`，统一 changed/delete 过滤、`DeployFileManager` 更新、build-file 依赖状态、Git reconcile 和 pending file-processing barrier。IDEA VFS 只通过 `IdeaFileChangeMonitor` 上报事件；共享 `WatchServiceFileChangeMonitor` 递归监听项目目录，支持 debounce、create/modify/delete、rename 的 delete+create 归一化，并在 overflow 时触发完整 Git reconcile。`.git`、`.gradle`、`.idea` 和 `build` 输出目录不进入 WatchService，避免构建产物产生无效事件风暴。

`GitFileChangesDetector` 已从 `idea` 下沉到 `main`。`DependencyChangeManagerByGradle` 与 `DependencyChangeManagerBySync` 不再调用 `PlatformApi.showChangeConfirmDialog()`；该 API 已从 `IPlatformApi` 删除。依赖确认复用已有 `CompileUiHandler`：IDEA 的 `JuggCompileUiHandler` 展示现有 dialog，CLI/standalone handler 接受命令或 runtime config 给出的确定结果；`IDependencyChangeManager` 只负责应用结果。

当前 compile-on-save 的设置读取和实际 compile 触发仍由 `JuggManager` 完成：共享 manager 返回 `FileChangeResult`，IDEA 根据现有 `JuggSettings.compileOnSave` 调用编译。待 Step 5 下沉 settings、Step 10 下沉 compile orchestrator 后，再将该最后触发点纳入共享 Runtime；文件变化落库和 pending barrier 已完全共享。

已完成验证：

- `FileChangeManagerTest`（L2，changed/delete/build-file/Git、pending barrier 与任务取消释放）
- `WatchServiceFileChangeMonitorTest`（L2，真实 create/rename/delete）
- `GitFileChangesDetectorTest`（L2，Git recover 与缺失 undeployed file）
- `DependencyChangeManagerByGradleTest`（L1/L2，manager 应用确认结果后的状态）
- `DeployStateManagerTest`、`FileChangesHandlerTest`、`GitChangesCompileCheckerTest`、`JuggCompileHelperTest`（L2）
- `TopLevelFlowTest#testInstallAndLaunch`（L3）
- `CmdLineTest`、`:idea:compileKotlin`、`:idea:compileTestKotlin`、`:cmd_line:compileTestKotlin`

目标：统一 IDEA VFS 和 standalone WatchService 的变化处理语义。

任务：

- 建立共享 `FileChangeManager`。
- 下沉 `GitFileChangesDetector`。
- IDEA 保留 `IdeaFileChangeMonitor`，将 VFS 事件交给共享 manager。
- 新增 `WatchServiceFileChangeMonitor`，处理 rename/delete/overflow/debounce。
- 保留 pending file-processing barrier。
- 将 dependency change 确认从 `PlatformApi` 收口到已有 `CompileUiHandler`。
- IDEA 和 CLI 分别通过对应 handler 提供确定结果，dependency manager 只应用结果。

验证性任务：

- VFS、Git checkout/pull、外部编辑、rename/delete 结果一致。
- compile 不会抢在 pending file event 落库前执行。
- WatchService overflow 后 Git reconcile 能恢复完整状态。
- build/dependency 变化在 IDEA 与 CLI 策略下得到确定结果。

### Step 5：运行设置、Custom Config 与扩展能力

实现状态：已完成。`JuggSettings` 保留原 `ide.bean` 包名，直接持有设置 schema、默认值与内存 effective fields，底层复用只处理原始 JSON 字段的 `JsonRuntimeSettingsRepository`；不再保留 `RuntimeSettings`、manager 或 repository interface。IDEA 由 `JuggManager.init()` 提交的 `Init Jugg` 后台任务调用 IDEA 模块提供的 `JuggSettings.migrateLegacyJuggSettings()` 扩展入口，读取旧 `jugg.*` 属性并只回填 `~/.jugg/settings.json` 缺失字段；已有 JSON 字段始终优先。迁移成功后在 `PropertiesComponent` 记录完成标记；失败不阻断启动、不清理旧属性，本次使用现有 JSON/default，下次启动继续重试。IDEA 与 standalone 不再显式调用 load，首次 persisted setting get/set 自动读取 JSON；文件缺失时使用内存默认值且不创建文件。JSON 写入统一使用全局写锁、临时文件和原子替换，同进程 setter 全程串行。CLI 强制 backup classpath 使用进程级 override，不修改共享用户设置。`JuggGlobalPathManager.rootDir` 可切换，settings 缓存会自动跟随 root，测试任务使用独立 root 避免污染真实用户配置。

新增共享 `ProjectCustomConfigManager`，私有持有 `ProjectCustomConfigStore`，统一负责 refresh、server default 更新，并应用 server rules、build file rules、ignored rules、module custom classpath、custom compiler 与 embedded APK。项目级 `custom_config.json` 优先于 server 写入的 `default_custom_config.json`，本地配置删除后回退到 default；应用失败会失效缓存并在下次 refresh 重试。`JuggManager` 不再保留 refresh wrapper，运行期配置应用统一进入项目写锁。`CustomCompilerManager` 实现 `AutoCloseable`，不再接收外部 `Disposable`；仅为 `ICompilerCreator` SPI 在内部持有最小 `Disposable` compatibility scope。配置或显式 jar 列表变化时释放旧 compiler scope、清理缓存并关闭旧 `URLClassLoader`，IDEA/CLI 生命周期结束时统一调用 `close()`。

`JuggServer` 构造函数只创建对象，不读取 settings、不启动后台任务；IDEA 在 legacy migration 后、CLI 在 Runtime 初始化时显式调用幂等 `initialize()`，首次 settings 访问由该流程自动触发。`JuggGlobalPathManager.settingsFile` 保留，继续集中记录 Jugg 全局文件位置。

已完成验证：

- `JsonRuntimeSettingsRepositoryTest`（L1，缺失文件、按字段合并、迁移失败重试和跨 Runtime 更新）
- `JuggSettingsTest`（L1，自动加载、root 切换、进程级 override 与 migration cache 刷新）
- `IdeaRuntimeSettingsMigrationTest`（L2，只转换显式保存的旧 IDEA properties）
- `ProjectCustomConfigurationFlowTest`（L2，custom config 优先级、失败重试、default 即时应用和真实 ServiceLoader compiler 切换释放）
- `TopLevelFlowTest#testInstallAndLaunch`（L3）
- `CmdLineTest#buildIncrementalApkWithCustomCompilers`（CLI custom compiler 回归）
- `:idea:compileKotlin`、`:cmd_line:compileTestKotlin`

目标：让两个 Runtime 使用一致的 effective settings、文件规则、classpath 和 custom compiler。

任务：

- 设置 schema、默认值与 effective fields 只保留在 `JuggSettings`，JSON repository 不感知领域模型。
- 新版 IDEA 首次启动时从 `PropertiesComponent` 回填 `~/.jugg/settings.json` 缺失字段。
- standalone 只读取已存在的 `settings.json`；文件缺失时使用默认值且不创建文件。
- 将 `JuggSettings` 调整为领域设置 facade，逐步移除直接全局存取。
- 先形成可由 IDEA 与 standalone 直接复用的配置生命周期，再纳入后续 Runtime 聚合。
- 对齐 server custom config、build file rules、ignored rules、module custom classpath、embedded APK。
- 明确双 Runtime 的配置更新和冲突规则。

验证性任务：

- IDEA 设置读取和 UI 修改行为不变。
- IDEA 写入后 standalone 可读取相同 effective settings。
- custom compiler、embedded APK 和 custom classpath 行为一致。
- JSON 迁移不覆盖已有设置；IDEA 与 standalone 对有效 settings 的读取一致。

### Step 6：Server 和 Hot Update 下沉

实现状态：已完成。新增 `RuntimeInfo`，仅包含 `runtimeType/runtimeVersion/hostVersion/buildTime`，由 IDEA、CI、standalone 各自的 Host 边界单点构造并通过 `IPlatformApi.getRuntimeInfo()` 复用；`JuggServer` 不再读取 `Project`、`PluginInfoReader` 或 `PlatformApi`，事件上报继续保留后端兼容的 `version/ide_version` 字段，`runtimeType` 仅用于 Runtime 锁 owner identity。custom server 输入已从 `JuggServerChooser` 的 Host dialog 中移除，IDEA 获取输入后通过后台全局写任务调用共享 `JuggServer` 写入 settings。

hot update 的下载、MD5 校验、原子 jar/metadata/load manifest 发布、embedded jar 同步和过期清理统一下沉到 `JuggHotUpdateManager`。IDEA `IdeaHotUpdateCoordinator` 保留定时检查、频控、通知、插件安装/重启和 reopen project；standalone 后续通过 `JuggServer` 检查更新后复用同一 manager，并在下一次 daemon 启动读取共享 manifest。`isNeedReinstall=true` 只记录已校验 jar 和 update metadata，不替换 active load manifest。

Loader 创建 hot-update classloader 前只通过 `JuggHotUpdateBootstrap` 读取 manifest、embedded build time 和 jar 路径。该 bootstrap 无锁、只读、不依赖 `TaskRunnerManager`，避免把 hot-update runtime 类型穿过 parent/child classloader 边界。

本 Step 不新增 diagnostics/maintenance control plane，不修改 TaskRunner 的 job/task 观测模型；IDEA report、CLI/skills update、MCP fetch cleanup 和 clean/reset 继续由既有调用链承担。runtime/deployer 内容版本资源推迟到 Step 9，在真实 deployer binary 布局明确后以 `JuggResourceManager` 落地。

已完成验证：

- `RuntimeInfoFlowTest`（L2，Server runtime info 与 custom server Host 边界）
- `JuggHotUpdateManagerTest`（L1/L2，下载校验、embedded 发布、清理和 compatible/reinstall manifest 边界）
- `JuggHotUpdateBootstrapTest`（L1，Loader manifest 只读与 embedded build 匹配）
- `TaskRunnerManagerTest`、`ProjectCustomConfigurationFlowTest`（L2）
- `JuggServerTest`、`JuggDeployerInstallTest`、`JuggCliAutoUpdaterTest`（L1/L2）
- `CmdLineTest`、`TopLevelFlowTest#testInstallAndLaunch`、`:idea:compileKotlin`

目标：解除 Server 与 hot update 对 IDEA/plugin runtime 的依赖，不预建尚无 standalone 调用方的 control plane。

任务：

- `JuggServer` 使用 Host 注入的 `RuntimeInfo`，不依赖 plugin metadata。
- 下沉可共享的 update check、下载、校验逻辑；IDEA plugin install/restart 保留在 IDEA。
- standalone 复用 `~/.jugg/hot_update` 下载与 metadata，更新只在下一次 daemon 启动加载；`isNeedReinstall=true` 不写入 standalone load list。
- Loader bootstrap 保持无锁只读，不依赖 runtime 任务域。
- 保持 IDEA report、TaskRunner job 状态和现有运维行为不变。
- runtime/deployer resource 版本策略推迟到 Step 9。

验证性任务：

- IDEA update/report 行为不变。
- IDEA、CI 和后续 standalone 的 runtime info 均由 Host 显式提供。
- 双 Runtime 不会并发写坏 hot update jar、metadata 或 load manifest。
- server 下发 custom config 后两个 Runtime 行为一致。

### Step 7：CLI Run Configuration

实现状态：已完成。新增共享 `CliRunConfiguration`、`CliRunConfigurationGenerator`、`CliRunConfigurationSerializer` 与 `CliRunConfigurationStore`，配置分别保存到 `build/jugg/config/run_configurations/<id>.json`，当前指针保存到 `current_run_configuration.json`。配置文件使用临时文件和原子替换，POSIX 平台限制为当前用户读写；配置 id 为 UUID，Gradle project info 默认配置使用 module path + variant 生成确定性 UUID，IDEA 配置将 id 持久化到 `JuggRunConfigurationOptions`，重命名不改变 id。

默认配置不再使用 `SuggestRunConfiguration`。生成顺序为最近成功配置、名为 `app` 的 application module、其余 application module 稳定排序；variant 优先当前 `buildVariant`，缺失时使用 `debug`。`debug/release` flavor variant 的 APK 路径按 `<flavor>/<buildType>` 生成。IDEA 首次发现未绑定的 Jugg Run Configuration 时导入全部 Jugg 配置，普通 Android Run Configuration 不导入；选择、增加或修改 Jugg 配置时在项目锁内更新配置或指针，并忽略已经过期的异步选择事件。IDEA Runtime 的 MCP/CLI Gradle 调用优先当前选中的 Jugg 配置，不再固定执行列表第一个配置。

Active Build Variant 同步已改为基于 effective `JuggProjectInfo` 的 reconcile：Sync 更新模型后，为每个 application module 生成当前 variant 候选，复用同 module + variant 的已有配置并保留自定义字段，只补齐缺失配置；仅在当前选择为 Jugg 时切换同 module variant。文件扫描使用 Runtime 实例内锁串行，不占用 project lock，因此不会阻塞该 Sync 写事务。

Gradle build 成功且 APK 已确认后，当前配置会回写本轮实际 `compileCommand`、APK pattern、module、variant、build target 与远端编译字段。远端密码保留在配置文件中，但配置 `toString()` 与 `JuggGradleCompileOptions.toSafeString()` 不输出密码、Gradle command 或环境变量。

本 Step 已提供 standalone 可直接复用的 generator/store/toCompileOptions 边界；实际 `jugg init` 与 standalone Gradle build 调用点随 Step 8/11 的 Runtime/编译链建立后接入，不重复实现配置协议。

已完成验证：

- `CliRunConfigurationTest`（L1，单 app、非 `app`/多 application、custom variant、最近成功配置优先、稳定 id、配置集合/指针 JSON 与敏感字段日志收口）
- `IdeaCliRunConfigurationFlowTest`（L2，IDEA 导入、重命名 id、当前指针、过期选择事件、Gradle 成功回写）
- `JuggConfigurationRunnerTest#selected jugg configuration has priority for cli invocation`（L2）
- `CmdLineTest`（CI 行为回归）
- `TopLevelFlowTest#testInstallAndLaunch`（L3）
- `:idea:compileKotlin`

目标：CLI 无需 IDEA Run Configuration 即可获得稳定 build profile。

任务：

- 定义 `CliRunConfiguration` 和 schema serializer。
- Gradle project info 读取链增加默认配置识别。
- 定义配置集合、稳定配置 id 与当前指针 JSON serializer。
- IDEA 首次访问时仅导入 Jugg Run Configuration，并以 IDE 当前选择更新指针。
- IDE Gradle build success 与 standalone init/Gradle build 更新当前配置。
- 不支持 `.local` 覆盖层；远端密码直接持久化于项目配置，且不得进入日志或报告。

验证性任务：

- 单 app、非 `app` module、多 application module、custom variant 场景生成结果稳定。
- IDE Gradle build 后 CLI 可直接读取并执行当前配置的同一 task/APK output。
- IDE 切换配置或手工维护指针后，standalone 使用正确的当前配置。

### Step 8：Standalone 模块和进程骨架

实现状态：已完成。`:cmd_line` 保持现有 CI 入口、包名、一次性命令与 `Main-Class` 不变，在 `cmdline.standalone` 下新增 `JuggDaemon`、`StandaloneProjectRegistry`、`StandaloneProjectRuntime`、`StandaloneJuggRuntimeAssembler`、`StandalonePlatformApi`、idle timer 与 activity state；distribution 额外生成 Java 11 `jugg-standalone` / `.bat` launcher。standalone 按 `--project-dir` 初始化项目，并通过共享 `McpLocalServer`、`McpBaseInvoker`、`McpToolInvoker` 提供 `version`、`list-projects`、`status` 骨架。

`IMcpRuntime` 使用非空 host-neutral `projectDir`，移除 `Project`，且所有成员均无默认实现；IDEA 与 standalone Runtime 必须明确提供或声明缺失的能力，MCP action 不再读取 `Project.basePath`。`RuntimeInfo` 只描述 Host 身份，不携带 MCP capability；`version` 从进程级 `McpToolRegistry` 返回 `runtimeType/runtimeVersion/capabilities`。Step 8 的 standalone registry 只启用 `version/list-projects/status`，并以同一实例限制 `tools/list` 和 action 分发，不广告未串联的 compile/deploy 能力。

standalone registry 使用规范化路径作为查找 key，同时保留 canonical `File` 供项目文件读取，避免大小写归一化影响 complete flag 等真实文件访问。

项目锁的瞬时 owner metadata 继续写入 `runtime.lock.owner.json` 并在释放时删除；新增 `runtime.owner.json` 原子保存上次 IDEA/standalone owner，`TaskRunnerManager` 在取得项目写锁时生成 owner-change event，CI Runtime 不参与该归属。损坏的 last owner 按无历史 owner 处理，记录原因后由当前 Runtime 原子覆盖。Python CLI 扫描全部 MCP 端口并读取 Runtime/project 信息，同项目双 Runtime 时仅在验证项目锁确实被持有后优先 current owner，否则选择 last owner；`--runtime idea|standalone` 可显式覆盖。已知项目列表不匹配的 legacy Runtime 不阻止 standalone 拉起，仅在项目列表不可读取时保留兼容 fallback。无 owner 时通过项目级 `runtime.launch.lock` 串行化自动拉起，并在锁内二次发现 Runtime；仍未发现时才通过 `JUGG_STANDALONE_LAUNCHER` 或默认安装路径启动 daemon。Hook 调用由 `hook_common.py` 显式传递 `JUGG_CALLER=hook`，无 complete flag 时以成功状态直接跳过。

daemon idle deadline 为 4 小时，任意 MCP HTTP 请求到达时刷新；job、项目写事务和 update download 使用独立 activity counter 延期退出，并按 1 分钟周期复查。WatchService 和后台轮询尚未接入 daemon，且不会被误计为外部活动。

已完成验证：

- `RuntimeOwnerSwitchTest`（L2，last owner 持久化、损坏 metadata 自愈与 IDEA → standalone change event）
- `StandaloneRuntimeTest`（L2，无 IDEA 的 version/list-projects/status 与 canonical project registry）
- `DaemonIdleTimerTest`（L2，外部活动刷新和 job/project-write/update-download 延期）
- Python `test_jugglib.py`（L2，同项目双 Runtime current/last/显式选择、legacy fallback、并发自动拉起去重、hook complete flag 门禁）
- Python `test_cmd_version.py`（L2，Runtime 类型、版本和 capability 输出）
- hooks `test_hooks_guard.py`（L2，hook 子进程传递 `JUGG_CALLER=hook`）
- `VersionMcpToolActionTest`、`GetStatusMcpToolActionTest`、`McpInvokerValidationTest`、`McpInvokerToolSuccessTest`
- `TaskRunnerManagerTest`、`ProjectExecutionLockTest`、`McpLocalServerTest`
- `CmdLineTest`、`TopLevelFlowTest#testInstallAndLaunch`、`:idea:compileKotlin`
- `:cmd_line:installDist`，并使用 Java 11 实际启动 `jugg-standalone` 与 IDEA Runtime 共存，验证 CLI 选择 standalone 端口及 `version/status/list-projects`

目标：建立 standalone daemon，但暂不完成部署。

任务：

- 保持单一 `:cmd_line` 模块和现有 CI 包名/任务/产物；新增 `cmdline.standalone` 包，全部使用 Java 11。
- 新增 daemon、project registry 和 `StandaloneJuggRuntimeAssembler`。
- 建立持久化 Runtime owner identity 与 owner change 事件，区分当前持锁 metadata 和上次 Runtime owner。
- 实现 standalone MCP runtime。
- `IMcpRuntime` 使用非空 `projectDir` 且不提供默认实现，MCP action 不再读取 `Project.basePath`。
- Python 内部发现策略支持普通 CLI 自动拉起 standalone；hook 仅在 complete flag 存在时拉起。
- 实现 4 小时外部有效活动 idle timer，以及 job/项目锁/更新下载期间按 1 分钟延期复查的退出语义。
- 保持 CI 命令和分发兼容。

验证性任务：

- Python CLI 可发现 standalone runtime。
- `version`、`status`、`list-projects` 正常。
- IDEA runtime 和 standalone runtime 可同时启动。
- hook 在无 complete flag 时不拉起 daemon，有 complete flag 时可拉起并读取状态。

### Step 9：反编译 Quail Standalone Deployer

目标：一次完成最小、可发行的 standalone deployer 实现，不依赖完整 Android Studio jar。

任务：

- 新增 `:standalone_deployer`。
- 从 Android Studio Quail 1 梳理并反编译 deployer 调用闭包，以 Java 11 源码重新编译。
- 引入最小 protobuf、ddmlib、utility 依赖。
- 打包 installer/agent/app-server 二进制。
- 生成 metadata 和 SHA-256 校验。
- 实现 `StandaloneApplyChangesExecutor`。

验证性任务：

- 纯 JVM 创建 install session。
- base install 成功并建立 deployment cache。
- 直接调用 `OptimisticApkSwapper` 完成 class HOT RELOAD。
- 资源 HOT RELOAD 生效。
- App 进程和 Activity 不发生非预期重启。
- installer/Java 实现版本不匹配时启动失败并给出明确错误。
- Java 11 JVM 不会加载任何 Quail Java 21 class。

### Step 10：部署编排下沉

目标：IDEA 与 standalone 复用同一 deploy lifecycle。

任务：

- 引入 `IApplyChangesExecutor`。
- 下沉 `JuggDeployTask`、`JuggDeployer`、LaunchContext 核心。
- 下沉 recover、retry、Direct Overlay lifecycle。
- 建立 `JuggDeployOrchestrator` 和两个部署运行环境。
- IDEA 部署环境关联现有 `AsDeployerCompat`。
- standalone 部署环境关联 Quail 实现。
- 适配现有 `JuggDeploymentService.memoryCache`；Runtime owner、runtime version 或 deployment cache generation 变化时清理并从项目级磁盘 cache 恢复。

验证性任务：

- IDEA 现有 deploy Flow 全部保持通过。
- Standalone install、HOT RELOAD、HOT FIX、recover、retry 通过。
- 同一项目在 IDEA/standalone 间切换后可恢复。

### Step 11：编译与部署完整串联

目标：实现完整 CLI 用户链路。

```text
jugg init
→ jugg gradle-build
→ 修改源码
→ jugg compile
→ jugg deploy
→ jugg status
```

任务：

- Standalone project context 恢复。
- Git changed files 自动刷新。
- 增量编译、Gradle fallback、deploy 串联。
- compile job、取消、轮询和日志。
- Runtime 切换 generation 检查。

### Step 12：三平台发行和验收

任务：

- macOS distribution。
- Linux distribution。
- Windows distribution。
- 三个平台分别携带 Java 11 runtime image。
- Python CLI auto-start/stop daemon。
- runtime update 和版本兼容。
- installer 资源完整性校验。

## 10. 测试策略

实现必须按 TDD 顺序推进。

### L1

- Runtime settings JSON 迁移、CLI Run Configuration 推断、配置集合与指针序列化。
- Gradle-only project model 加载与 module 过滤。
- File change 归一化和 dependency decision policy。
- lock metadata 和 lock key。
- 项目级 deployment cache 原子读写。
- Quail deployer 数据模型和协议解析中的确定性逻辑。

### L2

- `JuggProjectRuntime` 初始化、恢复、重新关联和销毁协作。
- IDEA project model 与 Gradle-only model 的等价契约。
- VFS/WatchService/Git reconcile 协作。
- settings/custom config/custom compiler 生命周期。
- 两个进程竞争项目锁。
- 同一项目的 deployment cache 串行读写与不同项目 cache 隔离。
- Standalone runtime、project registry、MCP job 协作。
- hook complete flag 自动拉起策略与 daemon idle 退出/延期复查。
- IDEA/standalone Apply Changes executor 契约一致性。
- 不增加设备/package 锁时的并发部署收敛验证。

### L3

新增 standalone Flow，使用真实 demo 编译和真实 emulator/device：

- Gradle baseline → 修改方法 → HOT RELOAD，进程不重启且行为变化。
- 修改资源 → HOT RELOAD，Activity 不重启且 UI 生效。
- IDEA deploy 后切 standalone deploy。
- standalone deploy 后切 IDEA deploy。
- overlay mismatch → recover → redeploy。
- Windows 独立完整 Flow。

涉及 IDEA deploy 编排下沉时，必须定向回归已有 `TopLevelFlowTest` 或等价 L3 Flow，不能只依赖 standalone 测试。

CI 与 standalone 同模块改造必须定向回归现有 `CmdLineTest`，证明 CI 参数、包名和产物语义不变。

## 11. 首期不做

- AS 插件改为 daemon 薄客户端。
- IDEA Debug attach 迁移到 standalone。
- 用 mock `Project` 模拟完整 IntelliJ service container。
- 支持多版本 standalone deployer 实现。
- 用 Direct Overlay 替代完整 HOT RELOAD。
- 自动展示和选择多个 Run Configuration 候选。

## 12. 完成标准

首期完成必须同时满足：

- 不启动 IDEA 时，Python `jugg deploy` 可完成真实 HOT RELOAD。
- IDEA 和 standalone 使用共享 deploy 编排，不存在两套 recover/retry 实现。
- `JuggManager` 只保留 IDEA 环境组装、事件转换和表现层关联，不再持有完整运行域业务编排。
- IDEA 与 standalone 使用同一个 `JuggProjectRuntime` 生命周期和领域服务。
- IDEA 和 standalone 不会并发写坏同一项目状态。
- deployment cache 以项目目录隔离，同一项目由项目锁保护。
- IDEA/standalone Runtime 切换后状态可恢复。
- CI `cmd_line` 行为兼容。
- macOS、Linux、Windows 均通过独立验收。
- standalone 发行物不依赖完整 Android Studio runtime jar。
- standalone daemon 与 standalone deployer 均运行在 Java 11。
- hook 在没有 complete flag 时不会拉起 daemon；daemon 连续 4 小时无外部有效活动后可安全退出。
- deployer Java 实现和 installer/agent/app-server 二进制有明确版本和完整性校验。
