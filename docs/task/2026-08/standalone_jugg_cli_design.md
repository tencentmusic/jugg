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
- 正式发行使用单一跨平台 Bundle，不携带 jlink runtime image；依赖用户提供完整 JDK，最低 Java 11，并验收 Java 11、17、21。

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
:deploy_compat:standalone_deployer
```

该模块放在 `deploy_compat/standalone_deployer/`，只由 standalone runtime 依赖，不进入 IDEA 插件 classloader。模块固定使用 Android Studio Quail 版本的 deployer 实现和二进制协议，不承担 Android Studio 多版本兼容。

Quail 的现成 deployer class 为 Java 21 字节码，不能被 Java 11 daemon 直接加载。`:deploy_compat:standalone_deployer` 与 `:cmd_line` 均固定以 Java 11 编译和运行：standalone 通过受控反编译回迁实现实际所需 deployer 闭包，而不是加载 Quail 的现成 class。

### 2.4 共享 JAR 内容池，Runtime manifest 独立

IDEA 与 standalone 共享 `~/.jugg/hot_update/jars/` 内容存储，但不共享 classpath manifest：

```text
~/.jugg/hot_update/
├── jars/
├── load_manifest.json
└── standalone_load_manifest.json
```

`load_manifest.json` 只描述 IDEA Runtime 当前可加载的完整 JAR 快照；`standalone_load_manifest.json` 只描述 standalone Runtime 当前可加载的完整 JAR 快照。公共 JAR 可同时被两份 manifest 引用，IDEA 专属 compat JAR 只进入 IDEA manifest，`cmd_line`、`base_api`、真实 ddmlib 和 `standalone_deployer` 等 standalone 专属 JAR 只进入 standalone manifest。共享物理目录不表示共享 classpath，禁止把两个 manifest 的并集放入 IDEA 插件 `jugg/lib/`。

根构建一次生成全局唯一、可排序的 `releaseBuildId`，同一构建中的插件 metadata、Bundle manifest、两份 Runtime manifest、服务端 update metadata 和 reinstall candidate 必须使用同一个值。`targetVersion` 只用于展示和产品版本判断，不作为同版本不同构建的激活身份。standalone manifest 还记录 `releaseChannel`、`schemaVersion`、`runtimeApiVersion`、`bootstrapApiVersion`、`toolingReleaseBuildId`、`managedBy=idea|external` 和有序 `jarFileNames`。完整 Bundle 安装时 `toolingReleaseBuildId=releaseBuildId`；普通兼容热更新只切换 runtime `releaseBuildId`，继续引用当前版本化 bootstrap/CLI，不得提高 tooling API。

所有进入共享池的来源统一使用内容寻址或等价的不可变 unique name，包括 IDEA embedded lib、standalone Bundle 和服务端两组文件。更新只新增或复用摘要一致的已校验文件，不覆盖运行中版本；同一 unique name 对应不同摘要时按协议错误失败。manifest 使用临时文件和原子替换发布，Loader 只读取自己所属的 manifest。未引用 JAR 清理使用 IDEA/standalone 两份 active manifest、待安装更新文件和既有保留策略的引用并集。

全局 standalone 采用确定的接管规则。自动同步只在同一 `releaseChannel` 内先比较规范化产品版本，再在相同产品版本内比较 `releaseBuildId`；不同 channel、无法可靠比较的 snapshot 或更旧产品版本都不得自动接管。外部 Bundle 安装可显式选择升级或降级；插件内“Install CLI”默认只安装/修复不低于当前 active 的版本，若 bundled 版本更旧必须明确确认降级。`isNeedReinstall=true` 仅可激活与新插件 `releaseBuildId` 完全一致的候选。插件自身回退不自动回退 standalone，若需要配套降级，由用户显式确认安装回退插件携带的 Bundle。这样旧分支晚构建、多 Android Studio 安装和外部安装共用 `~/.jugg` 时都不会因普通启动反复切换版本。

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

`JuggCliAutoUpdater` 等全局资源 owner 必须在内部使用统一全局写锁提交文件变更；`McpFetchCleaner` 只清理项目级目录，继续作为普通后台任务执行。

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
    └── AsDeployerCompat（通过 IApplyChangesExecutor 接入共享部署编排）
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

`TaskRunnerManager` 统一管理普通任务和项目写事务，并跟踪 Job 以便 Runtime dispose 时取消尚未执行的任务。当前任务包括：

后台任务只保留 `runBackgroundSafe()`：`isProjectWrite=false` 时不获取项目锁；`RuntimeTaskCoordinator` 自动捕获当前逻辑 owner，并传播给 `runTaskSafe()`、`runBackgroundSafe()` 和 `runAsyncSafe()` 提交的跨线程子任务。独立项目事务继续串行，同 owner 子任务共享重入；业务调用不需要判断顶层任务或事务内子任务。全局共享文件不再由 TaskRunner 包围整段业务 action，而由具体资源 owner 在最小提交段内部取得统一 Global Resource Lock。

- compiler warm-up。
- project info 延迟复查。
- deployment cache 预加载。
- MCP fetch 清理。
- CLI/skills 更新。
- server update/custom config 检查。
- standalone runtime/deployer resource 校验。

双 Runtime 下，CLI/skills 更新、standalone Bundle 安装、全局 runtime resource 解压、settings 写入、library Test APK build history 和 hot update 写入统一使用 `~/.jugg/locks/global.lock`。Windows CLI 安装在 G 内完成 `~/.jugg/bin` 文件发布后立即释放锁，再以 5 秒硬超时调用 `reg.exe` 更新用户 PATH；进程输出先重定向到临时文件，禁止在读取未关闭的 stdout 时无限等待。IDEA 与 standalone 共享 `hot_update/jars/` 内容池，但分别使用 `load_manifest.json` 与 `standalone_load_manifest.json`，不兼容旧 `load_list.txt`。两份 manifest 均描述所属 Runtime 的完整、有序 JAR 快照，不通过共享目录扫描或 JAR 前缀推导 active classpath。

启动 Loader 不进入任务域或全局锁，只读取所属 manifest、校验版本边界、确认全部 JAR 存在并刷新所用 JAR 的修改时间，不删除任何文件。IDEA manifest 继续校验 embedded build identity，避免旧插件加载不匹配的 hot update；standalone manifest 使用 `releaseBuildId`、`schemaVersion`、`runtimeApiVersion`、`bootstrapApiVersion` 和 `jarFileNames` 校验独立 Runtime。standalone 当前进程不热替换 classpath，只在下一次 daemon 启动时读取新 manifest。

服务器热更新协议保留现有 `jarFileInfos` 的 IDEA 语义，新增 nullable `standaloneJarFileInfos` 和 reinstall 时使用的 nullable `standaloneBundleFileInfo`。Gson 边界必须对缺字段按 `null` 读取并统一 `orEmpty()`，不能依赖 Kotlin 默认参数；旧插件忽略新增字段且不得收到 IDEA/standalone 并集。Bundle 和服务端文件共用同一文件名安全契约：只允许单个 basename，禁止绝对路径、`..`、任意平台路径分隔符、控制字符和 symlink target，normalize 后的目标必须仍位于该类型声明的 `hot_update/jars/` 或 `hot_update/candidates/<releaseBuildId>/` 根目录。

下载端根据服务端明确给出的文件集合分别生成本地 manifest，不根据文件名前缀分类。Hot update 先在短 Global Resource Lock 内快照可复用缓存和当前 metadata，随后在锁外下载、校验到唯一 staging 目录，最后重新取得短锁；只有 metadata 基线未被其他 Runtime 改写时，才原子发布 JAR、manifest、candidate 和 metadata，已被更新提交抢先替换的慢请求直接放弃提交。普通更新在全部 JAR 校验通过后分别原子发布两份 manifest；两份文件不追求跨文件原子切换，因此 `isNeedReinstall=false` 必须表示新旧 IDEA/standalone Runtime 可长期并存，并与当前及上一代持久化 schema、锁和 owner 恢复协议双向兼容，不满足时服务端必须下发 reinstall。运行中的旧 IDEA classloader 与下一次启动加载新版 manifest 的 standalone 共同访问项目时，也必须满足这一兼容契约。

`isNeedReinstall=true` 时只下载和记录两组候选文件、Bundle artifact 及其 `releaseBuildId`，不发布任何 active manifest。Bundle 固定保存到 `hot_update/candidates/<releaseBuildId>/standalone.zip`，candidate metadata 与已调度的插件安装标记是该目录的唯一 owner；新候选替代旧候选、安装成功或确认失败后，在不再被安装任务引用时删除整个 candidate 目录，下载失败的临时文件立即删除。共享池中的候选 JAR 继续按两份 manifest、candidate metadata 和既有 90 天策略联合清理。

新插件真正安装并重启后，只有插件 metadata 与 candidate 的 `releaseBuildId` 完全一致才激活配套 standalone manifest；仅 `targetVersion` 相等、安装被其他来源覆盖或同版本不同构建均不得激活候选。当前 Step 11 updater 不认识 Bundle artifact，也只能生成 `jugg/lib/`，因此“当前版本 → 首个 Step 12 版本”禁止使用 legacy hot-update reinstall，必须通过 Marketplace 或官方完整插件 ZIP 安装；首个 Step 12 插件生效后才启用新协议。服务端需对旧 runtime 返回完整插件升级提示而不是下发无法正确安装的 reinstall candidate。

插件发行包中的 Bundle 固定放在 `jugg/standalone/jugg-standalone-<releaseBuildId>.zip`，不作为 IDEA classpath element。hot-update 重装 ZIP 必须同时包含 IDEA manifest 引用的 `jugg/lib/*.jar` 和已校验的 Bundle artifact；Bundle 内部 JAR 不得直接展开到 `jugg/lib/`。普通无需重装的更新不改写已安装插件资源，只在 standalone 已安装时更新其 active manifest。

IDEA packaged runtime 初始化仍只在 `hot_update` 目录已经存在时发布 IDEA embedded JAR。若 `standalone_load_manifest.json` 已存在，表示 standalone 已安装，插件自动更新按 `releaseBuildId` 接管规则同步配套 standalone 闭包；未安装 standalone 时不主动展开大体积 runtime Bundle。未引用且超过 90 天的 JAR 由下载端在写锁内清理，引用集合包含 IDEA/standalone 两份 active manifest，以及已下载但因 reinstall 尚未激活的候选文件。

项目锁能力统一从 `TaskRunnerManager` 暴露。Global Resource Lock 由 settings repository、hot update manager、CLI/skills installer、runtime resource manager 和 history store 等资源 owner 内部使用，不允许 IDEA 业务层或 TaskRunner 持锁执行任意 callback。G action 必须同步完成且只执行有界本地资源读改写，不得启动异步任务、等待外部资源、调用业务 callback 或申请业务 monitor；当前线程持有 G 时再尝试阻塞式或 try Project Runtime Lock 会立即失败。hot update Loader 是无锁只读 bootstrap，不依赖 TaskRunner。`ExecutionLockManager.kt` 中的 Runtime identity、owner、项目锁接口和文件锁实现全部为 `internal` / `private`。

### 3.8 诊断和运维能力

本阶段不预建统一 diagnostics/maintenance manager。IDEA 继续使用现有 `ProjectInfoReader`、logcat dump 和 `JuggServer.reportAndUploadLogs()`；TaskRunner 不为尚未落地的 doctor 命令增加 job/task 观测状态。

standalone 的 `doctor/report`、clean/reset 等命令在出现真实调用入口时，再从 IDEA 与 standalone 的共同数据需求提取聚焦服务。custom server、CLI/skills update 和 MCP fetch cleanup 当前继续由既有业务对象负责；TaskRunner 只选择是否进入项目事务，全局资源互斥由资源 owner 自行收口。

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

Global resource owner
  -> fixed global resource lock
```

项目锁实现为同一 `FileExecutionLockManager` 实例内跨线程共享的引用计数 lease：首个项目任务取得进程内 permit 与 NIO `FileChannel` 锁，后续同 Runtime 任务只增加引用，最后一个引用结束后释放文件锁。不同 manager、进程或 classloader 仍以 NIO 文件锁作为最终 Runtime 互斥依据；同进程 classloader 隔离导致 `OverlappingFileLockException` 时按短间隔等待重试。固定全局资源锁继续使用线程级可重入锁，但只覆盖资源 owner 的提交代码，不共享任务事务。

`IExecutionLockManager` 不提供默认方法实现，只声明阻塞项目锁、非阻塞项目锁与 owner 读取语义；测试 fake 也不得通过默认体退化锁行为。

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

Project Runtime Lock 只管理不同 Runtime 对项目的持有权，不承担同一 Runtime 的任务互斥。`RuntimeTaskCoordinator` 为根项目事务创建逻辑 owner，并在 TaskRunner 的所有跨线程提交入口自动传播；独立 owner 互斥，同 owner 通过引用计数共享重入。固定顺序为“Runtime logical owner → Project Runtime lease”，子任务不会等待持有同 owner 的父任务，因此父任务等待默认阻塞、非阻塞、后台或 async 子任务时不形成内部循环等待。无父 owner 的非阻塞 Host task 保留原有并发语义；`status` 仅执行 owner 与 lease 的非阻塞尝试，失败立即降级为只读快照。

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
- 同一 Runtime 的 compile/deploy 等阻塞任务由 `TaskRunnerManager` 串行，不同 Runtime 由 Project Runtime lease 互斥。

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
3. 迁入 `:deploy_compat:standalone_deployer`，只允许进入 `IApplyChangesExecutor` 实际调用链上的 class 和显式第三方依赖；禁止按 package 或整 jar 无边界迁入。
4. 保留原始 `com.android.tools.deployer` 包名，避免 package-private、反射和协议代码因 relocation 失效。
5. 只引入实际需要的第三方基础依赖，不携带完整 IDEA/Android Studio jar。
6. installer、agent、app-server 直接复用 Quail 二进制产物。

由于 standalone deployer 运行在独立 daemon JVM，保留原包名不会与 IDEA 插件内的 Android Studio deployer class 发生 classloader 冲突。

Standalone deployer 必须在 Java 11 JVM 中完成 install、class HOT RELOAD 和 resource HOT RELOAD 验证。若反编译闭包存在 Java 11 不可用的 JDK API 或依赖，必须在受控闭包内完成等价实现；不得退回为加载 Quail 的 Java 21 class，也不得扩大迁入范围规避问题。

### 5.2 二进制资源

资源按版本存放：

```text
deploy_compat/standalone_deployer/src/main/resources/deployer/quail/
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

现有 `IAsDeployerCompat` 同时承载 IDEA 集成和 deployer transport。Step 9 先为 standalone executor 引入部署领域接口：

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

Step 10 对齐 `optimisticSwap` 等共享方法签名后，让 `IAsDeployerCompat` 直接继承 `IApplyChangesExecutor`：

```text
IApplyChangesExecutor
├── IAsDeployerCompat
│   └── AsDeployerCompat / v_* compat
└── StandaloneApplyChangesExecutor
    └── 使用 :deploy_compat:standalone_deployer 固定 Quail 实现
```

不新增只负责转发的 `IdeaApplyChangesExecutor`。共享部署编排依赖 `IApplyChangesExecutor`，IDEA 专属调用继续依赖 `IAsDeployerCompat`。

IDEA 专属能力继续保留在 `IAsDeployerCompat`：

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
- 项目锁，以及由具体资源 owner 管理的固定全局资源锁。
- 设备发现。
- runtime resource 解压和版本校验。
- 4 小时无外部有效活动后的自动退出。

Standalone daemon 代码以 Java 11 编译。正式发行提供单一平台无关 Bundle，例如 `jugg-standalone-<version>.zip`，不携带 jlink runtime image，包含完整 standalone runtimeClasspath、Java 11 standalone deployer、Python CLI、稳定 daemon launcher、三平台安装入口和完整性 metadata：

```text
jugg-standalone-<version>/
├── install.command
├── install.sh
├── install.cmd
├── installer/
│   └── jugg-standalone-installer.jar
├── payload/
│   ├── standalone_bundle_manifest.json
│   ├── jugg-standalone-selector.jar
│   ├── jugg-standalone-bootstrap.jar
│   ├── jars/
│   └── cli/
```

`standalone_bundle_manifest.json` 是离线 Bundle 的权威闭包，至少记录 `releaseBuildId`、`schemaVersion`、`runtimeVersion`、`runtimeApiVersion`、`bootstrapApiVersion`、有序 unique JAR 名称和 SHA-256。它由根构建根据实际 standalone runtimeClasspath 生成，安装器不得扫描共享 `~/.jugg/hot_update/jars/` 或根据 JAR 前缀猜测闭包。服务器普通热更新已有明确文件列表，不要求额外传输 Bundle manifest，由 updater 根据已校验列表生成本地 `standalone_load_manifest.json`；reinstall 更新必须额外携带完整 Bundle，保证新插件 ZIP 仍具备插件内安装能力。

安装脚本只负责定位 Java 并启动无第三方依赖、Java 11 编译的 installer。安装结果保留一个冻结启动 ABI 的 Java 11 selector，selector 只解析 standalone manifest 的稳定头部并按其中的版本化 bootstrap 路径加载 bootstrap；bootstrap 再以受控 `URLClassLoader` 在当前 JVM 加载有序 runtime JAR，不拼接 `java -cp <全部绝对路径>`，避免 Windows 命令行长度限制。普通热更新不得提高 `schemaVersion` 或 `bootstrapApiVersion`；需要升级 bootstrap、launcher 或 Python CLI 时必须走完整 Bundle 安装事务。

完整安装先在 `~/.jugg/standalone/releases/<releaseBuildId>/` stage 并校验版本化 bootstrap、CLI 和其他 launcher 资源，再复制不可变 runtime JAR，最后以 `standalone_load_manifest.json` 的原子替换作为提交点。selector 和稳定 CLI wrapper 根据 manifest 的 `toolingReleaseBuildId` 选择版本化 release；普通热更新保持 `toolingReleaseBuildId` 不变。Standalone 不保留 previous manifest，安装或启动失败由用户重新安装恢复。

bootstrap 按 active manifest 的声明顺序加载 Runtime JAR 并启动 daemon。class load、link 或 daemon 初始化失败时直接返回异常，active manifest 保持不变；Standalone 不提供自动或手工 rollback，用户通过重新安装修复不可启动版本。selector ABI 在 Step 12 固定，未来若必须改变 selector 本身，作为新的安装迁移处理，不属于普通 hot update。

安装器统一承担三平台 JDK/Python 校验、全局文件锁、路径安全、SHA-256 和原子发布。Java 优先使用 `JAVA_HOME`，再使用 `PATH`，要求完整 JDK 而非仅 JRE；Python 复用现有 CLI 的 `python3` → `python` 发现顺序并要求 3.7+，缺失或版本过低时在提交安装前明确失败。Jugg daemon 目标支持 Java 11、17、21，目标 Android 工程自身的 AGP/Gradle JDK 下限仍由工程决定。当前 `cmd_line` distribution 中 `repository-32.0.1.jar` 已存在 major version 61 class，因此 Java 11 仍是 Step 12 必须消除的真实发行阻塞，不能只凭项目 `jvmTarget=11` 或 daemon 能启动判定兼容。安装结果固定为：

```text
~/.jugg/bin/                         # 稳定 Python CLI wrapper
~/.jugg/standalone/bin/              # 稳定 jugg-standalone / .bat 入口
~/.jugg/standalone/selector/         # 冻结 ABI 的 Java 11 selector
~/.jugg/standalone/releases/<id>/    # 版本化 bootstrap、CLI 和 launcher 资源
~/.jugg/hot_update/jars/             # 共享不可变 JAR 内容池
~/.jugg/hot_update/standalone_load_manifest.json
```

外部 Bundle 安装只初始化 standalone manifest，不创建空的 IDEA `load_manifest.json`。IDEA 插件在 `jugg/standalone/` 携带同一个 Bundle 作为非 classpath artifact；插件端“Install CLI”复用同一安装逻辑，而不是分别维护另一套解压规则。若插件当前由普通 hot update 加载了比磁盘 Bundle 更新的 Runtime，“Install CLI”先安装 bundled tooling/base runtime，再立即执行一次正向 standalone update check 并在校验成功后激活最新兼容 runtime；离线时保留可工作的 bundled 版本。已有 standalone 比 bundled 版本更新时默认不降级，只有用户明确确认才安装旧 bundled 版本。重复安装同一 Bundle 必须幂等，任一校验或复制失败时不得替换 active standalone manifest。macOS ZIP 必须保留 `install.command` 可执行位，同时保留 `sh install.sh` 命令行入口作为系统阻止双击脚本时的明确降级路径。

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

- `adb.exe`、完整 JDK 11/17/21、`gradlew.bat` 发现。
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
| Step 2 | 已完成 | 任务域、项目锁、全局资源锁、IDEA task adapter、后台 Job 生命周期和项目级 deployment cache 已落地；review 改进已合入 |
| Step 3 | 已完成 | 项目模型、Compile Context 核心与本地 Gradle project info 调度已下沉；IDEA/Gradle-only source 已落地 |
| Step 4 | 已完成 | 文件变化处理与 Git reconcile 已下沉，共享 WatchService monitor 与 dependency policy 已落地 |
| Step 5 | 已完成 | 共享 Runtime Settings、IDEA 旧设置迁移、统一 custom config 生命周期与 custom compiler reload/dispose 已落地 |
| Step 6 | 已完成 | Server RuntimeInfo 与共享 JuggHotUpdateManager 已落地；诊断、运维门面和资源版本策略延后到真实 standalone 调用出现时 |
| Step 7 | 已完成 | 共享 CLI Run Configuration schema、确定性默认推断、配置集合/指针、IDEA 导入/选择监听和 Gradle 成功回写已落地 |
| Step 8 | 已完成 | Java 11 daemon/registry/MCP/status 骨架、last owner、idle 生命周期、Python 双 Runtime 发现与 hook 门禁已落地；编译部署仍待 Step 10～11 |
| Step 9 | 已完成 | 在 `deploy_compat/standalone_deployer/` 落地固定 Quail 1 的 Java 11 standalone deployer、版本资源、完整性校验与真机 install/class/resource PoC |
| Step 10 | 已完成 | 共享 deploy lifecycle、IDEA 接线、standalone Host 边界和跨 Runtime cache 恢复已落地；standalone 编排组装与用户命令留待 Step 11 |
| Step 11 | 已完成 | standalone 已串联 init、Gradle baseline、Git/WatchService、增量编译、共享 deploy、异步 job/取消/轮询与 Runtime owner/cache generation 恢复 |
| Step 12 | 已完成 | 单一跨平台 Bundle、统一 `releaseBuildId`、Java 11 bootstrap、版本化安装事务、双 Runtime manifest、hot update candidate/精确激活、插件安装接线和发行门禁已落地；macOS 已完成 JDK 11/17/21 安装与链接验收，Linux/Windows/真机矩阵需在对应环境执行 |

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

实现状态：已完成。`TaskRunnerManager` 已下沉 `main`，通过 `IHostTaskExecutor` 隔离 IDEA `Task.Backgroundable` 执行 / `ProgressIndicator` 与 CLI 无 UI 执行；同 Runtime 的阻塞项目任务由 manager 内公平可重入锁串行，`FileExecutionLockManager` 通过引用计数 NIO lease 只互斥不同 Runtime，并写入 owner metadata，诊断读取会清理异常退出遗留的 stale metadata。完整 `JuggRunningTask`、项目初始化/更新、文件变化处理和 deployment cache 读写已进入项目事务。TaskRunner 不再提供 `isGlobalWrite` 或任意 global callback；CLI 自动更新、skills 安装、hot update、runtime resource、settings 与 library Test APK build history 由各自资源 owner 在内部取得固定全局资源锁。hot update Loader 保持无 TaskRunner 的只读 bootstrap；现有代码已实现 IDEA `load_manifest.json`、embedded packaged JAR 同步、原子快照发布和 90 天未引用 JAR 清理，Step 12 在同一内容池上新增独立 standalone manifest 与联合引用清理。TaskRunner 统一跟踪 Job，并在 `JuggManager.dispose()` 时取消尚未执行的任务。已进入写事务的任务不被强制解锁；异步 completion owner 在关闭时结束等待，事务收到取消后退栈并通过 `finally` 自然释放 owner 与 Project Runtime lease，避免半提交或锁引用失配。

原用于隔离 IDEA 模块的 `IBackgroundTaskRunner` / `CoroutineBackgroundTaskRunner` 已删除。`TaskRunnerManager` 下沉后成为唯一任务编排入口，IDEA 与现有 CLI 都直接持有完整实例；Host task 的实际提交由 `IHostTaskExecutor` 完成，IDEA 使用 `HostTaskExecutor`，CLI 使用无 UI executor，并通过 `runtimeType=standalone`、`runtimeVersion` 参与同一套项目锁。命令结束时先 dispose TaskRunner、再取消 CoroutineScope。`TaskRunnerManager.dispatcher` 固定返回其实际 CoroutineScope dispatcher，供 ConstRef 等共享组件调度子任务。`ITaskEventReporter` 与 IDEA 的单行转发实现已删除，TaskRunner 直接通过注入的 `JuggServer` 上报完成事件。

`runTaskSafe` 已拆分 `isProjectWrite` 与 `isBlockIncrementalCompile`：阻塞任务在项目事务内设置 `isInitializingIncrementalCompile`，无项目锁却要求阻塞增量编译的组合会被直接拒绝。同 Runtime 的独立项目事务由逻辑 owner 协调器串行，TaskRunner 子任务自动继承父 owner；无父 owner 的非阻塞 Host task 保留原有并发语义。`status` 通过协调器和 Project Runtime lease 的非阻塞尝试保留空闲 refresh 语义。`JuggDeploymentCacheStore` 继续依赖项目事务串行内存与磁盘访问，不同 Runtime 由 Project Runtime lease 互斥；磁盘写入使用临时文件原子替换。

已完成验证：

- `ProjectExecutionLockTest`（L2，双 JVM 项目锁竞争、正常/异常退出、同 Runtime 跨线程 lease 共享与最后引用释放、metadata、G → Project fail-fast，以及固定全局锁跨项目/跨入口/跨 classloader 锁表串行）
- `JuggDeploymentCacheStoreTest`（L1/L2，原子替换、项目隔离、双 Runtime 串行写入）
- `TaskRunnerManagerTest`（L2，独立事务串行、默认阻塞/非阻塞/后台/async 事务内子任务完成、非阻塞 `try`、锁内 initializing、上报及 dispose）
- `GlobalResourceLockArchitectureTest`（静态架构守卫，TaskRunner 与 IDEA 业务层不得拥有 Global Resource Lock）
- `WindowsUserPathHelperTest`（L1，Windows PATH 解析与外部命令硬超时）
- `GradleProjectInfoLocalFetchManagerTest` / `JuggManagerFullBuildFlowTest`（L2，remote-init completion 正常完成、关闭取消并停止后续 classpath 初始化）
- `JuggHotUpdateManagerTest`（L1/L2，hot update 锁外下载、并发提交淘汰、快照原子替换与 90 天 jar 清理）
- `JuggDeployerInstallTest#hot update bootstrap does not depend on task runner`（L2，Loader bootstrap 依赖边界）
- `JuggDeployerInstallTest#production code uses task runner instead of execution lock types`（L2，生产调用点不引用锁实现类型）
- `LibraryTestApkBuildHistoryTest`（L1，跨实例并发写不丢记录）
- `JuggDeployerInstallTest`、`JuggDeployerHelperDeployFlowTest`（L2）
- `TopLevelFlowTest#testInstallAndLaunch`（L3）
- `CmdLineTest`（CLI 使用统一 TaskRunner 完成增量构建）、`:idea:compileKotlin`

目标：让 IDEA 与后续 standalone 使用同一任务串行、锁和后台任务语义。

任务：

- 将 `TaskRunnerManager` 下沉到 `main`，建立 `ProjectExecutionLock`；固定全局资源锁由资源 owner 内部持有。
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

实现状态：已完成。新增 `RuntimeInfo`，仅包含 `runtimeType/runtimeVersion/hostVersion/buildTime`，由 IDEA、CI、standalone 各自的 Host 边界单点构造并通过 `IPlatformApi.getRuntimeInfo()` 复用；`JuggServer` 不再读取 `Project`、`PluginInfoReader` 或 `PlatformApi`，事件上报继续保留后端兼容的 `version/ide_version` 字段，`runtimeType` 仅用于 Runtime 锁 owner identity。custom server 输入已从 `JuggServerChooser` 的 Host dialog 中移除，IDEA 获取输入后通过普通后台任务调用共享 `JuggServer`；settings repository 在内部完成全局资源加锁与原子写入。

hot update 的下载、MD5 校验、原子 jar/metadata/load manifest 发布、embedded jar 同步和过期清理统一下沉到 `JuggHotUpdateManager`。IDEA `IdeaHotUpdateCoordinator` 保留定时检查、频控、通知、插件安装/重启和 reopen project；现有实现只发布 IDEA `load_manifest.json`，Step 12 让 standalone 通过 `JuggServer` 检查更新后复用同一 manager，并在下一次 daemon 启动读取独立 `standalone_load_manifest.json`。`isNeedReinstall=true` 对两侧都只记录已校验 JAR 和 update metadata，不替换任一 active manifest。

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
- standalone 复用 `~/.jugg/hot_update` 下载与 metadata，更新只在下一次 daemon 启动加载；`isNeedReinstall=true` 不替换 standalone active manifest。
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

daemon idle deadline 为 4 小时，任意 MCP HTTP 请求到达时刷新；job、项目写事务和 update download 使用独立 activity counter 延期退出，并按 1 分钟周期复查。Step 8 阶段未接入 WatchService 和后台轮询；Step 11 接入后仍不把这些内部活动计为外部请求。

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

实现状态：已完成。新增 `:deploy_compat:standalone_deployer`，以 Java 11 重编译固定 Quail 1 的最小 deployer 闭包，打包四 ABI installer 与 Java 8 protocol JAR；daemon 启动前通过 `JuggResourceManager` 校验 metadata/protocol/SHA-256。Pixel 7 API 36 真机已验证 base install、class HOT RELOAD 和 asset resource full swap：类更新不重启 Activity，资源更新只发生一次协议要求的预期 Activity 重启，App 进程全程不重启。Step 10 再迁移共享部署编排。

目标：一次完成最小、可发行的 standalone deployer 实现，不依赖完整 Android Studio jar。

任务：

- 在 `deploy_compat/standalone_deployer/` 新增 `:deploy_compat:standalone_deployer`。
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

实现状态：已完成。`JuggDeployOrchestrator`、deploy task/deployer、LaunchContext、recover/retry、Direct Overlay transport 与 deployment service 已下沉 `main`，IDEA 已改由共享 orchestrator 执行；`IDeployHost` 隔离 Host 操作，`IdeaDeployEnvironment` 与 `StandaloneDeployEnvironment` 分别提供实现。standalone Runtime 已缓存固定 Quail executor 和共享引用计数的真实 ddmlib bridge，但因 Compile Context、deploy target 和用户命令属于 Step 11，本 Step 不在 standalone 内组装或调用 orchestrator。deployment memory cache 的 owner 与磁盘 snapshot generation 失效策略由 `JuggDeploymentService` 内部完成。

批准实施范围（2026-08-06）：

- 统一 `IApplyChangesExecutor` 与 `IAsDeployerCompat` 的 Apply Changes transport 契约，IDEA compat 直接实现共享接口。
- 将 deploy task、deployer、launch context、recover、retry、Direct Overlay lifecycle 和 deployment cache service 下沉 `main`。
- 建立共享 `JuggDeployOrchestrator`；IDEA 与 standalone 仅保留各自真实设备、ADB、prompt、debugger 和 UI 环境差异。
- standalone 使用固定 Quail executor 与真实 ddmlib 设备边界，但本 Step 不开放 Step 11 的 compile/deploy MCP 用户命令。
- Runtime owner、runtime version 或 deployment cache generation 变化时失效 Runtime 本地 deployment memory cache，并从项目级磁盘 snapshot 恢复。
- 复用现有 deploy Flow、recover、retry、install 和顶层 Flow owner，补充 standalone Host 边界与 Runtime 切换 cache 恢复验证。
- 不包含 standalone Debug attach、AndroidTest UI 迁移、完整编译部署串联、三平台发行或多版本 standalone deployer。

实施前基线：

- `TopLevelFlowTest#testInstallAndLaunch` 通过。
- `StandaloneApplyChangesExecutorTest` 与 `StandaloneDeployerArchitectureTest` 通过。
- `JuggDeployerHelperDeployFlowTest` 20 个场景均因 `Device does not originate from an Android Studio compatibility boundary` 失败，作为本 Step 的失败证据。

任务：

- 对齐 `IApplyChangesExecutor` 与现有 IDEA deploy transport 的共享方法签名。
- 让 `IAsDeployerCompat` 直接继承 `IApplyChangesExecutor`，删除重复方法声明，不新增 `IdeaApplyChangesExecutor` 转发类。
- 下沉 `JuggDeployTask`、`JuggDeployer`、LaunchContext 核心。
- 下沉 recover、retry、Direct Overlay lifecycle。
- 建立 `JuggDeployOrchestrator` 和两个部署运行环境。
- IDEA 部署环境关联现有 `AsDeployerCompat`。
- standalone 部署环境关联 Quail 实现。
- 适配现有 `JuggDeploymentService.memoryCache`；Runtime owner、runtime version 或 deployment cache generation 变化时清理并从项目级磁盘 cache 恢复。

验证性任务：

- IDEA 现有 deploy Flow 全部保持通过。
- 共享 orchestrator 的 install、HOT RELOAD、HOT FIX、recover、retry 通过；standalone executor 与真实设备边界保持可用。
- 同一项目在 IDEA/standalone 间切换后可恢复。

实施验证：

- `JuggDeployerHelperDeployFlowTest` 20 个 install/HOT RELOAD/HOT FIX/recover/retry 场景通过，共享 orchestrator 不再触发 IDEA device boundary 错误。
- `TopLevelFlowTest#testInstallAndLaunch` 真实设备 install/launch 通过。
- `StandaloneApplyChangesExecutorTest`、`StandaloneDeployerArchitectureTest` 通过；固定 Quail executor 保持 Java 11 与发行边界约束。
- `JuggDeploymentServiceTest` 验证另一 Runtime 写入项目 snapshot 后，现有 Runtime 会失效 memory cache 并恢复新 overlay。
- `CmdLineTest` 5 个现有 CI 场景通过，Step 10 未改变 CI 参数和产物语义。
- standalone 完整 lifecycle 调用需要 Step 11 提供 Compile Context、deploy target 与命令入口，本 Step 不宣称已完成该端到端链路。

### Step 11：编译与部署完整串联

目标：实现完整 CLI 用户链路。

实现状态：已完成。`StandaloneProjectServices` 组装 Gradle-only project model、Compile Context、历史恢复、Git/WatchService、共享 compiler/deployer 与项目锁；Runtime 构造期恢复、显式 `init` 以及 refresh → compile/Gradle → context/config → deploy 全链均持有跨进程项目写锁，standalone 项目写事务同时计入 daemon activity。空闲 status 仅在非阻塞取得项目锁后执行 owner 恢复、Git refresh 和一致性快照；同 Runtime 正在编译或项目锁由其他写事务持有时，不等待锁、不刷新文件状态，立即返回部署状态、fallback、待编译文件、baseline 和时间戳等真实只读快照，保留 CLI wait/heartbeat。IDEA 与 standalone 每次接管 owner 后都在下一次成功取得项目锁的业务链或 status snapshot 开始前从磁盘恢复 Compile Context、history、APK 与 Git 状态，并失效 deployment memory cache；cache generation 检查不再提前消费 owner-change event。共享 `JuggCompilerHelper` 在 Compile Context rebind 和关闭时通过 IntelliJ `Disposer` 递归释放 compiler 子树；standalone `platform_compat` 实现保持 identity-based 注册、主动释放解绑、兄弟节点逆注册顺序和异常后的完整清理语义。`StandaloneConfigurationRunner` 复用现有 MCP `CompileJobManager` 提供新任务取消旧任务、轮询、indicator 和 `compile_latest.log`，同时对齐 IDEA 的 dependency build lifecycle 与 full-build failure 状态。standalone capability 现为 `version`、`list-projects`、`init`、`compile`、`deploy`、`gradle-build`、`get-compile-status`、`status`。`jugg init` 在无当前配置时优先复用 Gradle project info，缺失时执行一次 `assembleDebug --dry-run` 生成快照；`gradle-build` 只建立/刷新 baseline，安装或增量部署由后续 `deploy` 完成。standalone 保留调用环境显式 `JAVA_HOME`；设备选择优先 `ANDROID_SERIAL`，无 serial 时仅允许恰好一台在线设备。

实施边界：本 Step 不开放 standalone remote SSH、Debug attach、AndroidTest UI、`clean-reinstall` 及 Step 12 跨平台 Bundle 发行。当前 profile 启用 remote compile 时，`init` 与运行命令均在执行前结构化失败，不静默切到本地，也不读取或使用远程凭据。

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

已完成验证：

- `StandaloneRuntimeTest`（L2，capability、status 与基于 Gradle project info 的 `init`）。
- `CmdLineTest`（L2，standalone launcher/distribution 入口回归）。
- `JuggCompileHelperTest`、`JuggDeployerHelperDeployTest`、`JuggDeployerHelperDeployFlowTest`（L2，共享编译与部署 owner 回归）。
- `TopLevelFlowTest#testInstallAndLaunch`（L3，IDEA 既有安装启动链路回归）。
- `android_demo_project` 真机手工链路（L3，standalone `init → gradle-build → compile → deploy → status`）。

### Step 12：三平台发行和验收

实现状态：已完成。根构建生成全局唯一、可排序的 `releaseBuildId`，由 IDEA metadata、standalone metadata 和 Bundle manifest 复用；`:cmd_line:standaloneBundle` 从实际 runtimeClasspath 生成带版本的单一跨平台 ZIP，Runtime JAR 使用 SHA-256 内容寻址名称，并携带版本化 Python CLI、三平台安装入口和固定 Java 11 bootstrap。安装器验证完整 JDK 11+、Python 3.7+、路径/symlink/SHA-256，在统一全局锁内先发布 immutable JAR、tooling 和 CLI，最后提交 standalone active manifest；同 channel 按产品版本/build identity 接管，不同 channel 或降级需显式授权。bootstrap 不扫描共享池，按 manifest 顺序使用进程内 classloader，启动失败直接返回异常并保留 active manifest。

IDEA 与 standalone 使用独立 active manifest；兼容 hot update 同时准备两组文件，reinstall 只保存 candidate，插件 `releaseBuildId` 精确匹配后才激活。插件完整发行与 hot-update 重建 ZIP 均把 Bundle 放入 `jugg/standalone/`，standalone JAR 不进入 `jugg/lib/`；Step 11 legacy updater 不再生成缺 Bundle 的重装 ZIP。发行门禁覆盖 Bundle/manifest 一致性、Android class owner、普通 class major `<=55`、旧 Gson JSON、服务端路径、安装失败不切 active、接管矩阵和 candidate identity。macOS 已使用 JDK 11.0.26、17.0.17、21.0.6 完成真实 Bundle 安装及 ordered-classloader `--verify`；Linux、Windows、真实设备 HOT RELOAD 和 Step 11 实包升级仍需在对应发行环境执行验收矩阵。

任务：

- 构建单一 `jugg-standalone-<version>.zip`，包含 macOS `install.command`、POSIX `install.sh`、Windows `install.cmd`，不构建 jlink runtime image。
- 根构建一次生成全局唯一、可排序的 `releaseBuildId`，IDEA metadata、Bundle、服务端 metadata、candidate 与两份 Runtime manifest 全链复用，禁止 IDEA/cmdline 各自产生 compile timestamp。
- Bundle 携带完整 standalone runtimeClasspath、版本化 Python CLI/bootstrap、冻结 ABI 的 selector、`standalone_bundle_manifest.json` 和 SHA-256；IDEA 插件固定在 `jugg/standalone/` 携带同一个 Bundle，但不将其中 JAR 放入 `jugg/lib/`。
- 安装器验证完整 JDK 11+ 和 Python 3.7+；发行阶段扫描全部普通 class entry 的 major version 必须 `<=55`，multi-release JAR 只允许 `META-INF/versions/<n>` 中符合其声明版本的高版本 class。当前 `repository-32.0.1.jar` 的 major 61 阻塞必须通过裁剪、替换或降级依赖解决，再使用 Java 11、17、21 分别完成完整 standalone Flow。
- 外部安装与插件“Install CLI”复用同一安装逻辑，stage 版本化 release，初始化 `~/.jugg/hot_update/jars/` 与 `standalone_load_manifest.json`；manifest 最后提交，启动失败直接返回异常并由用户重新安装恢复。
- 保留 IDEA `load_manifest.json`，新增 standalone active manifest；两个 Runtime 使用同一 JAR 内容池但只加载各自 active manifest，清理保留两份 active 和待激活更新引用的并集。
- 扩展 hot update 协议，在不改变现有 `jarFileInfos` IDEA 语义的前提下新增 nullable standalone 文件集合和 reinstall Bundle artifact；真实 Gson 缺字段统一 `orEmpty()`，普通更新发布配套 standalone manifest，`isNeedReinstall=true` 仅在新插件 `releaseBuildId` 精确匹配后激活。首个 Step 12 版本必须通过 Marketplace/官方完整插件 ZIP 从 Step 11 迁移，禁止 legacy updater 重装。
- 修正插件安装 ZIP 生成路径：IDEA manifest 引用 JAR 只进入 `jugg/lib/`，完整 Bundle 进入 `jugg/standalone/`；禁止 standalone ddmlib、deployer、`base_api` 或 `cmd_line` 直接进入 IDEA classpath。
- 所有 shared-pool 来源统一不可变 unique name 和文件名安全校验；IDEA embedded 同名不同内容、服务端 traversal、绝对路径、symlink 和 Windows 占用旧 JAR 均不得覆盖 active 文件。
- 明确 standalone 接管策略：同 channel 先比较产品版本、同版本再比较 build，不同 channel 不自动接管；外部安装可显式降级，插件“Install CLI”默认不降级且对旧 bundled 版本要求确认；插件回退本身不静默回退 standalone。
- 普通 hot update 后点击“Install CLI”先安装 bundled tooling，再正向检查并激活最新已校验 standalone runtime；管理 `hot_update/candidates/<releaseBuildId>/` 的 Bundle owner 和替换/成功/失败清理。
- 普通兼容更新必须允许旧 IDEA 进程和新 standalone 长期并存并双向兼容共享持久化状态；不满足该契约时强制 `isNeedReinstall=true`。
- Python CLI auto-start/stop daemon，launcher 每次启动读取 standalone manifest；运行中 daemon 不热换 classpath，新版本在下一次启动生效。
- 验证 standalone 独立安装、Step 11 → 首个 Step 12 完整插件升级、先安装 standalone 后安装 IDEA、先安装 IDEA 后安装 standalone、普通 hot update 后首次 Install CLI、IDEA 更新同步 standalone、Stable/Beta 交替启动、旧版本晚构建、external → plugin、插件回退、同版本不同 build、manifest 提交后启动失败、损坏 Bundle、下载中断、Windows 长路径和文件占用场景。

实现顺序：

1. 在根构建生成统一 `releaseBuildId`，从 `:cmd_line` 实际 runtimeClasspath 生成 Bundle、内容寻址 JAR 名称和 Bundle manifest，增加 class major、路径和 class owner 架构守卫。
2. 实现 Java 11 selector/bootstrap、三平台薄安装脚本、版本化 release 和以 standalone manifest 为最终提交点的幂等安装事务。
3. 新增 standalone manifest Loader，接入现有 daemon launcher；保留 IDEA Loader 现有 manifest 语义。
4. 扩展 `JuggHotUpdateManager` 与服务端协议数据模型，分别准备和发布 IDEA/standalone manifest，统一 embedded/Bundle/server 不可变命名和路径校验，修正 reinstall identity 与兼容更新边界。
5. 扩展插件“Install CLI”和后续插件更新同步，复用 Bundle 安装服务。
6. 完成 Java 11/17/21、macOS/Linux/Windows 发行验证和真实 demo Flow。

明确非目标：

- 不携带或生成 jlink/JRE runtime image。
- 不根据共享目录中的 JAR 前缀推导 active classpath。
- 不在运行中的 standalone daemon 内热替换 classloader。
- 不为消除双 manifest 而 relocate/shade 全部 Android Studio 或第三方依赖。

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
- Bundle manifest 与实际 standalone runtimeClasspath 一致，关键 Android runtime class 保持唯一 owner；普通 class major 全部 `<=55`，multi-release entry 按版本目录单独校验。
- standalone 安装幂等；缺失/过低版本 JDK、缺失/低于 3.7 的 Python、SHA-256、损坏 ZIP/JAR、非法路径、symlink、stage 失败和原子发布失败均不替换 active manifest；新 bootstrap/CLI 已 stage 但 manifest 未切换时旧 release 仍可启动。
- IDEA/standalone manifest 从同一 JAR 池加载各自闭包；IDEA embedded、Bundle 和 server 同名不同摘要均失败且不覆盖。插件首次发行及 hot-update 重建 ZIP 都包含 `jugg/standalone/<Bundle>`，其内部 JAR 不直接出现在 `jugg/lib/`。
- 兼容 hot update 同时准备两组文件；普通更新、`isNeedReinstall=true`、同版本不同 `releaseBuildId`、安装被覆盖分别保持正确激活语义；Step 11 legacy updater 收到首个 Step 12 版本时只提示完整插件升级，不生成残缺 ZIP。
- 旧协议测试使用真实 Gson JSON 缺失 nullable standalone 字段反序列化，验证边界 `orEmpty()`；服务端 `uniqueName` 覆盖 `../`、绝对路径、Windows/POSIX 分隔符、控制字符和 normalize 越界。
- 自动接管覆盖同 channel 新产品版本、同版本新 build、旧版本晚构建、不同 channel、external 安装、显式 Install CLI 和插件回退矩阵；普通启动和旧 embedded Bundle 均不得静默降级 standalone。
- 未安装 standalone 时经历普通 hot update 再点击“Install CLI”，以及新版 standalone 点击旧 embedded Bundle 安装入口时，分别得到最新兼容 runtime 和明确的非降级行为。
- candidate Bundle 的下载、替换、插件重启前持有、成功激活、确认失败和 orphan 清理均有唯一 owner，不在 `hot_update/jars/` 中长期残留 ZIP。
- selector/bootstrap 以进程内 classloader 启动全部 runtime JAR，不生成超长 `-cp`；Windows 长用户目录下仍可启动。
- manifest 已提交但 daemon 在 class load、link 或初始化阶段失败时，启动命令直接失败且 active manifest 保持不变；用户重新安装可用 Bundle 恢复。

### L3

新增 standalone Flow，使用真实 demo 编译和真实 emulator/device：

- Gradle baseline → 修改方法 → HOT RELOAD，进程不重启且行为变化。
- 修改资源 → HOT RELOAD，Activity 不重启且 UI 生效。
- IDEA deploy 后切 standalone deploy。
- standalone deploy 后切 IDEA deploy。
- overlay mismatch → recover → redeploy。
- Windows 独立完整 Flow。
- Java 11、17、21 分别启动 daemon 并完成 `init → gradle-build → compile → deploy → status`；若目标工程 AGP 要求更高 JDK，测试使用匹配工程并单独验证 daemon JVM 兼容边界。
- 外部 Bundle 安装、插件内安装、IDEA 更新同步 standalone、Stable/Beta 交替启动和插件回退后，下一次 daemon 启动选择符合接管规则的 manifest 且旧运行进程不受影响。
- 旧 IDEA classloader 保持运行时启动新版 standalone，切换同一项目 owner 并完成状态读取、编译与部署恢复，验证兼容更新的双向持久化契约。
- 从真实 Step 11 插件升级到首个 Step 12 完整插件 ZIP，确认新插件包含 Bundle、standalone 可安装，legacy updater 不参与重装。

其中“资源更新时 Activity 不重启”仍是 Step 10～12 完整 standalone Flow 的最终验收目标。Step 9 只验证固定 Quail `OptimisticApkSwapper` 闭包，其 resource full swap 与现有 IDEA 路径一致，会执行一次预期 Activity restart，不代表最终无重启目标已完成。

涉及 IDEA deploy 编排下沉时，必须定向回归已有 `TopLevelFlowTest` 或等价 L3 Flow，不能只依赖 standalone 测试。

CI 与 standalone 同模块改造必须定向回归现有 `CmdLineTest`，证明 CI 参数、包名和产物语义不变。

## 11. 首期不做

- AS 插件改为 daemon 薄客户端。
- IDEA Debug attach 迁移到 standalone。
- 用 mock `Project` 模拟完整 IntelliJ service container。
- 支持多版本 standalone deployer 实现。
- 用 Direct Overlay 替代完整 HOT RELOAD。
- 自动展示和选择多个 Run Configuration 候选。
- 携带宿主平台 runtime image 或 jlink 产物。

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
- standalone daemon、standalone deployer 及全部普通依赖 class 均满足 Java 11 字节码边界，可使用完整 JDK 11、17、21 完成真实 Flow；目标工程构建仍遵循自身 AGP/Gradle JDK 要求。
- 单一 Bundle 可在 macOS、Linux、Windows 安装，外部安装和插件安装得到一致的目录、launcher 和 standalone manifest。
- IDEA 与 standalone 共享不可变 JAR 内容池但使用独立 manifest，任一 Runtime 不会加载另一侧专属 JAR。
- 同一 release 的插件、Bundle、candidate 和 Runtime manifest 使用完全一致的 `releaseBuildId`；同版本不同构建不会互相误激活。
- 插件普通更新可按接管规则同步发布配套 standalone 快照；`isNeedReinstall=true` 在新插件 `releaseBuildId` 精确生效前不激活 standalone 候选版本。
- 首次发行和 hot-update 重建的插件 ZIP 均携带完整 Bundle artifact，standalone 专属 JAR 不直接进入 `jugg/lib/`。
- 多 Android Studio 安装、外部安装和插件回退不会因普通启动静默降级或反复切换 standalone。
- 需要 bootstrap/CLI 升级时通过完整 Bundle 事务完成，普通 hot update 不突破既有 schema/bootstrap API。
- Step 11 到首个 Step 12 版本通过完整插件包迁移，不会由 legacy updater 生成缺少 Bundle 的插件。
- Bundle 安装和插件内安装在提交前验证 Python 3.7+；普通 hot update 后首次安装 CLI 会收敛到最新兼容 standalone runtime，旧 embedded Bundle 不会默认降级现有 runtime。
- Bundle 或更新文件校验失败、复制中断和重复安装不会替换现有 active manifest；提交后启动失败直接返回异常，用户通过重新安装恢复。
- reinstall Bundle candidate 有固定目录和 owner，替换、成功、失败后不会形成无引用的大体积 ZIP 堆积。
- hook 在没有 complete flag 时不会拉起 daemon；daemon 连续 4 小时无外部有效活动后可安全退出。
- deployer Java 实现和 installer/agent/app-server 二进制有明确版本和完整性校验。
