# Windows rsync 测试环境与服务器配置

## 本次范围

维护者要求两种候选先验证，通过后达到插件可用于 e2e 测试的状态：oc-rsync 内置，Cygwin 检测并提示用户安装。当前 Cygwin 已接入安装检测；oc-rsync 0.6.4 在树外相对链接上传时失败，未达到内置条件。本轮没有分发第三方二进制。弱服务器使用脚本生成小产物，不运行真实远端 Gradle；结论见 [验收记录](windows_rsync_acceptance.md)。

独立 PoC 入口和原始传输报告见 [工具说明](../../../tools/windows_rsync_probe.md)。真实客户端入口见 `idea/src/test/java/com/sickworm/intellij/jugg/gradle/RemoteGradleCompileClientTest.kt`。这些是需要服务器的集成测试；离线 JVM 测试不需要服务器。

## 1. Linux server

准备一个专用 SSH 测试账户，允许非交互私钥认证、SFTP/普通文件读写以及 shell session（JSch 使用 shell channel，不是只有 exec 的受限账户）。账户须能运行 Bash、rsync、项目 Gradle wrapper，且可写 `remoteSyncPath`、用户 Gradle 缓存及 Android SDK。使用 Linux 上游 rsync，记录实际版本和发行来源。

在服务器安装与 `android_demo_project` 匹配的 JDK、Android SDK/platform/build-tools，按项目需要接受 SDK 许可。服务器需要访问项目依赖仓库和 Gradle distribution，或事先填充缓存。无需在 server 上安装 Android Studio，也不要求运行 Android 设备。独立 PoC 另需 Python 3、mktemp、stat。

把 Windows 测试用私钥对应的公钥加入该账户 `~/.ssh/authorized_keys`；目录仅账户可写，SSH 配置允许公钥登录。私钥保留在 Windows，使用无交互解密的专用测试 key。需要同时被 JSch 与所选 OpenSSH 接受；不要只检查一种 SSH 客户端。

服务器端验证示例（路径替换为实际安装位置）：

```bash
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
java -version
rsync --version
mkdir -p /home/testuser/jugg-rsync-tests
test -w /home/testuser/jugg-rsync-tests
```

测试会将本仓库 `android_demo_project` 同步到指定远端目录下同名子目录；上传包含 `--delete`。必须使用专用测试目录，避免与个人项目或两个并发测试共享远端工作区。设置非默认 SSH 端口时同时修改两份配置中的端口。

## 2. Windows runtime

### 当前可测试的 Cygwin 入口

安装 Cygwin x64，并在安装器中选择 `rsync`、`openssh`。插件默认检测 `C:/cygwin64`、`D:/cygwin64` 和 PATH 对应的安装根目录；自定义目录通过环境变量指定，设置后需重启 IDE/测试进程：

```powershell
$env:JUGG_CYGWIN_HOME = 'D:/cygwin64'
Remove-Item Env:JUGG_WINDOWS_RSYNC_RUNTIME -ErrorAction SilentlyContinue
$env:JUGG_REMOTE_CONFIG_FILE = "$HOME/.jugg/remote.json"
.\gradlew.bat :idea:test --tests 'com.sickworm.intellij.jugg.gradle.RemoteGradleCompileClientTest.testSimulatedArtifactFetch' --tests 'com.sickworm.intellij.jugg.gradle.RemoteGradleCompileClientTest.testSimulatedCancellation'
```

上述模拟用例只需 Linux SSH shell、rsync、Python 3，以及 `/tmp` 写权限，不要求远端 JDK/Android SDK。它们读取原配置中的连接字段，在内存中覆盖测试路径和 compileCommand；不会改写配置文件。真实上传后由 Python 生成全部 classpath 路径和小 APK，验证下载、链接、失败与取消；不会把模拟 APK 用于设备安装。测试结束清理独占远端目录。

外部 SSH 显式使用公钥认证并隔离用户 SSH 配置，不支持依赖该配置的 ProxyCommand/跳板机；known_hosts 仍使用 Windows 用户目录。Cygwin 子进程开启 `winsymlinks:nativestrict`；需要 Windows 原生 symlink 权限。首次拉取目标尚未到达时的链接 ENOENT 只恢复一次，仍失败则返回失败。

### 两种候选的独立对照

```powershell
python tools/windows_rsync_acceptance.py --config "$env:JUGG_REMOTE_CONFIG_FILE" --cygwin-root D:/cygwin64 --oc-rsync D:/Downloads/oc-rsync-0.6.4-windows-x86_64/rsync.exe
```

复用原 PoC 文件/链接夹具，增加全部产物目录和独立 known_hosts；配置只在子进程内读取，不输出命令、账号、key 或原始 stderr。脱敏报告写入被 Git 忽略的 `verification/windows-rsync/acceptance/preflight-report.json`。任一候选失败时脚本退出 1，不能据此误判另一个候选未完成。

### 保留的候选包工具

oc-rsync 和 Cygwin 各准备一个独立 staging 目录，包含实际需要的 exe/DLL、`licenses/` 和 `provenance.json`。provenance 记录固定版本、源码提交或源码包、下载来源、补丁审核结果；许可证目录必须放实际材料。脚本不审核许可证充分性和 DLL 闭包，仍须按最终方案实测。

默认目录为 `bin/rsync.exe`、`bin/ssh.exe`；Cygwin 另含同一安装版本的 `bin/cygpath.exe`。oc-rsync 的 SSH 必须为 Windows 原生 OpenSSH，不能混用 Cygwin SSH。可以用 `--rsync`、`--ssh` 调整 ZIP 内的相对路径。

