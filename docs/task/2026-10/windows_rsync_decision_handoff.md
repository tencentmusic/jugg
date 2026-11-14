# Windows rsync 决策与后续适配交接

日期：2026-10-16；入口更新：2026-10-18。状态：历史交接，实施以 [Windows rsync 最终适配方案](windows_rsync_final_plan.md)为准。当前顺序为先验证 oc-rsync、Cygwin 备选；正式引擎待实证，Cygwin 自构建也不是预设要求。下文保留原评估证据，具体实施和验收要求以新方案为准。尚未创建 fork、修改生产代码或执行 Windows 运行验证。

## 1. 当时记录的方向（已取代）

采用 **优先验证原生 Windows oc-rsync，保留 Cygwin rsync 备选** 的方向。此次补充评估替代此前“直接 fork rsync-win 工具包”的优先顺序，尚未认定任何候选已满足生产接入条件。

- 优先对 [oferchen/rsync（oc-rsync）](https://github.com/oferchen/rsync) 的固定版本做 Windows 实证验证，重点检查 Jugg 的真实参数、与远端标准 rsync 的互操作以及文件结果和失败契约。
- 验证通过后优先使用可追溯的上游构建；只有确需修改实现或承担独立构建维护时才 fork `oferchen/rsync`。fork 不是当前前置动作，版本、发布方式和维护归属待实测后确定。
- 若 oc-rsync 不能满足必要契约，则回到 Cygwin 工具包路线：以 [rn7s2/rsync-win](https://github.com/rn7s2/rsync-win) 为打包参考，直接调用原始 `rsync.exe`，不接入其 Rust 包装器。确认许可与复用价值后再决定 fork 包装仓库或独立编写打包逻辑。
- `robosync` 不具备本任务需要的 rsync 协议远端传输能力；`gokrazy/rsync` 缺少必要参数与过滤语义，本轮均不作为接入候选。
- 无论选哪条路线，工具分发负责构建或获取、依赖收集、版本与校验值、许可及对应源码材料、Windows 验证和发布；Jugg 负责资源准备与探测、命令和路径适配、SSH 参数、结果处理及取消，复用现有同步和远端编译流程。
- Cygwin 是选型备选，不新增运行时自动切换引擎或失败后换引擎重传的机制。原仓库已有二进制不直接成为正式依赖；需要核对选定版本的补丁状态、组件许可与依赖闭包。

本次仅更新方案，不创建 fork、不发布工具包、不进行生产代码适配。

## 2. 已取得的评估证据

### 2.1 rsync-win 的能力与限制

核对对象为仓库 README、发布记录、`Cargo.toml`、`src/main.rs` 及 `cygwin64` 文件清单。v0.1.3 源码也已单独读取。

| 证据 | 对接入的含义 |
|---|---|
| 工具由 Rust 包装器和 Cygwin rsync、SSH、cygpath、DLL 组成 | 可参考便携工具包组织方式，不能当作无运行库依赖的原生单文件 rsync |
| 包装器使用 `--src`、`--dest`，只解析固定选项 | 不是标准 rsync CLI 的透明替代品 |
| 缺少 `--include`、`--copy-unsafe-links`、`--delete-excluded`、`--prune-empty-dirs`，exclude 为单值字段 | 无法表达 Jugg 当前完整同步规则 |
| `.output()` 返回后丢弃子进程状态 | 子进程已启动但传输失败时，包装器可能正常退出，破坏同步失败契约 |
| 通过参数数组传入包含单引号的 `--exclude='模式'` | 静态判断会把引号带进匹配模式，不能照搬 |
| SSH 固定 `StrictHostKeyChecking=no`、`IdentitiesOnly=yes` 和单个 identity | 不应覆盖 Jugg 现有 `accept-new` 及认证参数语义 |
| 最新发布为 2025-08-10 的 v0.1.3；仓库于 2026-05-16 归档 | 后续工具升级与缺陷维护需要自行承担 |

v0.1.3 标签下的 `cygwin64/rsync.exe` 静态检查结果：

- PE machine 为 `0x8664`，即 x86-64；大小 2,178,666 字节。
- 可打印字符串中存在 `3.3.0`。
- SHA-256：`561580b0be4b50c5ba00d992d615496131aa95faaa73e2b3e4269dc6c38e84f8`。
- 这不是 Windows 上执行 `--version` 的结果，也不能证明其安全补丁状态或 Release ZIP 与仓库文件一致。
- 原仓库根目录与 Cargo 元数据中未见包装器的明确许可证；附带的 `LICENSE for Cygwin.txt` 只有官网链接。工具包中的组件仍需逐项核对许可和源码提供要求。

外部依据：

- [v0.1.3 源码](https://github.com/rn7s2/rsync-win/blob/v0.1.3/src/main.rs)
- [发布记录](https://github.com/rn7s2/rsync-win/releases)
- [Cargo 元数据](https://github.com/rn7s2/rsync-win/blob/master/Cargo.toml)
- [工具包文件](https://github.com/rn7s2/rsync-win/tree/master/cygwin64)
- [检查的 rsync.exe](https://github.com/rn7s2/rsync-win/blob/v0.1.3/cygwin64/rsync.exe)
- [Cygwin 许可说明](https://cygwin.com/licensing.html)
- [rsync 上游更新记录](https://download.samba.org/pub/rsync/NEWS)

### 2.2 新增候选比较

本轮读取了各项目的 README、版本资料和相关实现，未在 Windows 执行候选二进制。以下结论区分项目声明、源码证据与仍需实测的行为。

| 项目 | 与 Jugg 需求的关系 | 当前决策 |
|---|---|---|
| `oferchen/rsync` / oc-rsync | Rust 原生实现，提供 Windows x64 产物和 rsync 协议支持；源码包含关键选项的配置链路 | 优先 PoC，不能据此认定可直接替换 |
| `roethlar/robosync` 1.0.10 | 面向本地或已挂载文件系统，采用类似 robocopy 的参数；不提供 Jugg 需要的 rsync wire protocol 与 SSH 远端端点 | 排除远端编译同步场景，不为本地备份额外引入第二套引擎 |
| `gokrazy/rsync` | Go 实现，有 Windows 支持，但实际 CLI 表未开放若干必要选项，发送端通配符过滤仍未实现 | 排除当前接入，补齐成本超出 Windows 适配边界 |

#### oc-rsync：有条件优先，固定版本后验证

- [v0.6.4 发布](https://github.com/oferchen/rsync/releases/tag/v0.6.4)提供 Windows x64 产物，项目采用 GPL-3.0-or-later。原生实现可避免 Cygwin 运行库与路径转换，但不能据此假定无其他运行时依赖。
- [v0.6.4 README](https://github.com/oferchen/rsync/blob/v0.6.4/README.md)声明协议 28–32、参考 rsync 3.4.4；本轮读取的 master 声明协议 28–33、参考 3.5.1。master 的改进不能当作已发布产物的能力。源码复核快照为 `d6046b4180d906733f63908c36778a41228fad2d`；后续须记录实际选定提交、产物和 SHA-256。
- [CLI 配置链路](https://github.com/oferchen/rsync/blob/d6046b4180d906733f63908c36778a41228fad2d/crates/cli/src/frontend/execution/drive/config.rs)包含 `copy_unsafe_links`、`delete_excluded`、`prune_empty_dirs` 等选项，但参数被解析和传递不等于 Windows 行为已验证。[unsafe-links 测试](https://github.com/oferchen/rsync/blob/d6046b4180d906733f63908c36778a41228fad2d/crates/transfer/tests/rsh_server_sender_copy_unsafe_links.rs)受 `cfg(unix)` 限制，不能证明 Windows 链接语义。
- [Windows 支持矩阵](https://github.com/oferchen/rsync/blob/v0.6.4/docs/user/windows-support-matrix.md)将 Windows 列为 Tier 2，说明权限、属主及链接能力存在平台差异；文件符号链接创建还受到 Windows 权限限制。需分别验证上传展开和拉取创建，不能只检查文件内容。
- [Windows 互操作工作流](https://github.com/oferchen/rsync/blob/d6046b4180d906733f63908c36778a41228fad2d/.github/workflows/_interop-windows.yml)为 best-effort、允许失败，使用 MSYS2 上游 daemon 覆盖部分传输与过滤场景，并跳过链接及 SSH loopback 等项目；不能替代 Windows → Linux/macOS 的 Jugg SSH 端到端验收。
- SSH 路径需要额外核对。[该源码快照 README](https://github.com/oferchen/rsync/blob/d6046b4180d906733f63908c36778a41228fad2d/README.md)区分标准 `host:path` 配合外部 SSH 与自定义 `ssh://` 的内嵌 SSH，且后者不能混用 `-e` / `RSYNC_RSH`；v0.6.4 的相关描述不同。不得将“带内嵌 SSH”推导为 Jugg 现有调用无需外部 SSH。以固定版本实际实现和命令结果为准。

#### robosync：本地同步工具，不能替代远端 rsync

[crate 1.0.10](https://crates.io/crates/robosync/1.0.10)采用 MIT 许可。[项目 README](https://github.com/roethlar/robosync)要求网络位置先通过 SMB、NFS 或 SSHFS 等挂载；[CLI 实现](https://docs.rs/crate/robosync/1.0.10/source/src/main.rs)将源和目标作为本地 `PathBuf` 处理。其 `--mir`、`--xf`、`--xd` 及 `-e` 等参数含义与 Jugg 的 rsync 命令不同，`-e` 也不是 SSH remote shell。差分复制算法不能证明 rsync 协议互操作；改用它将改变传输和部署前提，超出此次适配范围。

#### gokrazy/rsync：缺少现有同步契约

源码复核快照为 `076039b65f53338107e0678dc46a749e5a674b77`，项目采用 BSD-3-Clause 许可。虽然 README 和 CI 包含 Windows 支持，但以下实现限制直接影响 Jugg：

- [主入口](https://github.com/gokrazy/rsync/blob/076039b65f53338107e0678dc46a749e5a674b77/internal/maincmd/maincmd.go)实际使用 `NewOptionsWithGokrazyDefaults`；[对应选项表](https://github.com/gokrazy/rsync/blob/076039b65f53338107e0678dc46a749e5a674b77/internal/rsyncopts/rsyncopts.go)中的 `--copy-unsafe-links`、`--delete-excluded`、`--prune-empty-dirs` 被注释为未实现。文件中另一套选项表不能作为默认 CLI 已支持的证据。
- [发送端过滤实现](https://github.com/gokrazy/rsync/blob/076039b65f53338107e0678dc46a749e5a674b77/internal/sender/exclude.go)遇到通配符规则会进入未实现的 panic 分支；Jugg 现有 `*/`、`**`、`*` 等规则不能直接复用。
- [协议常量](https://github.com/gokrazy/rsync/blob/076039b65f53338107e0678dc46a749e5a674b77/consts.go)为 27，低于 Jugg 当前 protocol 30 的探测下限。这不表示其无法与新版 rsync 协商，但放宽探测也无法解决前述功能缺口。

### 2.3 Jugg 当前适配边界

以下路径均相对 `main/src/main/java/com/sickworm/intellij/jugg/`。后续实施前重新核对当前代码，不依赖固定行号。

| 代码入口 | 当前行为与适配要求 |
|---|---|
| `gradle/compile/RsyncCompatibleHelper.kt` | Windows 初始化直接判定不兼容；需要按实际工具能力探测 |
| `gradle/compile/RsyncCommand.kt` | 拼接 Unix 单引号、SSH remote shell 和 `mkdir -p`；需要适配参数边界、本地目录准备和路径 |
| `gradle/compile/SshCommand.kt` | 生成上传、产物拉取与本地 classpath 同步过滤规则；必须保留规则顺序和传输根语义 |
| `gradle/compile/CmdExecutor.kt` | Windows 走 `cmd.exe /c`；需要密码交互的 rsync 分支会抛不支持异常；需接入实际进程结果并验证取消子进程 |
| `gradle/compile/ISshCommand.kt` | 当前通过 shell 完成标记解析结果；直接启动进程时不能继续假定存在该标记 |
| `gradle/compile/RemoteGradleCompileClient.kt` | JSch 远端登录与外部 rsync/SSH 是不同认证链；现有进程结果归一化不能掩盖新分支失败 |
| `ide/bean/JuggSettings.kt` | Windows 本地 backup classpath 被禁用；不能仅因远端同步可用就直接开放 |

## 3. 后续实施原则与候选验证范围

优先以 Windows x64、SSH 密钥认证和 `rsync_simple` 单工程同步验证端到端链路。先以 Linux 为远端跑通，再验证 macOS；当前不宣称两者已支持。多工程 `rsync`、本地 classpath 备份需分别验收；密码交互、Windows ARM64 和 Windows 远端不纳入最小验证范围。最终发布范围以实测结果确定。

实现保持最小边界：

1. Windows rsync 优先使用参数数组启动进程；本地目录创建交给 Java/Kotlin，避免依赖 `cmd.exe` 的 Unix 引号和 `mkdir -p`。
2. 按最终选定工具处理路径：原生 oc-rsync 使用经实测接受的 Windows 路径，仅 Cygwin 工具需要转换为 Cygwin 路径；私钥路径遵循实际 SSH 可执行文件的路径规则。保留 `user@host:/path` 的远端语义，不把过滤 pattern 当本地文件路径转换。验证 `-e` 内部 SSH 参数的独立转义要求。
3. 保留多条 include/exclude 的顺序、删除策略、mtime 比较和链接语义。源码上传使用 `--copy-unsafe-links`：传输树外相对链接及绝对链接展开，内部相对链接保留。排除规则相对实际传输根生效，不能退回旧方案的“始终相对 IDE 项目根”口径。
4. 优先以现有标准远端语法和外部 SSH 路线验证，使用明确的 SSH 可执行文件与密钥路径，保留 `StrictHostKeyChecking=accept-new`，验证非默认端口和 known_hosts 所在位置。JSch 登录成功不能代替 rsync 使用的 SSH 认证验证；若考虑内嵌 SSH，必须另行证明认证、主机校验和取消契约等价，不在本轮默认切换。
5. 真实非零退出码必须使同步失败，阻断后续构建或增量状态初始化；取消不能遗留持续传输的 rsync/SSH 子进程。复用既有日志脱敏、监听与适用的重试契约。
6. Mac/Linux 与 iFT 的正常路径保持原行为。不为一个平台适配引入通用框架或重复维护过滤规则。

本文件不预先规定新增类名、所有参数生成器的重构方式、资源目录布局或工期。先确认实际失败边界，再决定必要改动。

## 4. 下一会话的执行顺序

1. 读取本文件、仓库必读文档及当前代码，检查工作区状态，提取 Jugg 实际上传、拉取参数和过滤规则。
2. 固定 oc-rsync 候选版本及源码提交，核对该版本的参数、SSH 路线、已知修复与发布产物，记录来源、SHA-256、许可和对应源码。不以 master 文档替代发布版本证据，也不以 rsync 3.0/protocol 30 的旧探测下限代替安全版本选择。
3. 在干净 Windows x64 环境先做独立 PoC：记录程序与 SSH 版本、运行时依赖和实际包体；对同一测试树使用 Jugg 参数，连接 Linux 上游 rsync，并与标准 rsync 的目的端结果比较。先覆盖第 5 节的文件集合、链接、路径、SSH 和进程失败行为，再判定能否进入 Jugg 接入；完整编译和部署 Flow 在接入后验收，macOS 远端随后单独验收。
4. 根据 PoC 决定来源：通过则优先使用固定上游构建；有必要且范围可控的补丁才 fork oc-rsync；若必要契约仍不能满足，记录失败证据并验证 Cygwin 备选。采用 Cygwin 时确认打包仓库许可和维护方式，并在未安装 Cygwin 的 Windows 上验证依赖闭包。不要先创建 fork 再倒推选型。
5. 取得当前 Jugg 在 Windows 上的失败证据，再按 [06_testing.md](../../ai_knowledge/06_testing.md) 判断测试价值并定位已有 owner。通过门禁的行为先写失败测试，不为固定内部类或调用方式增加测试。
6. 完成选定工具的最小调用适配，执行下列验证；涉及 compile/deploy 编排时包含 L3 或已有等价 Flow 回归。
7. 将选型理由、实际支持边界、测试命令、结果与未验证项回写本文，再按实际改动更新知识库和直接受影响的 Wiki 页面。

## 5. 验证要求与完成边界

| 范围 | 必须取得的证据 |
|---|---|
| 基本传输 | 源码上传、远端编译、APK/classpath 拉取可用；第二次无变化同步不产生意外更新 |
| 文件集合 | 多条 include/exclude、必需配置文件放行、删除及 prune 语义正确；失败不污染有效状态 |
| 链接 | 外部相对链接、内部相对链接、绝对链接、缺失目标符合当前上传契约；Windows 实际链接类型与权限明确 |
| 路径与属性 | 中文、空格、跨盘路径、私钥路径及目录尾部 `/`；mtime、权限、可执行位和 Windows 链接创建行为 |
| SSH | 正确密钥、非默认端口、首次 host key、错误密钥、远端缺少 rsync；保持主机校验策略 |
| 失败与取消 | 断网、部分传输、非零退出码、用户取消均可判定；不误报成功、不遗留传输子进程 |
| 回归 | 未命中 Windows 分支的原有同步行为；实际覆盖的远端编译及部署 Flow |
| 独立能力 | 本地 backup classpath、多工程模式分别验证，通过前不因兼容探测成功而顺带启用 |

PoC 使用可重建的测试目录，以标准 rsync 的文件集合、内容、链接、删除结果及退出状态作为对照；Windows 无法表达的 POSIX 属性单列为支持边界，不能静默视为等价。版本输出与帮助中出现选项仅作为初筛，不能替代行为验收。涉及 `--delete` 的失败场景每轮恢复测试目录，不用自动切换引擎重传来掩盖部分更新。

优先检查现有 `SyncFileCommandTest`、`FetchClasspathCommandTest`、`BaseSshCommandTest`、`RsyncAuthRetryPolicyTest` 及远端 Flow 的覆盖责任，测试文件名和层级以实际代码为准。参数断言不能替代真实目的端内容、链接类型、删除结果和失败退出码的验证。无 Windows 环境时可以推进实现与其他平台回归，但不得宣称 Windows 已验证完成。

## 6. 与旧方案及知识库的关系

- [7 月方案](../2026-07/windows_cygwin_rsync_integration_plan.md) 保留为 Cygwin 备选的历史参考。当前先验证 oc-rsync，再决定是否 fork 及采用哪条路线；旧方案中的包体、工期、具体类设计、无条件 TDD 和 Wiki 页面选择不作为当前承诺。
- [远端源码链接同步修复](remote_source_symlink_sync.md) 是必须保留的现有行为依据。
- 已读取知识库：[概览](../../ai_knowledge/00_overview.md)、[索引](../../ai_knowledge/99_index.md)、[代码地图](../../ai_knowledge/98_code_map.md)、[项目工程 §5](../../ai_knowledge/04_engineering_project.md)、[工具与配置](../../ai_knowledge/05_utilities.md)、[验证策略](../../ai_knowledge/06_testing.md)。
- 本次只记录后续决策，不改变现有支持范围，因此不把规划写成知识库已实现能力，也不修改 Wiki。功能落地后按实际行为定位受影响页面，英文页面遵循严格镜像要求。

## 7. 本次交付状态

- 已完成：四个外部项目与 Jugg 源码静态评估、rsync-win 标签下二进制静态检查、候选优先级与验证门槛更新、决策与交接文档、旧方案入口关联。
- 未完成：fork、工具包构建与发布、生产代码适配、Windows 实测、完整远端编译与部署验收。
- 本次验证：文档差异、相对链接与工作区改动范围检查；纯文档记录不新增自动化测试，不执行 Gradle 构建。
