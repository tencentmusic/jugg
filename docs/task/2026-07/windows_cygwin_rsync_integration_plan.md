# Windows 内置 Cygwin rsync 接入方案

## 1. 背景

Jugg 的 Remote Gradle Compile 已支持 `rsync_simple`、`rsync` 与 `iFT` 三种同步模式，但当前 Windows 会被直接判定为不支持 rsync，用户只能使用 iFT。

调研 `rn7s2/rsync-win` 后确认：

- `rsync-win.exe` 只是参数受限的 Rust 包装器，不能透明替代标准 rsync。
- 其内置的 `cygwin64/rsync.exe` 是真正的 rsync 3.3.0，可以支持 Jugg 已使用的 include/exclude、delete、SSH remote shell 等标准能力。
- Windows 用户不需要安装完整 Cygwin；只要随 Jugg 分发 rsync、SSH 与所需 Cygwin DLL 即可。

因此，本方案不接入 `rsync-win.exe`，而是由 Jugg 内置并直接调用经过审核的官方 Cygwin rsync 最小运行时。

## 2. 目标

首期目标：

1. Windows x64 用户无需安装 Cygwin 即可使用 `rsync_simple` Remote Gradle Compile。
2. 保持 Mac/Linux 现有 rsync 行为不变。
3. 保持 Jugg 已有 rsync include/exclude、删除、错误码、取消和日志语义。
4. 首期支持 SSH key 登录和 Linux 远端编译机。
5. 内置运行时来自官方 Cygwin 包，具备可追溯版本、哈希、许可证与对应源码。
6. 不静默回退到 iFT；运行时或配置异常时给出明确错误，由用户决定是否切换同步模式。

## 3. 非目标

首期不处理：

- Windows ARM64。
- SSH 密码登录。
- 多工程 `rsync` 模式。
- Windows 本地 Gradle backup classpath。
- Windows 远端编译机；首期只支持 Linux 远端。
- 运行时在线下载与自动更新。
- 复用或 Fork `rsync-win.exe` Rust 包装器。
- 自动把现有 Windows iFT 配置迁移为 rsync。

## 4. 最终方案

### 4.1 运行时来源

不要直接复制 `rn7s2/rsync-win` Release 中的二进制。正式产物应从官方 Cygwin package/source package 获取，或由项目受控构建流程生成。

引入前必须固定：

- Cygwin 版本。
- rsync 版本。
- OpenSSH 版本。
- 每个 EXE/DLL 的 SHA-256。
- 官方二进制下载地址或构建来源。
- 对应 source package、许可证与 NOTICE。

rsync 必须满足当前 `RsyncCompatibleHelper` 的最低要求：

- rsync 3.0 或更高。
- protocol version 30 或更高。

### 4.2 首期运行时组成

首期建议同时内置 Cygwin SSH，避免依赖 Windows Optional Feature 中的系统 OpenSSH。

参考 `rsync-win v0.1.3` 的依赖闭包，运行时包含：

```text
rsync.exe
ssh.exe
cygpath.exe
cygwin1.dll
cygcrypto-3.dll
cygcom_err-2.dll
cyggcc_s-seh-1.dll
cyggssapi_krb5-2.dll
cygiconv-2.dll
cygintl-8.dll
cygk5crypto-3.dll
cygkrb5-3.dll
cygkrb5support-0.dll
cyglz4-1.dll
cygxxhash-0.dll
cygz.dll
cygzstd-1.dll
```

实际文件集必须以最终选定官方版本的静态依赖和 Windows 运行验证为准，不能假设不同版本具有相同依赖。

### 4.3 包体

基于 `rsync-win v0.1.3` 运行时实测：

| 方案 | 插件压缩增量 | 解压后磁盘占用 |
|------|-------------:|----------------:|
| rsync 最小集，依赖系统 OpenSSH | 约 4.71 MiB | 约 10.72 MiB |
| rsync + Cygwin SSH 完整依赖闭包 | 约 5.72 MiB | 约 13.05 MiB |

当前 Jugg 插件压缩包约 127.74 MiB。采用内置 Cygwin SSH 后，预计包体增加约 4.5%。最终数字以选定官方 Cygwin 版本重新测量为准。

### 4.4 资源目录

建议资源目录带明确版本，避免覆盖旧文件：

```text
main/src/main/resources/tools/windows/rsync/<runtime-version>/
├── rsync.exe
├── ssh.exe
├── cygpath.exe
├── *.dll
├── checksums.sha256
└── licenses/
```

运行时复制到：

```text
~/.jugg/resources/tools/windows/rsync/<runtime-version>/
```

复用 `JuggGlobalPathManager.resourceFile()` 管理全局资源，不写入具体工程的 `build/jugg`。

