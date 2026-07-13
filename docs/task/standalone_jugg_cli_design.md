# Standalone Jugg CLI 设计方案

## 1. 背景与目标

当前 Jugg 包含三种运行形态：

- Android Studio / IntelliJ 插件运行时，负责编译、部署、设备状态和 MCP runtime。
- `cmd_line` CI 运行时，负责 Gradle 基线构建与指定 changed files 的增量 APK 构建。
- Python `jugg` CLI，通过 MCP Server 调用 IDEA 插件运行时。

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
                         ┌─ IDEA Runtime Adapter
Jugg Runtime Engine ─────┤
                         └─ Standalone Runtime Adapter
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

### 2.2 CI 与 standalone 保持独立入口

`cmd_line` 按目录拆分为两个子模块：

```text
cmd_line/
├── ci/                         // :cmd_line:ci，Java 11
│   └── src/main/java/.../cmdline/ci/
│       ├── BuildGradleBaseCommand
│       └── BuildIncrementalApkCommand
└── standalone/                 // :cmd_line:standalone，Java 21
    └── src/main/java/.../cmdline/standalone/
        ├── JuggDaemon
        ├── StandaloneProjectRegistry
        ├── StandaloneProjectRuntime
        ├── StandaloneRunConfigurationManager
        └── StandalonePlatformApi
```

CI 命令继续保持一次性进程、显式参数和现有产物语义。Standalone 使用常驻 daemon、项目恢复、自动文件变化检测和设备部署，不复用 CI 的 `.dirty` 一次性约束。

现有 `:cmd_line` 发行入口迁移到 `:cmd_line:ci` 后保留兼容任务或 wrapper，避免现有 CI 脚本和产物名称立即失效。

### 2.3 Standalone Apply Changes 使用独立 Gradle 模块

新增模块：

```text
:standalone_deployer
```

该模块只由 standalone runtime 依赖，不进入 IDEA 插件 classloader。模块固定使用 Android Studio Quail 版本的 deployer 实现和二进制协议，不承担 Android Studio 多版本兼容。

Quail 目标 class 为 Java 21，因此 `:standalone_deployer` 与 `:cmd_line:standalone` 使用 Java 21；`:cmd_line:ci` 继续保持 Java 11。

## 3. 锁与并发模型

### 3.1 TaskRunnerManager 改造结论

当前 `TaskRunnerManager` 使用 `synchronized(this)`：

- 只能串行同一个 JVM 内、同一个 `TaskRunnerManager` 实例的任务。
- 无法协调 IDEA 与 standalone 两个进程。
- 无法协调不同项目实例对 `~/.jugg` 全局文件的并发写入。

保留“所有项目写任务通过 TaskRunnerManager 执行”的设计，但将底层锁抽到 `main`，形成可被 IDEA 和 standalone 共用的跨进程锁框架。

建议职责：

```text
TaskRunnerManager / StandaloneTaskRunner
  -> IExecutionLockManager
       -> project lock
       -> global named lock
```

锁实现使用 JVM 进程内可重入锁 + NIO `FileChannel.tryLock/lock`。外层第一次进入时获取文件锁，嵌套调用只增加当前进程持有计数，避免相同任务链重复获取同一个文件锁。

### 3.2 项目运行锁

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

### 3.3 设备 + package 锁

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

### 3.4 全局 deployment cache 锁

全局 deployment cache 不能只依赖项目锁。

原因：`JuggDeploymentCacheStore` 当前会在内存加载完整 entries，并通过删除旧文件后重写完整文件保存。两个不同项目可分别持有自己的项目锁，却同时读写同一个全局 cache，产生 lost update。

全局 cache 使用与 TaskRunnerManager 相同的锁框架，但使用独立全局 lock key：

```text
~/.jugg/locks/deployment_cache.lock
```

写入要求：

- 持有全局 cache 锁后重新从磁盘加载最新 entries。
- 更新指定 deviceSerial + packageName entry。
- 写入临时文件。
- fsync/flush 后原子替换目标文件。
- IDEA 与 standalone 使用相同实现。

固定锁顺序：

```text
project lock
→ global deployment cache lock
```

首期不增加设备/package 锁；若后续增加，锁顺序固定为：

```text
project lock
→ device/package lock
→ global deployment cache lock
```

### 3.5 Runtime 切换

