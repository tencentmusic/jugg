# Windows rsync 测试准备

## 当前行为与运行依赖

Windows x64 本地 NTFS → Linux 支持 `rsync_simple`、完整 `rsync` 与多工程。默认内置 oc-rsync 0.6.4，使用系统 `C:/Windows/System32/OpenSSH/ssh.exe`，缺失时提示安装 Windows OpenSSH Client。

Jugg 控制面板 Settings → Compiler 的 `Use Cygwin rsync on Windows (temporary)` 开启后，从下次同步使用本机 Cygwin。安装 Cygwin x64 的 rsync、openssh；默认检测 C:/cygwin64、D:/cygwin64 与 PATH。自定义安装目录通过 `JUGG_CYGWIN_HOME` 指定，重启 IDE/测试进程生效。不自动安装 Cygwin，不在失败后自动切换引擎。`JUGG_WINDOWS_RSYNC_RUNTIME` 仅用于诊断覆盖，会优先于 Settings。

私钥路径或密码继续填写 `remoteSshPassword`。外部 SSH 隔离个人 SSH 配置，密码通过每次子进程环境和 askpass 传递，不放入命令行或文件。askpass.exe 运行需要 Windows 自带 .NET Framework 4；维护者重建可执行 `powershell -File tools/build_windows_rsync_askpass.ps1`。涉及链接时需要 Windows 原生 symlink 权限。上传保留链接，不展开树外目标。

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
$env:JUGG_REMOTE_CONFIG_FILE = "$HOME/.jugg/remote.json"
$env:JUGG_TEST_WINDOWS_RSYNC_ENGINE = 'oc-rsync' # or cygwin
Remove-Item Env:JUGG_WINDOWS_RSYNC_RUNTIME -ErrorAction SilentlyContinue
.\gradlew.bat :idea:cleanTest :idea:test --tests '*RemoteGradleCompileClientTest.testSimulated*'
```

上述过滤只运行模拟产物三种布局与取消回归，不会匹配现有真实 testCompile/testFetchClasspath。密码回归只需将环境变量指向 remote-pass.json，再对两种引擎分别执行。已有 JSON 仍使用原 compileCommand/outputApkName；不需要为模拟测试修改它们。

## 独立矩阵与 benchmark

```powershell
python tools/windows_rsync_acceptance.py --config "$env:JUGG_REMOTE_CONFIG_FILE" --benchmark-rounds 5
```

默认读取 D:/cygwin64 和 D:/Downloads/oc-rsync-0.6.4-windows-x86_64/rsync.exe，可通过 --cygwin-root/--oc-rsync 调整。安全入口仅输出脱敏分类、哈希、计时和文件数；不要用底层原始 Probe 保存私有配置的 argv/stderr。benchmark 使用 257 文件约 3 MiB，分别测首次上传、无变化上传、16 文件增量修改和拉取，每轮比对全部下载 SHA-256。报告位于 Git 忽略的 verification/windows-rsync 下。结论不外推为真实大 APK/Gradle 性能。

## 内置包来源

oc-rsync 固定官方 v0.6.4 Windows x64 发布 ZIP，下载时按 GitHub release digest 校验；内置 runtime.zip 包含未修改 rsync.exe、GPL-3.0、provenance 与逐文件 SHA-256；固定提交源码 tar.gz 位于 third_party/sources，由发行 SOURCE.md 和 SOURCE_SHA256SUMS 定位校验。不分发系统 OpenSSH 或 Cygwin。`tools/package_windows_rsync.py --system-ssh` 生成这类包，外部候选目录仍可用原 manifest 进行校验测试。