## 5. 技术设计

### 5.1 Windows rsync runtime 准备

新增内部对象 `WindowsRsyncRuntime`，职责保持单一：

1. 仅在 Windows x64 初始化。
2. 按固定清单通过现有 resource copy 机制复制 EXE/DLL。
3. 校验文件 SHA-256；缺失或不一致时重新从插件资源复制。
4. 返回 `rsync.exe`、`ssh.exe`、`cygpath.exe` 的稳定绝对路径。
5. 执行一次 `rsync --version` 兼容性检查。

该对象不负责构造同步命令、SSH 登录或 Remote Gradle Compile 编排。

### 5.2 使用结构化进程参数

Windows rsync 不继续通过 `cmd.exe /c` 拼接完整命令字符串，而是通过 `ProcessBuilder(List<String>)` 启动。

原因：

- `cmd.exe` 不把单引号作为 quoting 字符。
- include/exclude 参数顺序和边界必须保持准确。
- 工程路径和 SSH key 可能包含空格、中文及特殊字符。
- Cygwin rsync 的 `-e` 参数本身包含一个完整 remote shell 字符串。

实现约束：

- 只为 Windows `RsyncCommand` 增加结构化执行分支，不重写全部 SSH/Gradle 命令执行体系。
- Mac/Linux 继续沿用现有 Bash/expect 逻辑，降低回归范围。
- Windows 进程结束后直接读取真实 `process.exitValue()`，不依赖 shell 拼接的 Jugg result echo。
- stdout/stderr、取消和 `RsyncAuthRetryPolicy` 继续复用现有监听与重试规则。

### 5.3 rsync 参数结构化

将 rsync 参数的生产口径收敛为 `List<String>`，再按不同消费者渲染：

- Windows Cygwin rsync：直接传给 `ProcessBuilder`。
- Mac/Linux rsync：按现有 Bash 规则安全渲染为字符串。
- iFT：将参数列表渲染为 iFT `-a` 所需的单个参数字符串。

必须保持以下规则及顺序不变：

- Jugg 必需 include 位于用户 exclude 之前。
- 支持多个 include/exclude。
- 支持 `--delete`、`--delete-excluded`、`--prune-empty-dirs`。
- Additional exclude patterns 仍相对当前 Jugg project root。

不要在 Windows 分支重新维护一份独立过滤规则。

### 5.4 路径转换

本地 Windows 路径转换为 Cygwin 路径：

```text
C:\work\demo -> /cygdrive/c/work/demo
```

需要转换：

- 本地源码目录。
- APK/classpath 本地目标目录。
- Cygwin SSH 使用的 identity 文件路径。

禁止转换：

- `user@host:/data/project` 形式的远端路径。
- rsync include/exclude pattern。
- 远端 Linux 命令与工作目录。

优先调用同版本 `cygpath.exe -u`，避免在 Kotlin 中复制 Cygwin 路径规则。路径转换结果应缓存到单次命令对象，避免同一命令重复启动 `cygpath.exe`。

### 5.5 SSH

Windows rsync 使用内置 `ssh.exe` 作为 remote shell：

```text
-e "<ssh.exe> -p <port> -o StrictHostKeyChecking=accept-new ..."
```

要求：

- 保持 `StrictHostKeyChecking=accept-new`，禁止降级为 `no`。
- 首期只允许 SSH key，不进入 expect/sshpass 密码流程。
- identity 路径必须转为 Cygwin 路径。
- 延续现有 SSH port 和 key 参数。
- 远端必须已安装兼容版本 rsync；缺失时输出明确错误。

当 Windows 用户配置的是密码而不是 key 时，应在启动同步前给出用户可见错误，提示首期仅支持 SSH key，不要在执行阶段抛出模糊的“不支持 Windows rsync”。

### 5.6 本地目录创建

`RsyncFetchOutputCommand` 和 `RsyncFetchClasspathCommand` 当前使用 `mkdir -p` 创建本地目录。Windows 分支必须改为 Kotlin `File.mkdirs()`，成功后再启动 rsync。

不要将 Windows `mkdir` 拼进 rsync 命令。

### 5.7 兼容性检测

调整 `RsyncCompatibleHelper`：

- Windows x64：优先使用内置 Cygwin rsync 并执行版本检测。
- Mac：保持当前内置 rsync 优先逻辑。
- Linux：保持系统 rsync 检测逻辑。
- Windows ARM64：返回不兼容，并提供明确提示。

首期只开放 Remote Gradle Compile 的 `rsync_simple`。`JuggSettings.isCanUseBackupClasspath` 在 Windows 仍保持关闭，直到独立完成本地 classpath backup 验证。

### 5.8 错误、日志与取消

