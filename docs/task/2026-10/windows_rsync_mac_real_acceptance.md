# Windows → macOS 真实工程验收

> 当前方案已统一为 Cygwin + rsync，移除范围和最新验证见 [Cygwin 收敛记录](windows_rsync_cygwin_only.md)。本文双方案部分保留为历史取舍证据。

创建：2026-10-19。目标是本分支插件可交付 Windows 用户测试；本记录以本轮实测结果为准，不将早期 Linux 小夹具结论视为 macOS 证据。

## 范围与凭据

- 复用 RemoteGradleCompileClientTest，在 Windows 上实际通过 JSch、Windows rsync、Mac Gradle 构建 android_demo_project，并拉回真实 APK 与 classpath。
- 使用维护者提供的 remote-key.json、remote-pass.json，仅测试进程读取；不在文档、对话或提交记录账号、主机、密码、密钥路径及原始输出。
- 原配置不修改；Git 忽略目录中的测试副本使用独占 /tmp/jugg-real-mac-* 工作区、APK 文件名 app-debug.apk，并给远端命令设置 Mac SDK 的 ANDROID_HOME。原配置的 /home 路径不能在该 Mac 创建。
- 对内置 oc-rsync 和已安装 Cygwin 分别执行密钥/密码回归。真实工程测试检查 Manifest、DEX、资源修改已进入 APK，并检查 classpath 有 class/jar。

## 首轮失败与修正

1. 真实用例先在 MkDirCommand 失败：原配置使用 Linux 风格的 /home 目录。仅测试副本改用 /tmp。
2. Windows rsync 的 uname 校验只接受 Linux，会拦住 Darwin。局部放开为 Linux/Darwin，其他平台仍明确失败。
3. Windows 上传不能保留 Unix wrapper 执行位，实际 ./gradlew 返回 126。Windows rsync 源码同步完成后，只为本次 compileCommand 指定且位于工程内的相对 gradlew 设置执行位；非 Windows、仅拉取、其他命令不受影响。
4. Mac SDK 已存在，但 SSH 环境未配置 ANDROID_HOME；仅测试副本补齐，不修改远端用户配置或原始凭据。
5. Mac 的 Gradle 代理端口连接被拒绝；直接 Google Maven 预检返回 200，仅测试命令覆盖 http/https/socks proxyHost，不修改远端全局配置。
6. 真实 Gradle assembleDebug 成功后，只配置 APK 文件名的 FindOutputCommand 在 BSD find 返回 illegal option：GNU find 可省略搜索根而 BSD 不可。统一在空 findPath 时使用显式 `.`；包含目录的原路径不变，由真实 APK 拉取回归保护。
7. Mac 默认工具为 openrsync protocol 29（rsync 2.6.9 compatible）。oc-rsync 普通拉取通过，但 `--prune-empty-dirs` 单独即可触发 receiver IPC 14；加 `--no-inc-recursive` 仍失败。去掉 prune 后，简单锚定目录规则可通过，实际所需的 `--include=*/` 与相对目录规则仍失败。Cygwin 相同协议及过滤规则通过。已撤销无效的参数尝试，保留完整过滤/删除契约；仅在 Windows classpath 拉取预检检测到 openrsync protocol 29 时选择已安装 Cygwin，明确打印 info。默认 oc-rsync 模式的其他传输仍使用 oc-rsync；Cygwin 缺失明确提示安装，诊断 runtime override 仍优先。此组合不能宣称纯 oc-rsync 通过，由真实 Mac Flow 保护回退后的最终内容。
8. 模拟产物回归发现共享 conventional build 规则 `build/...` 漏掉嵌套模块。独立真实 rsync 小夹具中原规则退出 0 但文件数为 0，`**/build/...` 与工程根精确路径均拉到目标。统一为共享规则增加 `**/`，保持去重、不同 variant 和精确自定义目录策略；FetchClasspathCommandTest 更新协议断言，模拟 Flow 校验所有文件以防假成功。
9. Mac zsh 提示符控制字符可能与取消夹具的首行标记相连；夹具 printf 前补换行，避免将提示符时机误判为生产取消失效，不调整生产取消逻辑。

## 性能口径

真实 Jugg 用例分别记录首次构建、无修改构建、修改资源后的构建与 APK 拉取总耗时，以及每轮 classpath 拉取耗时。首次构建清理项目构建产物，但不清理 Gradle distribution、依赖缓存或系统缓存。

另以真实工程源码和 Jugg 实际从 Mac 拉回的 classpath/APK 快照执行传输 benchmark：全量、无变化、单文件变化上传；全量、无变化、APK 副本尾部变化拉取。修改仅发生在隔离副本，不改真实编译产物；每轮上传后计算远端 SHA-256、完成拉取后计算本地 SHA-256，逐文件比较。传输耗时含 SSH 启动，不含本地 cygpath 预转换、哈希验证或 Gradle 编译。按引擎顺序执行，未随机交错，缓存已预热。结果只代表本机、本网络、本工程，不外推至其他规模或 CPU/内存占用。