项目锁释放后允许另一个 runtime 接管。接管时检查：

- 上次 runtime owner。
- runtime version。
- project info fingerprint。
- compile context generation。
- deploy history generation。
- deployment cache entry。
- 设备 overlay id。

owner 变化后：

1. 丢弃 runtime 内存 cache。
2. 从磁盘恢复 compile context、deploy history 和 APK 信息。
3. 刷新 Git changed files。
4. deploy 前复用现有 recover 状态机校验 cache/history/device。
5. 不因 owner 变化直接重装；只有现有 recover 判断失败时才 reinstall。

## 4. Standalone Apply Changes Backend

### 4.1 固定 Quail 实现

Standalone backend 以以下安装目录作为实现和二进制事实来源：

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
2. 从 Quail 对应 class 直接反编译所需实现和传递依赖闭包。
3. 迁入 `:standalone_deployer`。
4. 保留原始 `com.android.tools.deployer` 包名，避免 package-private、反射和协议代码因 relocation 失效。
5. 只引入实际需要的第三方基础依赖，不携带完整 IDEA/Android Studio jar。
6. installer、agent、app-server 直接复用 Quail 二进制产物。

由于 standalone backend 运行在独立 daemon JVM，保留原包名不会与 IDEA 插件内的 Android Studio deployer class 发生 classloader 冲突。

首期不尝试把 Quail deployer 降级编译到 Java 11。Standalone 发行物使用 Java 21，避免因字节码版本、JDK API 和依赖版本差异引入额外适配。CI 发行物仍使用 Java 11，两者独立构建。

### 4.2 二进制资源

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

### 4.3 接口拆分

现有 `IAsDeployerCompat` 同时承载 IDEA 集成和 deployer transport。新增平台中立接口：

```text
IApplyChangesBackend
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
IdeaApplyChangesBackend
  → 委托现有 AsDeployerCompat

StandaloneApplyChangesBackend
  → 使用 :standalone_deployer 固定 Quail 实现
```

IDEA 专属能力继续保留在 `IAsDeployerCompat` 或拆出的 `IIdeaDeployIntegration`：

- IDE 设备选择。
- IDE module info。
- IDEA Run Configuration。
- Debug attach。
- Android Studio DeploymentService adapter。
- Android Studio 版本兼容分发。

### 4.4 部署编排下沉

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
- `Project`/console/prompt adapter。
- Android Studio Debug attach。
- AndroidTest SM Test Runner UI。

`JuggDeployerHelper` 拆成：

```text
JuggDeployOrchestrator        // main，共享状态机和 lifecycle
IdeaDeployRuntimeHost         // idea，IDE prompt/UI/device adapter
StandaloneDeployRuntimeHost   // cmd_line/standalone，CLI log/device adapter
```

### 4.5 HOT RELOAD 边界

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

## 5. platform_compat 边界

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

## 6. Standalone Run Configuration

### 6.1 配置来源

`SuggestRunConfiguration` 已废弃，本方案不再复用。

Gradle project info 读取链增加默认 CLI Run Configuration 识别能力，生成与 IDEA 默认逻辑接近的确定性配置。首期不向用户展示多候选选择器；自动生成一个默认配置，复杂项目通过配置文件覆盖。

默认识别顺序：

1. 最近一次成功 Gradle build 实际使用的 module、variant、task 和 APK 输出。
2. Gradle project info 中名称为 `app` 的 application module。
3. 其他 application module 按稳定排序选择第一个。
4. variant 优先使用 project info 的当前 buildVariant。
5. 缺失时使用 `debug`。

生成结果必须记录推断来源，不能静默把 fallback 结果描述为 IDE 配置。

### 6.2 配置文件

生成配置：

```text
build/jugg/config/cli_run_config.json
```

用户覆盖配置：

```text
build/jugg/config/cli_run_config.local.json
```

CLI 按以下顺序读取：

```text
cli_run_config.json
→ merge cli_run_config.local.json
→ CLI flags 覆盖
```

IDE 和 standalone 只写 `cli_run_config.json`，不覆盖 `.local.json`。

建议字段：

```json
{
  "schemaVersion": 1,
  "generatedBy": "idea|standalone|gradle-project-info",
  "generatedAt": 0,
  "moduleName": "app",
  "variant": "debug",
  "buildTarget": "APP",
  "compileCommand": "./gradlew :app:assembleDebug",
  "outputApkName": "app/build/outputs/apk/debug/*.apk",
  "isRemoteCompile": false,
  "environmentVariables": ""
}
```