使用 Python 3.9+ 生成候选包和逐文件哈希清单，再解压到不同目录：

```powershell
python tools/package_windows_rsync.py --input D:/rsync-stage/oc --output D:/rsync-packages/oc.zip --engine oc-rsync
python tools/package_windows_rsync.py --input D:/rsync-stage/cygwin --output D:/rsync-packages/cygwin.zip --engine cygwin
Expand-Archive D:/rsync-packages/oc.zip D:/rsync-test/oc
Expand-Archive D:/rsync-packages/cygwin.zip D:/rsync-test/cygwin
```

只在当前测试进程环境设置 `JUGG_WINDOWS_RSYNC_RUNTIME`，值为含 `runtime.json` 的解压目录。每轮选择一个，不自动换引擎。runtime 文件会先按清单校验，损坏的外部测试目录直接失败。Cygwin 子进程使用 `winsymlinks:nativestrict`；开发者需自行确认 native symlink 权限，不能把兼容链接当作验收成功。

## 3. Remote testcase JSON

在仓库外保存例如 `D:/private/jugg-remote-test.json`：

```json
{
  "compileCommand": "./gradlew :app:assembleDebug --rerun-tasks",
  "outputApkName": "app/build/outputs/apk/debug/app-debug.apk",
  "remoteSshUser": "testuser",
  "remoteSshPassword": "C:/Users/Admin/.ssh/jugg_test_key",
  "remoteSshIp": "192.0.2.10",
  "remoteSshPort": 22,
  "remoteSyncPath": "/home/testuser/jugg-rsync-tests",
  "environmentVariables": "JAVA_HOME=/path/to/jdk;ANDROID_HOME=/path/to/android-sdk;PATH=/path/to/jdk/bin:$PATH",
  "httpProxyIp": "",
  "httpProxyPort": 0
}
```

`remoteSshPassword` 沿用现有字段名，这里填 Windows 私钥绝对路径。不要填登录密码或私钥文本。Windows rsync 首期不支持交互密码、HTTP proxy、SSH agent 或私钥解密提示；JSch 能登录不代表外部 SSH 也能登录。native/Cygwin OpenSSH 共用 Windows 用户目录 `.ssh/known_hosts`，首次连接 accept-new，已知主机 key 改变必须人工核查。

## 4. 执行现有 testcase

从新的 PowerShell 会话启动 Gradle，保证环境传给测试进程。每轮切换 runtime 后重新执行，保存报告并记录候选 ZIP 哈希：

```powershell
$env:JUGG_REMOTE_CONFIG_FILE = 'D:/private/jugg-remote-test.json'
$env:JUGG_WINDOWS_RSYNC_RUNTIME = 'D:/rsync-test/oc'
.\gradlew.bat :idea:test --tests 'com.sickworm.intellij.jugg.gradle.RemoteGradleCompileClientTest.testCompile' --rerun-tasks
.\gradlew.bat :idea:test --tests 'com.sickworm.intellij.jugg.gradle.RemoteGradleCompileClientTest.testFetchClasspath'
.\gradlew.bat :idea:test --tests 'com.sickworm.intellij.jugg.gradle.RemoteGradleCompileClientTest.testCancel'

$env:JUGG_WINDOWS_RSYNC_RUNTIME = 'D:/rsync-test/cygwin'
.\gradlew.bat :idea:test --tests 'com.sickworm.intellij.jugg.gradle.RemoteGradleCompileClientTest.testCompile' --rerun-tasks
.\gradlew.bat :idea:test --tests 'com.sickworm.intellij.jugg.gradle.RemoteGradleCompileClientTest.testFetchClasspath'
.\gradlew.bat :idea:test --tests 'com.sickworm.intellij.jugg.gradle.RemoteGradleCompileClientTest.testCancel'
```

先 compile，再 fetchClasspath，最后 cancel。取消用例监听 `:preBuild`，示例 compileCommand 的 `--rerun-tasks` 用于确保触发真实构建。不要并发跑两种候选。Gradle 报告在 `idea/build/reports/tests/test/`，复跑前保存上一轮；Jugg 详细日志位于测试工程对应全局日志目录。缺少 `JUGG_REMOTE_CONFIG_FILE` 时整类 skipped，不代表通过。

离线定向测试：

```powershell
.\gradlew.bat :main:test --tests 'com.sickworm.intellij.jugg.gradle.compile.CmdExecutorProcessTest' --tests 'com.sickworm.intellij.jugg.gradle.compile.WindowsRsyncRuntimeTest' --tests 'com.sickworm.intellij.jugg.gradle.compile.FetchClasspathCommandTest'
```

## 5. 上线收尾与未完成验证

只有 oc-rsync 达到必要契约后才将候选 ZIP 放入 `main/src/main/resources/tools/windows/rsync/runtime.zip`。Cygwin 按维护者最新要求使用用户安装，不制作随插件发布的 Cygwin 包。保留候选 runtime 环境变量用于显式验收，不在失败时自动切换引擎，也不新增公开双引擎设置。

上线前仍须完成最终方案的 Windows 矩阵、第三方组件来源/补丁/许可审核和干净环境 DLL 闭包、真实源码到 Linux Gradle 到 APK/classpath 到增量初始化及设备部署的 Flow，以及 Mac/Linux/iFT 回归。`RemoteGradleCompileClientTest` 保护远端客户端行为，不等价于完整设备部署 Flow。服务器未就绪时不能将这些步骤记为通过。

本次验证结果最终回写 [主方案](windows_rsync_final_plan.md)。Wiki 的公开支持范围在实际验收前保持不变。
