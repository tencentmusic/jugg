# Windows rsync 测试准备

## 当前行为与运行依赖

Windows x64 本地 NTFS → Linux/macOS 支持 `rsync_simple`、完整 `rsync` 与多工程。统一使用本机 Cygwin 的 rsync、ssh 和 cygpath。macOS 使用账号可写的同步目录，并在远程环境变量设置实际 ANDROID_HOME；不能沿用 Linux 的 /home 路径。若 Gradle 全局代理不可用，应修正或在本次编译命令覆盖，不要把依赖下载失败误判为 SSH/rsync 认证失败。

安装 Cygwin x64 的 rsync、openssh，将该安装的 bin 目录加入用户 PATH，重启 IDE/测试进程。只选择 PATH 第一个 rsync.exe，要求同目录 ssh.exe/cygpath.exe；不完整时直接失败，不跳过冲突项。JUGG_CYGWIN_HOME 与固定目录探测已取消。失败控制台提供中英文环境配置 Prompt，可交给自己的 Agent；不自动安装或修改 PATH。

私钥路径或密码继续填写 `remoteSshPassword`。外部 SSH 隔离个人 SSH 配置，密码通过每次子进程环境和 askpass 传递，不放入命令行或文件。askpass.exe 运行需要 Windows 自带 .NET Framework 4；维护者重建可执行 `powershell -File tools/windows-rsync/build_windows_rsync_askpass.ps1`。涉及链接时需要 Windows 原生 symlink 权限。上传保留链接，不展开树外目标。

## Server 给集成测试的配置

离线单元测试无需 server。模拟远程 Flow 只需要 Linux、Bash、SSH shell session、rsync、Python 3 和 /tmp 写权限；SSH 账号不能是只能 SFTP 或只能 exec 的受限账号。私钥测试将公钥加入该账号的 `~/.ssh/authorized_keys`；密码测试允许该账号密码登录。两种登录方式都要被 JSch 和对应 OpenSSH 接受。known_hosts 校验仍生效，主机密钥改变应先确认原因。

真实 Gradle 测试另需匹配 android_demo_project 的 JDK、Android SDK/platform/build-tools、Gradle distribution/依赖缓存与网络访问权限，且有充足内存。本轮弱服务器只跑模拟测试，不运行真实 Gradle。

在仓库外创建 JSON，例如 `C:/Users/YourName/.jugg/remote.json`：

```json
{
  "remoteSshIp": "YOUR_SERVER",
  "remoteSshPort": 22,
  "remoteSshUser": "YOUR_TEST_USER",
  "remoteSshPassword": "C:/private/test-key-or-password",
  "remoteSyncPath": "/tmp/jugg-test-workspace",
  "compileCommand": "./gradlew :app:assembleDebug",
  "outputApkName": "app/build/outputs/apk/debug/app-debug.apk"
}
```

密码 JSON 可另存 remote-pass.json，只修改 remoteSshPassword，其他字段按 server 实际值填写。不要把文件加入仓库或输出到对话。模拟测试在内存覆盖 compileCommand 和工作目录，不改写 JSON；上传包含 --delete，只在独占 /tmp/jugg-e2e-* 工作区操作，结束后清理。模拟 APK 只是小文本文件，不用于设备安装。

## 复用 remote compile testcase

```powershell
$env:PATH = "D:/cygwin64/bin;$env:PATH" # Only this test process; use your actual installation.
$env:JUGG_REMOTE_CONFIG_FILE = "$HOME/.jugg/remote.json"
.\gradlew.bat :idea:cleanTest :idea:test --tests '*RemoteGradleCompileClientTest.testSimulated*'
```

上述过滤只运行模拟产物三种布局与取消回归，不会匹配现有真实 testCompile/testFetchClasspath。密码回归只需将环境变量指向 remote-pass.json，再次执行。已有 JSON 仍使用原 compileCommand/outputApkName；不需要为模拟测试修改它们。

## 真实工程编译与拉取

Mac 系统 openrsync protocol 29 和 Linux GNU rsync 均使用同一本机 Cygwin 路径，不再预检或切换引擎。

使用同一 JUGG_REMOTE_CONFIG_FILE，筛选 `*RemoteGradleCompileClientTest.testRealProjectCompileAndFetch`。该用例以仓库 android_demo_project 为真实工程：清理远端构建产物，首次构建、无修改重编译、修改测试资源后重编译；校验每轮 APK 的 Manifest、DEX 和资源内容，拉取到新的 classpath 目录并验证 app class 文件，最后恢复本地测试资源。它实际运行远端 Gradle，需要完整 JDK/SDK/依赖网络；不要对早期弱服务器执行。

真实源码/产物传输工具为 `tools/windows-rsync/windows_rsync_project_benchmark.py`，通过 `--config`、`--project`、`--artifacts`、`--engine cygwin`（可省略）、`--rounds 3`、`--output` 指定测试。运行真实用例时可额外设置 `JUGG_REMOTE_ARTIFACT_SNAPSHOT` 为一个不存在的目录，导出实际 classpath/APK 快照；将此目录传给 `--artifacts`。也可用互斥的 `--remote-artifacts` 只读指定远端目录，但它可能包含产品不拉取的缓存，不能混同产品能力。全量/增量变化都在新建独占 /tmp/jugg-project-benchmark-* 副本中完成。输出目录必须不存在，汇总仅含脱敏计时、规模和哈希验证状态。原始配置不能加入仓库。具体实测与限制见 [Mac 真实工程验收](windows_rsync_mac_real_acceptance.md)。

## 小夹具独立矩阵与 benchmark

```powershell
python tools/windows-rsync/windows_rsync_acceptance.py --config "$env:JUGG_REMOTE_CONFIG_FILE" --benchmark-rounds 5
```

默认读取 D:/cygwin64，可通过 --cygwin-root 调整。安全入口仅输出脱敏分类、哈希、计时和文件数；不要用底层原始 Probe 保存私有配置的 argv/stderr。benchmark 使用 257 文件约 3 MiB，分别测首次上传、无变化上传、16 文件增量修改和拉取，每轮比对全部下载 SHA-256。报告位于 Git 忽略的 verification/windows-rsync 下。结论不外推为真实大 APK/Gradle 性能。

## 内置包来源

插件只内置密码认证辅助 askpass.exe，不分发 rsync、OpenSSH 或 Cygwin。历史双方案数据保留在验收记录中；当前实现与发行包统一为 Cygwin。