远端编译密码、token 等 secret 不写入项目配置。需要时通过环境变量引用或现有安全配置读取。

### 6.3 写入时机

IDEA Runtime：

- Gradle build 成功并确认 APK 输出后写入。
- 写入实际生效的 `JuggGradleCompileOptions`，而不是写入开始前的候选值。
- 普通增量编译成功不重写配置。

Standalone Runtime：

- `jugg init` 在没有配置时根据 Gradle project info 生成。
- standalone Gradle build 成功后写入实际生效配置。

CI Runtime：

- 默认不写该配置，保持现有 CI 产物语义。
- 如未来需要复用，必须通过显式参数开启。

### 6.4 跨平台命令

配置保留逻辑 Gradle command/task；执行前按宿主系统解析 wrapper：

- macOS/Linux：`gradlew`
- Windows：`gradlew.bat`

禁止依赖 `/bin/bash -c` 执行配置命令。

## 7. Standalone Runtime

### 7.1 daemon

Standalone daemon 负责：

- MCP HTTP Server。
- 项目 registry。
- project runtime 生命周期。
- 编译/deploy job。
- 项目锁和全局 named lock。
- 设备发现。
- runtime resource 解压和版本校验。

Standalone daemon 使用 Java 21。正式发行优先携带裁剪后的 runtime image，避免依赖用户机器预装正确版本的 JDK；macOS、Linux、Windows 分别构建对应 runtime image。

Python CLI 端口发现同时识别 IDEA MCP runtime 和 standalone runtime。`version` 增加：

```text
runtimeType=idea|standalone
runtimeVersion
capabilities
```

当两种 runtime 同时存在时，CLI 根据 `projectDir` 查询已初始化项目；同一项目同时存在时，默认使用当前持有项目锁或最近成功运行的 runtime，并允许显式指定 runtime。

### 7.2 设备层

Standalone 使用真实 ddmlib 初始化 `AndroidDebugBridge`，实现：

- 设备发现和授权状态。
- serial 选择。
- shell、streaming shell、push、pull。
- pid、ABI、API level。
- install server 使用的 ADB client。

生产实现统一使用 `ProcessBuilder` / ddmlib API，不复用 test 中依赖 `/bin/bash` 的 `CmdAdb`。

### 7.3 Windows

设计阶段同时覆盖：

- `adb.exe`、`java.exe`、`gradlew.bat` 发现。
- Windows 路径、MSYS/Cygwin/WSL 归一化。
- NIO 文件锁。
- daemon launcher/pid 管理。
- Python/批处理 wrapper。
- long path 和临时目录。

Windows 独立验收，不以 macOS/Linux 通过代替。

## 8. 分阶段实施步骤

### Step 1：锁框架与 TaskRunnerManager 改造

目标：双 Runtime 具备安全共享文件系统的基础。

任务：

- 在 `main` 新增跨进程 named lock 框架。
- `TaskRunnerManager` 将 `synchronized(this)` 替换为项目锁。
- 实现项目锁诊断元数据和 `PROJECT_RUNTIME_BUSY`。
- `JuggDeploymentCacheStore` 使用全局 cache 锁、reload-before-write 和原子替换。
- 定义 lock order。

验证性任务：

- 两个 JVM 进程竞争同一项目锁，最多一个进入写事务。
- owner 正常退出和异常退出后锁均可释放。
- 不同项目并发写 global deployment cache 不丢 entry。

### Step 2：CLI Run Configuration

目标：CLI 无需 IDEA Run Configuration 即可获得稳定 build profile。

任务：

- 定义 `CliRunConfiguration` 和 schema serializer。
- Gradle project info 读取链增加默认配置识别。
- IDE Gradle build success 写 `cli_run_config.json`。
- standalone init/Gradle build 写相同配置。
- 支持 `.local.json` merge。
- secret 字段禁止持久化。

验证性任务：

- 单 app、非 `app` module、多 application module、custom variant 场景生成结果稳定。
- IDE Gradle build 后 CLI 可直接读取并执行同一 task/APK output。
- `.local.json` 不被 IDE 覆盖。

### Step 3：模块和进程骨架

