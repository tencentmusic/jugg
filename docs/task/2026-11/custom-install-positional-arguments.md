# 自定义安装逐 APK 位置参数

> 状态：其中 macOS/Linux 预设位置参数、取消末尾追加及显式脚本传参的约定已撤销。当前约定见 [恢复安装脚本自动追加参数](./restore-install-script-appended-arguments.md)。
## 已确认范围

用户已确认：每 APK、每设备执行一次 install 命令。macOS/Linux Bash 预先设置 `$1` 为当前 APK 绝对路径、`$2` 为当前 serial，取消末尾追加；已有脚本须显式传参。Windows 保留 cmd 追加参数约定，每次传单 APK 和 serial。移除分号列表及分号路径限制，保留 Windows `%` / `!` 限制。

install/sign 标题右侧使用平台圆形问号帮助图标，介绍直接命令、项目脚本、多条命令、参数引用以及平台区别。sign 保持既有追加 APK 参数行为。同步知识库和中英文安装 Wiki。

## 变更文件

- CustomApkInstallScriptRunner：单 APK 位置参数执行。
- JuggDeployer：保留现有整组调用；Runner 内逐 APK 执行，全部成功后沿用统一校验缓存，减少 API 改动。
- JuggRunSettingsComponent：标题帮助与简短示例。
- Runner、Install 和设置 UI 对应测试：协议、逐次执行、失败收口与正常路径；DeployFlow 沿用此前 demo R8 构建失败的限制记录，不重复运行相同阻塞。
- 系统应用知识库、中英文安装 Wiki、本方案。

## 验证

旧实现只追加列表，不能直接引用 `$1/$2`，先写协议失败测试。真实 Bash 验证直接命令、显式脚本参数、多条命令、含分号路径和引号等特殊字符。安装 owner 验证逐 APK 顺序及中途失败不提交缓存。执行定向测试、Wiki 验证/构建、diff 检查，再委派只读审查。

## 结果

- 旧实现的真实 Bash 协议测试失败（路径含分号时仍被拒绝），完成实现后通过。
- 定向 Runner（5 项）、安装回归（12 项）、自定义脚本 UI（3 项）通过。命令：`ANDROID_HOME=/Users/wormchen/Library/Android/sdk ./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.deploy.run.applychanges.CustomApkInstallScriptRunnerTest' --tests 'com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployerInstallTest' --tests 'com.sickworm.intellij.jugg.ide.logic.JuggRunSettingsComponentTest.*custom APK*'`。
- 逐 APK 循环留在 Runner，JuggDeployer API、最终包检查及 cache 更新顺序均无需修改。mock API 不变，因此 Install、DeployFlow 测试文件也无需调整。
- 全设置 UI 类执行暴露了两项本次范围外失败：control panel section 数量 9/10 不一致、SO toggle mock coroutineContext 为 null；未扩大修复。标题改动影响的三项脚本 UI 用例已调整定位并通过，删除了精确 placeholder 和无 JLabel 等实现细节断言。
- Wiki 镜像/链接检查、production build、生成 HTML 直接命令示例检查通过；Windows 未实机验证。
- 先前 Flow 已因 demo assembleDebug 的 R8 内部 NPE 阻塞，当前未重复执行同一阻塞。
- `git diff --check` 通过。委派只读审查通过，未发现剩余实际问题。