遵循现有 `JuggLogger` 规范：

- `warn`：用户可见的运行时缺失、哈希失败、SSH key 不支持、远端 rsync 缺失、同步失败。
- `info`：关键流程，例如首次准备 Windows rsync runtime。
- `debug`：实际 executable、版本检测结果、经过脱敏的参数和退出码。

其他要求：

- 日志中不能输出密码或私钥内容。
- 进程取消必须销毁当前 rsync 进程，并验证不会遗留持续传输的子进程。
- 保留 rsync 255 认证失败的现有重试判断。
- 不把非零退出码归一化为成功。

## 6. 代码改造点

| 文件 | 改造内容 |
|------|----------|
| `main/.../gradle/compile/WindowsRsyncRuntime.kt` | 新增内部 runtime 清单、复制、哈希、版本与 executable 定位 |
| `main/.../gradle/compile/RsyncCompatibleHelper.kt` | Windows x64 使用内置 rsync；保留其他平台逻辑 |
| `main/.../gradle/compile/RsyncCommand.kt` | 构造结构化 rsync/SSH 参数，转换 Windows 本地路径 |
| `main/.../gradle/compile/SshCommand.kt` | rsync filter 参数改为统一列表并按消费者渲染；本地目录创建移出 shell |
| `main/.../gradle/compile/CmdExecutor.kt` | Windows `RsyncCommand` 使用 `ProcessBuilder` 和真实退出码 |
| `main/.../gradle/compile/RemoteGradleCompileClient.kt` | 首期 Windows `rsync_simple` 入口校验和明确错误提示 |
| `main/.../ide/bean/JuggGradleCompileOptions.kt` | 校验 Windows 首期仅支持 `rsync_simple` + SSH key |
| `main/.../ide/bean/JuggSettings.kt` | 保持 Windows backup classpath 关闭，更新过时注释 |
| `main/src/main/resources/tools/windows/rsync/...` | 新增官方 Cygwin runtime、校验清单和许可证材料 |

如实现过程中发现无需修改某个文件，应删除对应改造，不为保持表格完整而增加无必要代码。

## 7. TDD 执行顺序

本功能影响 Remote Gradle Compile 用户主链路，必须先写失败测试，再修改生产代码。

### 7.1 测试文件与层级

| 层级 | 文件 | 覆盖内容 |
|------|------|----------|
| L1 | `main/src/test/.../gradle/compile/RsyncCommandTest.kt` | Windows executable/SSH/路径参数推导；本地与远端路径区分；参数边界 |
| L1 | `main/src/test/.../gradle/compile/SyncFileCommandTest.kt` | include/exclude 顺序、Additional exclude、Windows 渲染保持相同行为 |
| L1 | `main/src/test/.../gradle/compile/FetchClasspathCommandTest.kt` | delete、delete-excluded、prune-empty-dirs 与多个 include |
| L1 | `main/src/test/.../gradle/compile/RsyncAuthRetryPolicyTest.kt` | 真实退出码接入后 255 重试语义不变 |
| L3 等价远端 Flow | `idea/src/test/.../gradle/RemoteGradleCompileClientTest.kt` | Windows 真实 demo 上传、远端编译、APK/classpath 拉取和取消 |
| L3 | `idea/src/test/.../manager/TopLevelFlowTest.kt` | 增加或复用 Windows Remote Gradle Compile 后部署成功场景 |

`RsyncCommandTest` 属于确定性命令派生，可作为 L1。不要为每个新增内部对象分别创建单文件 Mockito 测试。

### 7.2 TDD 顺序

1. 在 `RsyncCommandTest` 写 Windows 结构化参数、路径和 SSH key 的失败测试。
2. 在现有 `SyncFileCommandTest`、`FetchClasspathCommandTest` 追加参数列表和顺序测试。
3. 在 `RemoteGradleCompileClientTest` 明确 Windows real remote 配置与期望行为。
4. 在执行清单中记录上述测试路径和层级。
5. 再实现 runtime、参数、进程执行和配置校验。
6. 先跑 L1，再跑真实 Windows remote flow，最后跑 TopLevel Flow 回归。

