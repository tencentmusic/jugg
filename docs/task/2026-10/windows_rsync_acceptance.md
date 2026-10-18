# Windows rsync 验收与 e2e 交付记录

日期：2026-10-19。分支：`feature/windows-rsync`。本轮目标是完成候选对照，并让通过的候选能进入插件 e2e 测试；不是宣布所有 Windows/远端/设备组合均已支持。

## 结论与范围

- Cygwin rsync 3.3.0 通过已执行的 Windows → Linux 传输矩阵，经一次针对已确认链接 ENOENT 的恢复后，原生链接可由 JVM 消费。按维护者要求使用安装检测与提示，不随插件分发 Cygwin。
- oc-rsync 0.6.4 普通文件、过滤、删除及产物拉取通过，但 `--copy-unsafe-links` 上传树外相对链接返回 Windows 错误 123、rsync 状态 23。该行为违反当前源码上传契约，未达到内置集成条件；不通过移除参数或展开所有链接来伪造通过，也没有把未通过的二进制放入插件。
- 两种候选共享有序参数和进程基础设施；Cygwin 特有逻辑集中在 `WindowsRsyncRuntime` 的安装检测、路径/环境和链接恢复判断。保留显式候选包入口，未来移除其中一种无需改写上传/拉取过滤规则。

维护者明确指出服务器性能不足。本轮不执行远端 Gradle、不下载真实大 APK/classpath；远端 Python 生成小文件，覆盖全部 23 个 `allBuildPaths` 项、自定义 classpath、自定义同步文件、APK 与 diff。模拟 APK 仅用于客户端文件传输，不进入 APK 解析、增量基线或设备安装。

## 环境与固定输入

| 项目 | 实测值 |
|---|---|
| 本机 | Windows x64，本地 NTFS；D 盘工具/仓库、C 盘 JVM 临时测试目录 |
| Cygwin | `D:/cygwin64`；rsync 3.3.0，protocol 31；OpenSSH 10.5p1 |
| Cygwin rsync SHA-256 | `561580b0be4b50c5ba00d992d615496131aa95faaa73e2b3e4269dc6c38e84f8` |
| oc-rsync | 0.6.4，提交 `9422609`，protocol 32；两个 exe 内容相同 |
| oc-rsync SHA-256 | `cd39d64e845a414d3154a617463a310259691d2d13c62bdd96e1c3671ecbb6bb` |
| 原生 SSH | Windows OpenSSH 9.5p2，`C:/Windows/System32/OpenSSH/ssh.exe` |
| server | Linux，上游 rsync 3.2.7；允许 SSH shell、Python 3 和临时目录读写 |

连接信息仅由脚本/JVM 内部加载 `JUGG_REMOTE_CONFIG_FILE`。没有把账号、地址、私钥、原始命令和现场日志提交到仓库。独立脚本对每次运行使用自己的 known_hosts，不覆盖用户记录；生产调用仍使用 Windows 用户目录的 known_hosts。

## 失败证据与修正

