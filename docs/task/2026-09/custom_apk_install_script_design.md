# 自定义 APK 安装脚本方案

## 目标

系统应用或厂商工程可能不能通过 Android Studio deployer / `adb install` 安装，而需要执行指定 Gradle task 或项目脚本。Jugg 增加可选的自定义 APK 安装脚本，并在脚本安装成功后继续维护 deployment cache、overlay id 和后续增量部署状态。

## 用户行为

- Jugg Run Configuration 增加 `Enable custom APK install script` 开关。
- 未勾选时只显示开关，现有安装行为完全不变。
- 勾选后在下方展开单行高度的脚本输入面板，仅显示项目脚本示例占位提示。
- 脚本在本地工程根目录执行；macOS/Linux 使用 Bash，Windows 使用 `cmd.exe`。
- 脚本应用于普通 App APK 的全部 install/reinstall 边界，包括 Gradle 编译后的安装、recover reinstall、APK 更新后的重装和 embedded APK 安装。
- 纯增量 class/overlay 部署不执行脚本。
- androidTest APK 保持 Android Studio 默认安装，避免系统应用脚本误处理测试包。
- 沿用既有安装粒度，每台设备、每个普通 App applicationId 分别执行脚本。

## 脚本输入

Jugg 按原样执行输入内容，不注入设备、applicationId 或 APK 路径变量。脚本继承 Android Studio 进程环境以及 Jugg 已解析的 Gradle JDK、Android SDK 环境，并把当前 Android SDK 的 `platform-tools` 加入 `PATH`，因此可以直接调用 `adb`。Bash 不加载用户的 shell 启动文件。

脚本必须通过项目自身约定找到并安装 Jugg 本轮产出的 APK，在设备与 PackageManager 已可查询目标包后返回；如果脚本触发 reboot，应自行等待启动与包扫描完成。退出码 `0` 表示成功；非零退出码直接结束本轮部署，不自动重复有副作用的安装脚本。

## 状态与失败边界

- 脚本成功后等待 ADB transport 恢复，并确认目标包已安装。
- 复用现有 APK 解析、base overlay id 和 deployment cache 写入逻辑，保证下一轮增量部署仍有可信基线。
- 自定义脚本自身失败不执行 deploy retry 或 Gradle fallback，也不使用 Android Studio installer 的 transient retry。脚本成功后的其它部署失败沿用原有重试和回退，包括 test APK 安装失败后的卸载重试；重复执行的语义和副作用由业务脚本负责。
- 脚本输出进入 Jugg Run 窗口；用户取消 Run 时终止脚本进程。
- 自定义脚本只在显式开关启用且脚本内容非空时生效；未启用时走原有 installer，启用但内容为空时由运行配置校验报错。
- 远程编译后仍在连接设备的本地 IDE 主机执行脚本。

## 变更范围

### 运行配置

- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfigurationOptions.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunSettingsComponent.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunConfigurationOptionsExt.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ide/bean/JuggGradleCompileOptions.kt`

### 部署编排

- 新增自定义安装脚本执行类，复用现有命令输出与取消机制。
- 脚本配置由 Run Configuration 经 `DeployOptions`、deploy/recover 请求和 `LaunchContextFactory` 传入 `LaunchContext`；`CompileUiHandler` 只承载进度、输出和取消交互。
- `JuggDeployerHelper`、`DeployStateRecover` 和 run host bridge 继续复用原有请求对象，保证 recover/reinstall 不丢失配置。
- `JuggDeployTask` 仅在 INSTALL 分支为普通 App APK 创建 runner，并作为 `JuggDeployer.install()` 的可空参数传入；不保存跨安装的 runner 或已执行标记。
- `JuggDeployer` 保留统一的安装后 cache 初始化逻辑。

### 文档

- 同步 `03_deploy_system_app.md`、`03_deploy_core.md`、`03_deploy_complete.md`、`98_code_map.md`。
- 新增中英文自定义安装脚本 Wiki，并更新部署能力索引及相关安装页面。

## 验证

- L1：脚本执行环境、退出码、输出和取消行为。
- L2：自定义脚本绕过默认 installer；失败不写 cache；recover reinstall 保留脚本配置；androidTest APK 不使用脚本。
- L3：默认 install 主链不回归，并验证自定义安装后可继续一次增量部署。
- 定向测试后执行 `./gradlew :idea:compileKotlin`。
- Wiki 执行镜像、链接校验和 production build。

## 不在范围内

- 不内置 `/system/app`、`/system/priv-app`、权限白名单、remount、签名或厂商刷机逻辑。
- 不支持远程编译机直接访问本地设备。
- 不增加 installer SPI、脚本类型枚举或多套 task/script 配置模型。
