# Windows rsync 最终适配方案

> 当前方案已统一为 Cygwin + rsync，移除范围和最新验证见 [Cygwin 收敛记录](windows_rsync_cygwin_only.md)。本文双方案部分保留为历史取舍证据。

Mac 真实工程验证及后续兼容修正以 [Windows → macOS 真实工程验收](windows_rsync_mac_real_acceptance.md) 为准，包括 BSD find、wrapper 执行位和 openrsync 的已知过滤兼容边界。

2026-10-19 平台条件修正：本文移除 `--copy-unsafe-links` 的决定仅适用于 Windows；macOS/Linux 保留该参数和树外目标复制行为。此前共享参数生成处无平台条件，导致非 Windows 行为改变，本次在该 owner 局部修正。复用 SyncFileCommandTest（L1 参数契约及 Unix 本地 rsync）、WindowsRsyncRuntimeTest 和现有模拟 Flow；定向测试 11 项通过、4 项 Unix 专用用例在 Windows 跳过，oc-rsync 密钥模拟 Flow 4 项通过。未重新执行 Unix 实际传输。Wiki 的相关说明位于 Windows 小节，含义仍准确，无需修改。

创建：2026-10-16；修订：2026-10-19。当前实现以维护者最新要求为准：去掉 `--copy-unsafe-links`，支持完整 rsync、多工程与密码；默认内置 oc-rsync 0.6.4，Settings 临时开关选择本机 Cygwin，Cygwin 缺失仅提示安装。两种候选的密钥/密码独立传输矩阵已通过，新增 5 轮 benchmark。服务器配置与定向命令见 [测试准备](windows_rsync_test_setup.md)，证据与剩余边界见 [验收记录](windows_rsync_acceptance.md)。

早期本文的单引擎、仅 rsync_simple/私钥以及树外链接展开约束已被本轮明确要求替代；下文保留选型背景，不作为当前实现的额外指令。服务器资源不足，继续使用脚本生成小产物，不执行真实远端 Gradle、大产物下载或设备部署。

本文是 [10 月交接](windows_rsync_decision_handoff.md)的当前决策入口。10 月 16 日曾将 Cygwin 自构建路线定为正式方案；复核后确认，该结论没有足够的 Windows 对照证据，现修正为原生 oc-rsync 优先验证。[7 月方案](../2026-07/windows_cygwin_rsync_integration_plan.md)仅保留为历史参考，不恢复其旧版本下限、包体估算、工期、类设计和测试承诺。

## 1. 最终决策

**先对固定发布版 oc-rsync 做 Jugg 场景验证；满足必要契约后优先接入原生实现。Cygwin 上游 rsync 作为选型备选，不预先投入自构建。**

- 优先复用可追溯的发布产物，先验证实际行为和运行依赖，不先 fork 或重写 Jugg 调用体系。
- 不接入 `rsync-win.exe` 包装器，不复用其旧二进制包，不 fork 其归档仓库。
- 只有 oc-rsync 出现必要契约失败且修补代价不合适时，才验证 Cygwin 备选；当前提供 Settings 显式选择两种引擎；失败不自动换引擎重传。
- Cygwin 路线优先审核官方二进制。只有确认所需修复或能力缺失、且没有满足条件的官方产物时，才考虑基于上游源码受控构建；不能仅因版本较旧就认定必须自构建。
- 当前开放 **Windows 10/11 x64（本地 NTFS）→ Linux、`rsync_simple`/完整 `rsync`/多工程、SSH 私钥或密码认证**。普通无链接工程不要求管理员权限；涉及原生链接创建时按实际 Windows 权限验收。
- 保持现有同步模式选择；不自动迁移 iFT 配置。工具不可用或同步失败时明确失败，用户可自行切回 iFT。

这是验证优先级与实施边界的决策，不代表已确认 oc-rsync 可发布。Windows 对照、Jugg 接入及 Flow 验收仍是完成条件；若不能满足，保留现有支持范围并记录阻断证据，不静默放宽同步契约。

