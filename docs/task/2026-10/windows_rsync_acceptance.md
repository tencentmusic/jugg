# Windows rsync 验收与交付记录

2026-10-19 修正：下文去掉 `--copy-unsafe-links` 仅指 Windows；macOS/Linux 保留原参数及树外目标复制行为。

日期：2026-10-19；分支：feature/windows-rsync。

## 当前结论

维护者明确接受不展开树外链接，源码上传已去掉 --copy-unsafe-links；两种候选的私钥和真实密码独立传输矩阵均通过，包括过滤、删除、增量、mtime、错误密钥、主机密钥变更、原生链接与 27 个模拟产物（23 个 allBuildPaths、自定义 classpath、自定义同步路径、APK、diff）。默认内置 oc-rsync，Settings 可切换本机 Cygwin，不自动安装。

## 环境与来源

- 本机 Windows x64、本地 NTFS，仓库/工具位于 D 盘，JVM 临时目录位于 C 盘；远端为资源有限的 Linux、rsync 3.2.7、Python 3，未运行远端 Gradle。
- Cygwin D:/cygwin64，rsync 3.3.0 protocol 31、SSH 10.5p1；rsync SHA-256：561580b0be4b50c5ba00d992d615496131aa95faaa73e2b3e4269dc6c38e84f8。
- oc-rsync 0.6.4 protocol 32，提交 94226093b02650dde1e6f264dbfdf9f00716a078；rsync SHA-256：cd39d64e845a414d3154a617463a310259691d2d13c62bdd96e1c3671ecbb6bb。官方发布 ZIP digest 已比对，包中携带许可证和来源记录，对应源码归档在 third_party/sources 并由发行 SOURCE.md 定位。
- 原生 Windows OpenSSH 9.5p2。连接字段仅在测试进程内读取，不在报告记录账号、服务器地址、密码、私钥或原始 stderr。

## Benchmark

两个引擎各 5 轮，257 文件，首次约 3 MiB（256 × 4 KiB + 2 MiB）；修改 16 文件并改变大小，避免时间戳精度误判。每轮拉取全部文件后比较 SHA-256。每个样本包括 rsync 进程与 SSH 启动，不含路径预转换；网络与低配 server 是共同影响因素，先 Cygwin 后 oc-rsync 顺序执行，未随机交错。以下为墙钟时间中位数（秒），原始 5 轮与 min/max 保存在脱敏 JSON。

| 引擎 | 首次上传 | 无变化上传 | 变化上传 | 拉取 |
|---|---:|---:|---:|---:|
| cygwin | 1.0516 | 0.6654 | 0.6774 | 2.4959 |
| oc-rsync | 0.8524 | 0.6316 | 0.6281 | 0.9907 |

当前小样本 oc-rsync 首次上传约快 19%，无变化/变化上传约快 5%/7%，拉取约快 60%。这不是大型工程、CPU/内存占用或真实 APK 的结论；没有执行真实 Gradle、大产物或设备部署。

## 已确认差异及适配

1. --copy-unsafe-links 下 oc-rsync 的树外相对链接返回 Windows 123/rsync 23。维护者确认去掉该最近新增参数；新契约保留链接，不物化树外目标，独立矩阵据此重新验收。
2. Cygwin 原生链接首次拉取可能在目标尚未到达时 ENOENT/23；仅 classpath 命中该明确错误时重试一次，第二次仍失败则保留失败；其他错误不重试。
3. oc-rsync 0.6.4 在 ssh.exe 前插入 BatchMode=yes，直接 SSH 密码成功而 rsync 密码失败。密码路径将系统 SSH 原样复制到按 SHA-256 隔离的 jugg-ssh.exe，避开其 basename 特判，真实密码矩阵重新通过。
4. Windows full rsync 的嵌套路径要保留 POSIX 分隔符及原始空格；classpath 返回实际下载位置，避免 Windows 原路径额外拼接工程名。
5. 取消回归从实际进程存活阶段发起，避免把缓冲输出的时机误认为进程启动时机；移除排查期间未证明必要的额外递归参数，默认参数的 oc-rsync Flow 重新通过。
6. Windows SSH 隔离个人配置，known_hosts 参数不含字面双引号；密码仅进子进程环境，经 askpass 交给 SSH，不进入命令行或文件。

## 回归与限制

定向 JVM/IDE 测试 49 项通过、4 项 Unix 专用测试在 Windows 跳过；双引擎×双认证模拟 Flow 各 4 项，共 16 项通过，oc-rsync 默认递归参数再次复验；Python 9 项通过；中英文 Wiki production build 通过。插件 buildPlugin 及 verifyThirdPartyCompliance 通过；内置运行时、askpass 与源码引用按最终提交重新核对。集成测试复用 RemoteGradleCompileClientTest，只筛选 testSimulated*，不在弱服务器运行现有真实 Gradle 测试。Settings 切换不重启 IDE，从下次传输生效。

本轮不代表交互 MFA、代理/跳板机、远端 Windows、非 NTFS/UNC、真实大型产物及设备安装已验收。benchmark 不输出这些未测维度的结论。