直接镜像整个远端 build（包含产品不需要的构建缓存）时 oc-rsync 失败，未把该能力当作通过。最终数据集限定为已通过 Jugg 内容断言的真实产物，源代码 276 文件/55,653,868 bytes，产物 456 文件/14,921,200 bytes（含真实 APK、347 class、4 jar）。此数据集的纯 oc-rsync 批量拉取也返回 IPC 14，因此工具保留上传的有效计时，将拉取标记为失败，并停止重复相同失败；不会替换成小文件或回退 Cygwin 伪造纯 oc 成功。

## 验收结果

| Settings 引擎模式 | 认证 | 真实工程三阶段 + 四项模拟 Flow |
|---|---|---|
| 默认 oc-rsync | 密钥 | 5 项通过；classpath 预检使用 Cygwin |
| 默认 oc-rsync | 密码 | 5 项通过；classpath 预检使用 Cygwin |
| Cygwin | 密钥 | 5 项通过 |
| Cygwin | 密码 | 5 项通过 |

四组合共 20 项测试通过，包含 12 次真实 Gradle 调用及 APK 内容、classpath、模拟全目录产物、完整 rsync、多工程、失败与取消断言。为导出 benchmark 快照另执行真实工程三阶段，亦通过。离线定向测试 56 项通过、4 项 Unix 专用用例在 Windows 跳过；Python 9 项通过，中英文 Wiki production build 通过。

真实 Jugg 编排计时示例（单次测量，秒；Gradle 依赖/daemon 已预热，首次阶段已 clean 项目输出）：

| 模式/认证 | 首次构建及 APK | 无变化构建及 APK | 资源变化构建及 APK | 三阶段 classpath 拉取 |
|---|---:|---:|---:|---|
| 默认 oc / 密钥快照复验 | 28.267 | 10.103 | 10.316 | 3.433 / 1.343 / 1.173（Cygwin） |
| 默认 oc / 密码 | 32.911 | 11.099 | 11.359 | 4.259 / 2.002 / 1.786（Cygwin） |
| Cygwin / 密码 | 29.126 | 11.588 | 11.241 | 4.392 / 1.801 / 1.699 |

原始账号、地址、密码、密钥路径和终端输出未进入本记录；脱敏矩阵、计时与哈希校验状态保存在 Git 忽略的 verification/windows-rsync。

传输 benchmark 三轮中位数（秒）：

| 引擎 | 全量上传 | 无变化上传 | 单文件变化上传 | 全量拉取 | 无变化拉取 | 单 APK 副本变化拉取 |
|---|---:|---:|---:|---:|---:|---:|
| oc-rsync 0.6.4 | 3.9788 | 0.5243 | 0.5698 | IPC 14，失败 | 未执行 | 未执行 |
| Cygwin rsync 3.3.0 | 3.9265 | 0.5048 | 0.4984 | 3.8913 | 0.4334 | 0.5503 |

本次真实工程上传全量耗时接近，Cygwin 增量上传约少 13%；拉取完整性只有 Cygwin 通过，不能给 oc 的失败拉取计算吞吐或性能优势。Cygwin 的上传与拉取全部逐文件哈希一致；oc 上传逐文件远端哈希一致，批量拉取首次 IPC 14 后不做相同重试，进程返回失败并保留有效上传样本。

插件 `:idea:buildPlugin` 与 `verifyThirdPartyCompliance` 通过。交付包为 `idea/build/distributions/jugg-3.5.4-feature_windows-rsync-SNAPSHOT.zip`；最终提交后重新构建，核对内置 runtime.zip、askpass.exe 和 SOURCE.md 对应提交，避免交付旧包。安装包不包含 Cygwin，使用条件见下节及同步后的中英文远端编译指南。

## 交付条件与剩余边界

该 Mac 的系统 openrsync 场景推荐 Windows 用户安装 Cygwin rsync/openssh，并开启 Settings 的临时 Cygwin 开关；默认 oc 模式也必须有 Cygwin 才能完成 classpath。纯 oc-rsync 无法独立满足本场景的多目录产物拉取要求，不能按之前 Linux 小夹具表现宣称其全面优于 Cygwin。Windows/OpenSSH、符号链接权限、服务器 SDK/JDK/依赖网络等前置要求仍需满足。

本轮验证真实远端 Gradle、APK 与 classpath；未执行真实 Android Studio 界面操作、APK 设备安装/热部署、非 NTFS/UNC、其他 Windows 架构或其他远端工具链矩阵。这些不冒充已通过。