## 2. 为什么选择这条路线

Jugg 需要的不只是复制文件，而是现有过滤顺序、删除策略、增量比较、链接处理、SSH 和失败退出状态同时成立。oc-rsync 已有关键参数链路，尚无本次取得的证据证明其必然违反 Jugg 契约；应先用小范围验证判断能否获得免去 Cygwin 打包和路径适配的收益，而不是先承担 Cygwin 构建与分发成本。

| 路线 | 收益与代价 | 决策 |
|---|---|---|
| 原生 oc-rsync | 减少 Cygwin 运行库和路径转换；仍需证明重实现与 Jugg 契约等价 | 优先做独立对照验证；通过后优先接入 |
| 上游 rsync + Cygwin + Cygwin OpenSSH | 复用现有协议和参数实现；承担 DLL 闭包、Cygwin 路径及链接适配，必要时还需构建 | 选型备选；同样必须做 Windows 验证 |
| `rn7s2/rsync-win` 包装器 | 有便携打包参考；CLI 和退出码不满足现有调用契约，仓库已归档 | 不作为依赖；无需为绕开包装器而 fork |
| robosync / gokrazy rsync | 前者不提供所需远端 rsync 协议；后者已有参数与过滤缺口 | 沿用交接评估的排除结论，不扩展本次实施范围 |

### 2.1 oc-rsync：验证缺口决定验证范围，不直接决定排除

此次复核固定为 `v0.6.4`，标签解析后的源码提交为 `94226093b02650dde1e6f264dbfdf9f00716a078`。以下链接均指向该提交，避免将 master 能力算入发布版本：