### 7.3 定向测试命令

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.compile.RsyncCommandTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.compile.SyncFileCommandTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.compile.FetchClasspathCommandTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.compile.RsyncAuthRetryPolicyTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.gradle.RemoteGradleCompileClientTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowTest"
./gradlew :idea:compileKotlin
```

禁止无 `--tests` 过滤地运行全量 `:main:test` 或 `:idea:test`。

## 8. Windows 真实环境验收矩阵

首期发布前至少验证：

| 场景 | 预期 |
|------|------|
| Windows x64 首次使用 | 自动准备 runtime，无需安装 Cygwin |
| 再次使用 | 复用已校验 runtime，不重复复制 |
| 工程路径包含空格 | 上传、编译、拉取成功 |
| 工程路径包含中文 | 上传、编译、拉取成功 |
| 非 22 SSH port | 使用配置端口成功连接 |
| SSH key 路径包含空格 | 成功转换路径并连接 |
| 首次连接新主机 | `accept-new` 保存 host key 后继续 |
| 多个 include/exclude | 文件集合与 Mac/Linux 一致 |
| 删除本地源码 | 远端旧文件按现有 `--delete` 语义删除 |
| Additional exclude | 排除规则相对当前工程生效 |
| APK 拉取 | 返回正确 APK，退出码为 0 |
| classpath 拉取 | include、delete-excluded 和 prune 行为正确 |
| 用户取消 | rsync 进程结束，编译返回 canceled |
| SSH 认证失败 | 返回非零退出码，按现有规则重试 |
| 远端缺少 rsync | 用户可见错误包含明确安装提示 |
| runtime 文件损坏 | 哈希检测失败后重新复制或明确终止 |
| 配置密码登录 | 编译前提示首期仅支持 SSH key |
| Windows ARM64 | 明确提示当前不支持，不尝试启动 x64 runtime |

## 9. 安全与许可证

这是正式发布前的阻断项，不得在实现完成后补做。

1. rsync 3.x 使用 GPLv3，必须满足对应二进制分发和源码提供义务。
2. Cygwin runtime 与各 package 有各自许可证，必须按官方 package metadata 汇总。
3. 发布包必须包含必要许可证与 NOTICE。
4. 必须保存与二进制完全对应的 source package 或提供满足许可证要求的源码获取方式。
5. 二进制来源和 SHA-256 必须进入仓库内可审计清单。
6. 对所有 EXE/DLL 执行安全扫描；确认版本没有已知高风险漏洞。
7. 不使用无明确授权的 `rsync-win.exe` 包装器代码或发布资产作为正式依赖。

如法务或安全审核未通过，功能不得随正式插件发布，可继续保留 Windows iFT 方案。

## 10. 文档同步

功能落地后至少同步：

- `docs/ai_knowledge/05_utilities.md`：Windows rsync runtime、支持边界与排查入口。
- `docs/ai_knowledge/98_code_map.md`：新增 runtime 入口类。
- `docs/wiki/zh/onboarding/agent-setup.md`：Windows 可选择 `rsync_simple`，说明 SSH key、x64 与 Linux 远端限制。
- 对应英文 Wiki 页面，如当前存在或发布流程要求中英文同步。

文档不得继续保留“Windows 不支持 rsync，必须使用 iFT”的旧结论。

## 11. 实施阶段与成本

### 阶段一：合规与二进制准备

- 确定官方 Cygwin/rsync/OpenSSH 版本。
- 生成依赖闭包、哈希、许可证和 source package 清单。
- 实测最终压缩与解压体积。

### 阶段二：最小 PoC

- Windows x64。
- `rsync_simple`。
- 单 SSH key。
- Linux 远端。
- 完成源码上传和 APK 拉取。

预计 2～3 人日，不包含合规审核等待时间。

### 阶段三：主链路完成

- classpath 拉取。
- 完整 include/exclude/delete 语义。
- 取消、退出码、重试和错误提示。
- L1 与真实 Remote Gradle Compile Flow 全通过。

累计预计 5～8 人日。

### 阶段四：后续增强

- 多工程 `rsync`。
- 多 key 与密码认证。
- Windows backup classpath。
- Windows ARM64。

不与首期绑定，完整对齐累计预计 8～12 人日。

## 12. 回滚方案

- 不修改现有 iFT 数据结构和同步行为。
- Windows 用户可在 Run Configuration 中切回 iFT。
- Mac/Linux rsync 继续使用原实现路径。
- Windows runtime 初始化失败时终止本次 remote compile，不自动切换同步模式。
- 如正式发布后发现兼容问题，可在后续版本重新禁止 Windows `rsync_simple`，无需迁移工程数据。

## 13. 完成标准

满足以下条件才视为首期完成：

1. Windows x64 无 Cygwin 安装环境可以完成真实 demo 的远端编译和部署。
2. `rsync_simple` 源码上传、APK/classpath 拉取、删除和 exclude 语义与 Mac/Linux 一致。
3. SSH key、非默认端口、空格/中文路径可用。
4. 取消、失败退出码和认证重试行为正确。
5. 所有定向测试通过，并完成 Windows 真实环境矩阵。
6. 官方二进制来源、哈希、许可证和 source package 通过审核。
7. AI 知识库与用户 Wiki 已同步。