1. 原生 SSH 的生效配置关闭公钥并优先密码，导致已配置私钥仍认证失败。隔离用户 SSH 配置并显式启用公钥后，两种 SSH 均认证成功。生产 Windows 调用采用相同参数，不改写全局配置。
2. Cygwin 直接 argv 中的 `UserKnownHostsFile="path"` 带入字面双引号，导致失败。去掉参数内部字面引号，统一由 rsync `-e` 渲染层转义后通过；保留了带空格路径的探测。
3. Cygwin 原生链接创建依赖目标已存在：本机 `ln -s existing-target` 成功、`ln -s absent-target` 报 ENOENT；全新目录拉取时，链接生成发生在普通文件内容落盘前，首次状态 23。首次传输已改变失败条件，第二次成功。仅对 Cygwin classpath、状态 23 和明确 symlink ENOENT 恢复一次；仍失败、取消或其他错误不伪造成功。[Cygwin 维护者说明](https://cygwin.com/pipermail/cygwin/2019-April/240994.html)与本机复现一致。
4. oc-rsync 的最小失败输入：源树内 `outside.txt -> ../external.txt`，源树外真实文件存在；上传保留 `-av --copy-unsafe-links --delete`。上游 Cygwin rsync 上传成功，oc-rsync 报 `link_stat ... failed ... (123)` 并返回 23。失败发生在上传，尚未进入该用例的拉取阶段。没有认定所有 oc-rsync 链接均失败。
5. 传输取消回归要求后设的 listener 能观察原生输出，发现 `CmdExecutor` 仍持有初始 listener。Windows 调用边界补同步。真实取消用例在 rsync 接收文件列表时触发，并观察 rsync/SSH 子进程退出；不以 mock 调用代替进程状态。

## 可复跑的验证

### 独立候选对照

```powershell
$env:JUGG_REMOTE_CONFIG_FILE = "$HOME/.jugg/remote.json"
python tools/windows_rsync_acceptance.py --cygwin-root D:/cygwin64 --oc-rsync D:/Downloads/oc-rsync-0.6.4-windows-x86_64/rsync.exe
```

复用已有 `windows_rsync_probe.py` 文件/链接夹具。报告位于被忽略的 `verification/windows-rsync/acceptance/preflight-report.json`；预期整体退出 1，因为 oc-rsync 的必要链接契约未通过。

| 行为 | Cygwin | oc-rsync |
|---|---|---|
| 实际 SSH/Linux/rsync/Python 前置检查 | 通过 | 通过 |
| 中文、空格、`&`、括号路径 | 通过 | 通过 |
| 上传固定配置放行、默认排除、目的端删除 | 通过 | 通过 |
| 重复同步不改写、修改内容更新、本地内容不变 | 通过 | 通过 |
| 27 个模拟产物文件拉取并逐文件校验 | 通过 | 通过 |
| 拉取删除/不删除、prune、mtime | 通过 | 通过 |
| 错误 key、远端 rsync 不存在必须失败 | 通过 | 通过 |
| 首次主机 key 接受、已知 key 改变必须失败 | 通过 | 通过 |
| 内部相对链接保留、树外相对/绝对链接展开 | 通过 | 树外相对链接失败 |
| 原生链接拉取并读取内容 | 单次恢复后通过 | 被上传失败阻断 |
| 隔离远端目录清理 | 通过 | 通过 |

### 插件真实客户端与构建

按 [配置说明](windows_rsync_test_setup.md)设置 `JUGG_CYGWIN_HOME` 和 `JUGG_REMOTE_CONFIG_FILE`，定向运行：

```powershell
.\gradlew.bat :idea:test --tests 'com.sickworm.intellij.jugg.gradle.RemoteGradleCompileClientTest.testSimulatedArtifactFetch' --tests 'com.sickworm.intellij.jugg.gradle.RemoteGradleCompileClientTest.testSimulatedCancellation'
.\gradlew.bat :idea:buildPlugin
python -m unittest discover -s tools -p 'test*windows_rsync*.py'
```

`testSimulatedArtifactFetch` 使用真实 JSch 登录、真实源码上传，并用脚本断言上传内容；随后生成产物、下载 APK/classpath、校验所有文件与链接，并验证非零命令阻断和真实传输取消。`testSimulatedCancellation` 验证远端 shell 运行中取消。复用现有 owner，不新增生产测试 seam。

定向 JVM 回归沿用 `WindowsRsyncRuntimeTest`、`CmdExecutorProcessTest`、`CmdExecutorCancellationTest`、`SyncFileCommandTest`、`FetchClasspathCommandTest`、`BaseSshCommandTest`、`JuggGradleCompileOptionsTest`。新增安装 Cygwin 的实际 classpath 链接回归，包含永不出现目标的永久失败。Python 回归 8 项通过，包含敏感诊断不进入报告的契约。

最终结果：上述 main 定向测试 55 项，46 通过、9 项 Unix/POSIX 场景 skipped，0 失败；真实远端模拟客户端 2 项通过，0 skipped；Python 8 项通过。`:idea:buildPlugin` 通过，ZIP 内已检查包含 `WindowsRsyncRuntime` 及 `JUGG_CYGWIN_HOME` 安装入口；产物为 `idea/build/distributions/jugg-3.5.4-feature_windows-rsync-SNAPSHOT.zip`。详细现场日志均保留在忽略目录，不随提交公开。

## e2e 使用与尚未声称完成的验证

安装本分支构建的插件 ZIP，安装 Cygwin x64 的 rsync、openssh 包；自定义路径设置 `JUGG_CYGWIN_HOME` 后重启 IDE。配置 `rsync_simple`、Linux server 和无交互私钥即可开始真实项目 e2e。没有 Cygwin 时提示安装；不自动联网安装工具，不自动切换到未通过的 oc-rsync。

本轮交付到插件 e2e 准备状态，以下仍属于后续完整发布验收，未冒充已验证：真实 Linux Gradle 与 APK 解析、增量初始化/后续设备部署、其他远端 rsync 版本、Mac/Linux/iFT 实机、无原生链接权限账户、junction 全矩阵、UNC/网络盘/云文件、长路径和极大型自定义过滤参数边界、Windows ARM64。oc-rsync 必要契约未通过，未开展其内置包许可、干净系统 DLL 闭包和损坏恢复发布验收。

Wiki 本次不扩大公开支持范围；面向测试人员的安装、server 与命令入口全部更新到本目录文档。真实 e2e 通过后，再更新用户 Wiki 的支持边界。