- [README](https://github.com/oferchen/rsync/blob/94226093b02650dde1e6f264dbfdf9f00716a078/README.md)将 Windows x86_64 定为 Tier 2；原生文件链接创建存在权限限制。
- [Windows 互操作工作流](https://github.com/oferchen/rsync/blob/94226093b02650dde1e6f264dbfdf9f00716a078/.github/workflows/_interop-windows.yml)设置 `continue-on-error: true`，主要通过 MSYS2 上游 daemon 覆盖互操作；明确跳过 SSH loopback 和链接场景。
- [Windows 支持矩阵](https://github.com/oferchen/rsync/blob/94226093b02650dde1e6f264dbfdf9f00716a078/docs/user/windows-support-matrix.md)仍将部分 reparse point、长路径和大小写冲突处理列为待完成或仅审计。矩阵与工作流对部分能力的描述还存在差异，不能把“Shipped”直接当作端到端证明。

上述证据没有证明 oc-rsync 必然传错 Jugg 文件。CI 允许失败和场景未覆盖，意味着 Jugg 必须补做验证；不能据此推导原生化收益不足。Cygwin 同样有未经验证的路径、链接、权限和进程行为。先用本文验收夹具比较真实结果，再决定采用哪条路线；若遇到引擎缺陷，记录最小复现并评估修补范围，不预先承诺承担独立协议实现。

### 2.2 Cygwin：官方二进制优先，自构建必须有具体原因

本次查询的 [Cygwin rsync 包页](https://cygwin.com/packages/summary/rsync.html)最高列出 `3.3.0-1`。[上游发布记录](https://github.com/RsyncProject/rsync/releases/tag/v3.5.1)及 [NEWS](https://download.samba.org/pub/rsync/NEWS)显示后续版本包含安全和路径处理修复。因此需要审核官方包的源码、补丁及与 Jugg 场景相关的修复，不能把“来自官方包”或“protocol ≥ 30”当作充分条件。

但本次没有完成该包的补丁审核，不能仅凭版本号认定必须自构建。上游官方源码发布与 Cygwin 官方预编译包是两层来源；从官方包抽取并重新打包 exe/DLL，也不等于自行编译。**满足行为、修复和分发条件的官方二进制优先；确认缺少必要修复或能力后，才评估自构建。** rsync 3.5.1 仅保留为可能的源码构建候选，不是已证明必需的版本。其 [INSTALL.md](https://github.com/RsyncProject/rsync/blob/v3.5.1/INSTALL.md)包含 Cygwin 构建依赖说明，本次未执行构建。

## 3. 工具包与维护责任

oc-rsync 先使用固定发布产物记录版本、哈希和实际运行依赖，使用明确路径的 Windows 原生 OpenSSH 验证既有 `host:path` / `-e` 调用，不为原生方案引入 Cygwin。原生不等于零运行依赖：v0.6.4 支持矩阵仍列出 MSVC runtime，SSH 的来源与可用性也要检查。正式接入前确定 SSH 使用系统组件还是受控分发；若改用内嵌 SSH，应另证认证、主机校验、输出和取消等价。

以下工具包设计**仅在进入 Cygwin 备选后适用**。先审核官方二进制，确需自构建时再选取源码基线；下表是既有候选快照，不是未经验证就必须采用的组合。实际分发必须锁定所有来源和文件哈希，不在用户机器动态选择 latest：

| 组件 | 构建基线 | 交付要求 |
|---|---|---|
| rsync | 优先合格的 Cygwin 官方包；自构建候选为上游 `3.5.1` | 核对实际补丁、源码和产物 SHA-256；确需构建时记录原因、工具链与配置；完成 Windows 验收 |
| Cygwin runtime | 官方 `3.6.11-1` stable | 固定官方包及对应源码；[包页](https://cygwin.com/packages/summary/cygwin.html)中的 3.7.0 test 包不进入首期基线 |
| OpenSSH | 官方 `10.5p1-1` stable | 仅收集实际 SSH 客户端所需文件；[包页](https://cygwin.com/packages/summary/openssh.html)中的 10.6p1 test 包不进入首期基线 |
| `cygpath.exe` 与 DLL/其他必需文件 | 与固定 Cygwin 包集合一致 | 按 PE 依赖扫描及干净 Windows 实跑取得闭包，不照抄旧 DLL 列表 |

若采用 Cygwin，打包脚本、固定包清单和验证说明放在本仓库工具目录，输出一个带版本的 Windows x64 runtime ZIP。ZIP 内包含 `bin/rsync.exe`、`bin/ssh.exe`、`bin/cygpath.exe`、实际依赖、逐文件校验清单及许可证材料。保持 Cygwin 所需目录结构；是否需要配置文件或其他辅助程序，以实际调用和干净环境证据决定。构建环境可以安装完整 Cygwin，最终用户无需安装。

插件内置这一个 ZIP，使用现有全局资源机制准备到版本目录，例如 `~/.jugg/resources/tools/windows/rsync/<runtime-id>/`。准备后校验清单并使用绝对路径启动工具，不依赖用户 PATH 中的 Git、MSYS2、Cygwin 或系统 OpenSSH。文件缺失或校验不符时，仅从插件内置资源重新准备一次；仍失败则报告最终原因。

Jugg 维护者负责固定依赖、构建、闭包、更新和验收；不承担新的 rsync 协议实现。需要源码补丁时先记录明确的构建/行为失败，只接受范围可控的兼容修补。若需长期维护独立协议分支，应重新评审成本。

发布时保留二进制对应的源码、补丁、构建脚本和组件许可证，按各组件实际许可提供获取方式；不能用一份官网链接替代材料。[Cygwin 许可说明](https://cygwin.com/licensing.html)是核对入口。首次包体和启动耗时在产物完成后测量，不沿用 7 月对旧包的数字，也不承诺未经测量的性能收益。

## 4. Jugg 的最小适配设计

### 4.1 参数：共用规则，按调用方式渲染

将上传与 classpath 拉取的参数生成收敛为有序 token 列表，再分别供 Windows 进程、现有 Unix shell 和 iFT 使用。保留现有调用入口，必要时由小型私有方法完成；不建立可插拔同步引擎框架。

- Windows 通过 `ProcessBuilder(List<String>)` 或等价 argv 接口直接启动；版本探测和 `cygpath` 也不经过 `cmd.exe /c`。
- argv 中的 pattern 是 `--exclude=local.properties` 等原始参数，不带为 shell 准备的字面单引号。不能通过 `split(" ")` 或删引号来反解析旧命令。
- Unix 与 iFT 从同一列表按各自既有契约渲染，保护其原行为；不在 Windows 分支复制一份过滤规则。
- 源码上传使用 `-av --delete`，保留链接本身，不展开树外目标，必需 include 位于用户 exclude 之前；classpath 保留现有 `--delete-excluded`、`--prune-empty-dirs` 及“不删除”分支。
- pattern 作用于实际传输根，不是固定 IDE 工程根；保持源路径尾部 `/`、固定放行目录和用户排除规则的顺序。

### 4.2 路径：只在本地边界转换

oc-rsync 使用实测接受的 Windows 本地路径；采用 Cygwin 时，才用同版本 `cygpath.exe -u` 转换本地源码、下载目录和 Cygwin SSH 私钥路径。SSH 参数遵循实际 SSH executable 的路径规则；按单次命令复用转换结果。两条路线都保留远端 `user@host:/path` 与 POSIX 路径，不转换 include/exclude。远端路径若经过 Windows `File` 拼接，应仅在相关远端边界消除反斜杠，避免修改全局路径模型。

下载前由 Kotlin 创建本地目录，确认目录可用后再执行 rsync。远端 `mkdir -p` 继续走既有远端命令链；本地不拼接 Unix shell 命令。

首期路径验收包括本地盘符、跨盘、中文、空格及特殊字符。UNC、网络盘和云占位文件不列入首期已支持范围；遇到未支持端点时在传输前明确提示。长路径与大型模块过滤集要实测 Windows argv 和文件系统边界，不能因绕过 cmd 就假定无限长。

### 4.3 SSH：明确客户端来源与认证链

- oc-rsync 优先验证明确路径的 Windows 原生 OpenSSH；仅 Cygwin 备选使用内置 Cygwin `ssh.exe`。保留显式 user、port、`StrictHostKeyChecking=accept-new` 和现有 identity 列表顺序；不强制截断为单个 key。
- `-e` 仍是 rsync 内部解析的一个 SSH 命令字符串：即使外层用了 argv，SSH executable、每个 identity 和 known_hosts 路径仍需独立转义，并验证 runtime 路径含中文、空格的情况。
- 明确使用 Windows 用户目录下的 `.ssh/known_hosts`，按所选 SSH 的路径规则传入，避免默认 home 导致校验记录落在其他位置。首次主机可接受，已记录但改变的 host key 必须失败。
- 私钥使用批处理认证；密码通过强制 askpass、单次口令请求完成，避免挂在交互提示上；不承诺 agent、PuTTY、交互式私钥解密或代理命令兼容。
- JSch 登录与 rsync 的外部 SSH 分别验证；JSch 成功不代表外部 SSH 已具备相同凭据。Windows 不进入 expect/sshpass；不支持的密码配置在同步前给出明确原因。

### 4.4 链接与属性：既保留上传语义，也检查 JVM 消费结果

上传沿用 [现有源码链接修复](remote_source_symlink_sync.md)：外部相对链接和绝对链接展开，内部相对链接保留。Windows 原生文件/目录 symlink 与 junction 分开验证；Git 把 symlink 检出成的普通文本文件不视为真实链接，Jugg 不猜测并改写它。

**拉取必须避免生成仅 Cygwin 可识别的链接。** [Cygwin 链接文档](https://cygwin.com/cygwin-ug-net/using.html#pathnames-symlinks)说明默认链接可能是 WSL reparse point 或特殊文件；Jugg 后续使用 JVM/Android 工具读取，不能只用 Cygwin 再读一次便判定成功。

首期 runtime 限定本地 NTFS；oc-rsync 验证其原生链接创建和权限失败行为。仅 Cygwin 分支在子进程环境使用 `winsymlinks:nativestrict`，不修改用户全局配置。依据 [CYGWIN 选项说明](https://cygwin.com/cygwin-ug-net/using-cygwinenv.html)，该选项在原生链接创建失败时返回失败，避免 `native` 模式的兼容链接回退。两条路线都以 JVM 能读取目标内容及链接类型为准；缺少创建权限时明确失败，不追加 `-L` 静默展开全部链接，也不要求所有用户默认提权。

mtime、远端脚本可执行性、本地可读写性必须验收；不宣称 NTFS 与 POSIX owner/group/mode 完全等价。不为绕过失败全局放宽权限。若固定工具在真实场景仍需权限参数适配，先保存失败证据，再在 Windows 对应传输方向做最小调整，回写本节并补验证。

### 4.5 结果与取消：Windows 直接读取进程状态

当前 `CmdExecutor` 依赖完成标记；`RemoteGradleCompileClient.normalizeRsyncProcessResult()` 会在未取消且结果为 `RESULT_CHANNEL_CLOSED` 时返回成功。直接启动 exe 若仍沿这条缺标记路径，会形成假成功，这是必须覆盖的失败边界。

- Windows 直接进程分支读取实际退出码，并排空 stdout/stderr；不等待 shell 完成标记，也不从任意输出文本猜测成功。
- 正常退出只有 0 成功；启动失败、部分传输、SSH 失败及无法取得结果均失败。Windows 该分支不得进入旧的“缺少标记视为成功”归一化，Mac/Linux 原有路径保持本次范围外。
- 取消映射为既有 canceled 状态，复用 `CmdExecutor.release()` 的 pending-cancel 和进程树销毁，验证启动前、传输中及 rsync/SSH 子进程均被收口。已有销毁逻辑不能代替 Windows 进程存活检查。
- 复用输出监听和脱敏；保留原始诊断及退出码。Windows 私钥/密码认证失败直接返回，不重复使用相同凭据重试；Mac/Linux 的既有瞬态密码认证重试策略保持原样。断网或部分传输不自动重传，不新增换引擎、换主机校验策略的重试。
- 同步失败阻断后续构建或增量初始化，保留已有有效 context/history；不把已部分更新的远端目录描述成事务回滚成功。删除类夹具每次恢复后再测。

### 4.6 开放入口与改动落点

| 当前入口 | 必要改动 |
|---|---|
| `RsyncCompatibleHelper.kt` | Windows x64 从固定 runtime 准备并实际探测；移除该平台直接不兼容的分支，不将版本文本当成全部能力证明 |
| `RsyncCommand.kt`、`SshCommand.kt` | 共用有序参数、Windows argv、局部路径/SSH 适配和本地目录准备 |
| `CmdExecutor.kt` | Windows rsync 直接进程与真实退出码；沿用现有监听及取消能力 |
| `RemoteGradleCompileClient.kt` | Windows 直接结果不得被旧归一化掩盖；入口和状态传播保持明确 |
| `JuggGradleCompileOptions.kt` | 首期平台、模式和认证范围校验；远端路径按需局部适配 |
| `JuggSettings.kt` | Windows 本地 backup classpath 继续关闭；不能被全局 rsync 探测成功顺带开放 |

资源准备使用一个最小内部实现即可；不预设更多接口或类层次。无需修改的文件不为满足表格而改动。Mac/Linux 与 iFT 通过原有路径回归，不改远端 Gradle、部署协议或缓存格式。

## 5. 验证、实施顺序与发布边界

实施顺序调整：维护者明确要求在 SSH/Cygwin/server 环境完成前提前落地两种候选的适配代码，复用已有 remote compile testcase。下列选型和发布验收门槛保留；独立 PoC 与生产适配可并行准备，不将“代码已准备”写成“方案已通过”。正式引擎仍由实测决定，运行时不维护自动切换策略。

1. **先做 oc-rsync 独立 PoC**：固定发布产物、源码和哈希，在没有 Cygwin、Git/MSYS2 不在 PATH 的 Windows x64 NTFS 环境记录运行依赖与实际 SSH 来源。先不 fork、不修改 Jugg 生产代码；验证真实 argv、SSH 以及下述文件行为。
2. **固定输入做独立传输对照并选型**：使用 Jugg 实际上传、APK 和 classpath 参数连接 Linux 上游 rsync，对照标准 rsync 的文件集合、内容哈希、链接、删除及退出状态；至少包含重复同步、外部链接展开、过滤清理、错误 key、远端缺 rsync、非零退出及取消。oc-rsync 满足必要契约后优先接入；若出现必要契约缺陷，先记录最小复现和修补范围，范围不合适时转入 Cygwin 官方包审核及同一矩阵验证。只有官方包不能满足条件且原因已确认时才自构建。对拟支持的远端版本分别验收，不把旧探测下限当安全推荐。
3. **按价值门禁补接入回归**：取得当前 Windows 不兼容分支及 argv/退出码失败证据，再改生产代码。复用已有 behavior owner，测试真实可观察结果，不为内部对象或类名加守卫。Windows 限定分支的稳定进程、文件行为可独立覆盖，不把本次任务变成测试框架建设。
4. **接入并跑 Flow**：完成 Windows 最小调用适配，验证真实源码上传 → Linux Gradle → APK/classpath 拉取 → 增量初始化 → 后续增量编译与设备部署；失败与取消必须在链路中生效。
5. **达到矩阵后开放**：只有实际跑过且有记录的环境进入支持范围；macOS 远端、多工程 `rsync`、本地 backup classpath、Windows ARM64 和 Windows 远端均不随首期开放。后续可复用同一引擎分别验收。

最低验收矩阵：

| 范围 | 必须记录的结果 |
|---|---|
| 干净环境/runtime | 工具版本、所有文件哈希、依赖闭包、无完整 Cygwin 可运行；重复使用、缺文件、损坏及一次重新准备失败 |
| 文件与增量 | 必需配置放行、多条 include/exclude 及顺序、上传删除、classpath 删除/不删除分支、prune；重复同步不意外改写，修改后内容更新 |
| 上传链接 | 内外相对链接、绝对链接、junction、缺失/不可读目标；本地内容不被修改，目的端类型和内容正确 |
| 拉取链接/属性 | 原生 symlink 创建成功和权限不足分支；JVM 消费、mtime、远端 wrapper/自定义脚本执行、本地可读写性 |
| 路径与规模 | 用户/runtime/工程/key 路径含中文、空格和特殊字符，跨盘，目录尾 `/`，长路径、真实大型 classpath 参数集；未支持端点提前拒绝 |
| SSH | 正确/错误 key、非默认端口、首次 host key、已知 key 改变、远端无 rsync、远端版本差异；JSch 与外部 SSH 分别记录 |
| 失败/取消 | 非零退出、断网、部分传输、无输出退出、启动前/中途取消；不假成功、不初始化失败基线、不遗留传输子进程 |
| 正常与回归 Flow | Windows 首次远端编译和后续增量部署；失败阻断与已有状态保留；Mac/Linux/iFT 正常路径 |

已有 owner 核对结果：

- `main/.../SyncFileCommandTest` 已有真实文件/链接回归，但 helper 使用 `assumeFalse(isWindows)` 并通过 Bash 启动，现有绿灯不能作为 Windows 证据。复用其行为场景，接入实际 Windows runtime 的执行路径。
- `FetchClasspathCommandTest` 主要检查过滤参数，仍需目的端文件、删除和 prune 的真实结果。
- `BaseSshCommandTest` 保护 shell 完成标记，不覆盖 Windows 直接进程退出；`RsyncAuthRetryPolicyTest` 保护既有重试判定。
- `idea/.../RemoteGradleCompileClientTest` 在缺少 `JUGG_REMOTE_CONFIG_FILE` 时直接返回；必须记录配置已就绪和实际执行日志，不能把空跑当作 Flow 成功。结合 `TopLevelFlowTest` 或等价真实 Flow 验证部署。

实施时按 [06_testing.md](../../ai_knowledge/06_testing.md)选择定向 `--tests`，编译可用 `./gradlew :idea:compileKotlin`。未取得 Windows、SSH 或设备环境时准确列为未验证，不以其他平台测试替代。原纯方案阶段未执行构建；提前落地阶段的验证见下文。

## 6. 文档同步与本次交付

本次只确定方案，现有已实现能力不变；不把规划写入 `ai_knowledge`，不修改 Wiki 支持范围。实现完成时：

- 在 `04_engineering_project.md` / `05_utilities.md` 中仅同步实际落地的远端同步边界和 runtime 规则，按新增真实入口更新 `98_code_map.md`。
- 检查现有远端编译配置说明及 `troubleshooting/remote-build-failed.md` 中 Windows/iFT 限制；只有失准的用户说明才更新。按实际页面同步中英文，不沿用 7 月预设的 agent onboarding 页面。
- 将工具清单、命令、平台、Flow 证据和支持范围回写本文；任何落地偏差必须说明原因。

已完成：Jugg 当前实现与测试 owner 复核、固定发布版本的外部证据核对、验证优先级与最小适配设计、实施和验收边界。10 月 18 日修正了“未验证即排除 oc-rsync”及“旧版本即必须自构建”的过早判断。提前落地了候选 runtime 校验/内置资源恢复、Windows argv/退出码/取消、源码上传与 APK/classpath 拉取，复用现有 remote compile testcase 并补充配置文档。没有新增引擎自动切换或公开设置。

本机 Windows 定向 JVM 回归覆盖 `CmdExecutorProcessTest`、`WindowsRsyncRuntimeTest`、`CmdExecutorCancellationTest`、`SyncFileCommandTest`、`FetchClasspathCommandTest`、`BaseSshCommandTest` 和 `JuggGradleCompileOptionsTest`；54 项中 45 通过，9 项 Unix/POSIX 场景 skipped。`idea:compileKotlin` 与 `idea:compileTestKotlin` 通过；Python PoC 进程结果与打包完整性 6 项通过。新增进程 API 的首次定向编译因缺少 `invokeProcess` 失败，随后实现并通过回归；本机同时复现既有 Bash/POSIX 测试在 Windows 的错误，已将平台前置条件前移，未将这些跳过项计入 Windows 传输证据。

10 月 19 日补充：已完成两种候选对 Linux rsync 3.2.7 的真实 SSH 传输对照，覆盖过滤、增量、删除/不删除、mtime、错误 key、host key 改变、27 项模拟产物文件与链接；Cygwin 原生链接首次 ENOENT 可在目标到达后恢复一次，oc-rsync 树外相对链接上传失败。复用 `RemoteGradleCompileClientTest` 完成真实 JSch + Windows rsync 的源码上传、脚本生成全量产物、APK/classpath 拉取、失败与取消；不是空跑或仅编译成功。Cygwin 走安装检测，当前无需制作随插件分发的 Cygwin ZIP。详情、定向回归及插件产物见 [验收记录](windows_rsync_acceptance.md)。

仍未完成的完整发布验证：真实 Gradle 与 APK 解析/增量初始化/设备部署 Flow、Mac/Linux/iFT 实机、其他远端版本与极端文件系统/规模矩阵。oc-rsync 未通过必要契约，没有进入内置包发布；将来修复后仍须验证许可、依赖闭包与打包恢复。当前交付目标是可进入 e2e 测试的插件，公开支持范围暂不扩大。

依据：`00_overview.md`、`99_index.md`、`98_code_map.md`、`04_engineering_project.md` §5、`05_utilities.md` 相关工具/同步约束、`06_testing.md` 相关门禁与文档验证要求、10 月交接、7 月历史方案和源码链接修复记录；实现与测试入口见本文 §4–§5。外部事实以各处官方链接为准，缺失的产物和运行证据未冒充已取得。