目标：建立 standalone daemon，但暂不完成部署。

任务：

- `cmd_line` 拆分为 `:cmd_line:ci` 和 `:cmd_line:standalone`，分别使用 Java 11 和 Java 21。
- 保留现有 `:cmd_line` CI 构建任务/产物兼容入口。
- 新增 daemon、project registry、runtime holder。
- 实现 standalone MCP runtime。
- `IMcpRuntime` 增加 `projectDir`，MCP action 不再读取 `Project.basePath`。
- 保持 CI 命令和分发兼容。

验证性任务：

- Python CLI 可发现 standalone runtime。
- `version`、`status`、`list-projects` 正常。
- IDEA runtime 和 standalone runtime 可同时启动。

### Step 4：反编译 Quail Standalone Deployer

目标：一次完成最小、可发行的 standalone deployer backend，不依赖完整 Android Studio jar。

任务：

- 新增 `:standalone_deployer`。
- 从 Android Studio Quail 1 梳理并反编译 deployer 调用闭包。
- 引入最小 protobuf、ddmlib、utility 依赖。
- 打包 installer/agent/app-server 二进制。
- 生成 metadata 和 SHA-256 校验。
- 实现 `StandaloneApplyChangesBackend`。

验证性任务：

- 纯 JVM 创建 install session。
- base install 成功并建立 deployment cache。
- 直接调用 `OptimisticApkSwapper` 完成 class HOT RELOAD。
- 资源 HOT RELOAD 生效。
- App 进程和 Activity 不发生非预期重启。
- installer/Java backend 版本不匹配时启动失败并给出明确错误。

### Step 5：部署编排下沉

目标：IDEA 与 standalone 复用同一 deploy lifecycle。

任务：

- 引入 `IApplyChangesBackend`。
- 下沉 `JuggDeployTask`、`JuggDeployer`、LaunchContext 核心。
- 下沉 recover、retry、Direct Overlay lifecycle。
- 拆分 `JuggDeployOrchestrator` 和两个 runtime host。
- IDEA adapter 委托现有 `AsDeployerCompat`。
- standalone adapter 委托 Quail backend。

验证性任务：

- IDEA 现有 deploy Flow 全部保持通过。
- Standalone install、HOT RELOAD、HOT FIX、recover、retry 通过。
- 同一项目在 IDEA/standalone 间切换后可恢复。

### Step 6：编译与部署完整串联

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

### Step 7：三平台发行和验收

任务：

- macOS distribution。
- Linux distribution。
- Windows distribution。
- 三个平台分别携带 Java 21 runtime image。
- Python CLI auto-start/stop daemon。
- runtime update 和版本兼容。
- installer 资源完整性校验。

## 9. 测试策略

实现必须按 TDD 顺序推进。

### L1

- CLI Run Configuration 推断、merge、序列化。
- lock metadata 和 lock key。
- deployment cache 原子读写。
- Quail deployer 数据模型和协议解析中的确定性逻辑。

### L2

- 两个进程竞争项目锁。
- 不同项目并发写 global deployment cache。
- Standalone runtime、project registry、MCP job 协作。
- IDEA/standalone Apply Changes backend 契约一致性。
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

CI 目录迁移必须定向回归现有 `CmdLineTest`，证明 CI 参数和产物语义不变。

## 10. 首期不做

- AS 插件改为 daemon 薄客户端。
- IDEA Debug attach 迁移到 standalone。
- 用 mock `Project` 模拟完整 IntelliJ service container。
- 支持多版本 standalone deployer backend。
- 用 Direct Overlay 替代完整 HOT RELOAD。
- 自动展示和选择多个 Run Configuration 候选。

## 11. 完成标准

首期完成必须同时满足：

- 不启动 IDEA 时，Python `jugg deploy` 可完成真实 HOT RELOAD。
- IDEA 和 standalone 使用共享 deploy 编排，不存在两套 recover/retry 实现。
- IDEA 和 standalone 不会并发写坏同一项目状态。
- 不同项目并发写 global deployment cache 不丢数据。
- IDEA/standalone Runtime 切换后状态可恢复。
- CI `cmd_line` 行为兼容。
- macOS、Linux、Windows 均通过独立验收。
- standalone 发行物不依赖完整 Android Studio runtime jar。
- deployer Java 实现和 installer/agent/app-server 二进制有明确版本和完整性校验。
