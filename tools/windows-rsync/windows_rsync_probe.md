# Windows rsync 独立传输准备

此底层脚本保留原始诊断，生产接入及验收应以 [测试准备](../../docs/task/2026-10/windows_rsync_test_setup.md) 为准。Python 3.9+，无第三方 Python 依赖。没有自动换引擎。

使用现有 `JUGG_REMOTE_CONFIG_FILE` 时，运行 `windows_rsync_acceptance.py`，密码配置也使用此入口。它复用本脚本夹具，在进程内部读取连接配置，报告不保存账号、密码、key、原始 argv/stdout/stderr，覆盖小产物拉取、错误 key、host key 改变与链接；`--benchmark-rounds 5` 增加 257 文件约 3 MiB 的五轮 SHA-256 校验计时。执行方法和实测结论见 [验收记录](../../docs/task/2026-10/windows_rsync_acceptance.md)。下述原始 PoC 会保存完整命令和诊断，只适合脱敏的专用测试配置，不应公开其报告。

## 环境就绪后运行

1. 复制 `windows_rsync_probe.example.json` 到仓库外，准备 Cygwin 配置。所有 executable、key 和 known_hosts 使用绝对路径；配置中的 target 必须为受信任的测试服务器 `user@host`。私钥不需要交互解密；不要将凭据提交到仓库。
2. rsync、ssh、cygpath 均指定同一 Cygwin 安装的 `bin` 下的 exe。脚本不下载工具、不修改系统 SSH/Cygwin 配置。
3. Linux 服务器需要 Python 3、上游 rsync、mktemp 和 stat。使用专用测试账户，错误 key 应是存在但未授权的独立私钥。known_hosts 的父目录必须已存在；首次连接会按 accept-new 写入指定文件。
4. 在仓库根目录执行：

```powershell
python tools/windows-rsync/windows_rsync_probe.py --config D:/private/cygwin.json
python tools/windows-rsync/windows_rsync_probe.py --config D:/private/cygwin-rsync.json
```

链接测试单独启用，要求当前 Windows 账户有原生 symlink 创建权限。权限不足会失败，不自动提权或跳过：

```powershell
python tools/windows-rsync/windows_rsync_probe.py --config D:/private/cygwin.json --links
python tools/windows-rsync/windows_rsync_probe.py --config D:/private/cygwin-rsync.json --links
```

输出默认在被 Git 忽略的 `verification/windows-rsync/<engine>-<随机标识>/`。每轮独立夹具不复用，不删除已有文件。远端通过 mktemp 创建 `/tmp/jugg-rsync-probe-*`；报告记录准确路径，保留现场，检查后由测试者清理该单个目录。

报告包含逐次 argv、stdout/stderr、真实退出码、耗时和三个入口 exe 的 SHA-256。可能包含用户名、路径、主机地址和输出内容，对外提供前人工脱敏；不记录私钥内容。每次命令后落盘，失败保留已取得的证据；超时作为失败处理。SSH 预检与传输分别执行。

## 已准备的判定

- 以当前 `SyncFileCommand.getRsyncArguments()` 和 `JuggPathManager.RSYNC_PUSH_CONFIG_DIR_ARGUMENTS` 为来源的独立 argv 快照，包括必需配置放行、默认排除和上传删除。上传不再使用 `--copy-unsafe-links`，保留链接本身。后续生产规则变化时需复核快照。
- 中文、空格、`&`、括号工程路径；内容更新、重复同步 mtime、本地源文件哈希不变。
- classpath 形式的 include 目录、prune、删除/不删除分支，下载哈希和 mtime。使用固定 `src/**` 代表性过滤集，不声称覆盖真实 module 的全部动态规则。
- 远端 rsync 路径不存在必须非零；配置 bad_key 时未授权私钥必须非零，否则记录 not_run。
- 可选内部相对 symlink 保留、外部相对和绝对文件链接展开、下载原生 symlink 的 Windows Python 可读性。
- 两个引擎使用相同夹具和预期结果；Cygwin 仅在子进程设置 `winsymlinks:nativestrict`，本地路径通过指定 cygpath 转换。远端路径和过滤 pattern 不转换。

## 尚需补充的验收

通过脚本只代表已选用例通过。JVM/Android 工具消费下载链接、目录链接/junction、缺失/不可读链接、脚本执行权限、host key 改变、取消及进程树存活、断网/部分传输、无输出异常退出、长路径/跨盘/大型真实 classpath、JSch、干净机器依赖闭包、完整 Jugg Flow 均未由此脚本验证。原始计划中的这些项目仍是发布门槛。不要将 Python 可读性算作 JVM 证据。

脚本剔除了 PATH 中 Git/MSYS2，仅保留 rsync 所在目录和 System32，但这不能证明干净机器无系统依赖；它记录入口 exe 哈希，也不能代替 DLL 闭包和来源/许可证审核。工具/key/runtime 本身含中文或空格时应另配置对应真实路径重跑。

超时由 Python 终止直接子进程，尚不保证 SSH 后代全部退出；不要将超时测试视作取消验收。当前无需启动插件、Gradle 或 Android 设备。
