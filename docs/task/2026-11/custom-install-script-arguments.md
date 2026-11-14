# 自定义安装脚本参数

## 已确认范围

保留逐设备、逐 applicationId 调用。install 命令末尾追加两个位置参数：本轮 APK 绝对路径列表（以 `;` 分隔）和当前设备 serial。分别进行 shell 转义，路径自身包含 `;` 时在执行脚本前报错。sign 参数行为保持不变。

修改 CustomApkInstallScriptRunner、JuggDeployer 及对应测试，更新安装知识库和中英文安装 Wiki。用户追加要求：安装与签名输入框的 e.g. 提示同时说明自动追加的参数。新增本方案记录实施及验证。

## 验证策略

现有代码直接执行 script，未传递 APK 或 serial，作为缺失行为证据。参数属于外部脚本协议，值得自动化保护：在现有 Runner 测试中通过真实 Bash 读取 argv，覆盖多 APK、空格、引号和特殊字符；拒绝含分号路径。复用安装与部署 Flow 回归，验证默认 installer、androidTest 和失败收口。

编译检查、Wiki 镜像/链接检查、production build 与 diff 检查。完成后委派只读审查，只提交本次范围。

## 执行结果

- 已实现两个 install 参数、分号路径拒绝和 install/sign 自动追加参数提示；脚本仍使用 secure command，未改动日志内容。
- 测试价值：外部脚本 argv 是稳定可观察协议；在原有 Runner owner 增加真实 Bash 参数断言（L2），未增加生产测试 seam。新增测试先因原接口不接受 APK 列表而编译失败；实现后 Runner 3 项、安装回归 12 项通过。
- 定向命令：`ANDROID_HOME=/Users/wormchen/Library/Android/sdk ./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.deploy.run.applychanges.CustomApkInstallScriptRunnerTest' --tests 'com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployerInstallTest'`。
- 已尝试等价虚拟设备 Flow `JuggDeployerHelperDeployFlowTest`：第一次缺少 ANDROID_HOME；补齐后 demo 的 assembleDebug 在 DexWorkAction/R8 3.3.83 内部发生 NullPointerException，未进入 Flow 断言。本任务不修改 demo 构建。
- Wiki 语言镜像/链接检查、production build 及中英文 HTML 参数示例检查通过；未执行 Windows cmd.exe 实机验证。
- `git diff --check` 通过。只读审查发现 Windows cmd.exe 在双引号内仍展开 `%` / `!` 环境变量；已在参数编码边界明确拒绝并同步中英文限制。新增测试独立运行发现 PlatformApi 初始化依赖，已显式初始化 TestGlobal；最终定向命令通过，15 项测试无失败。

- 委派只读审查完成；Windows 参数展开问题修复后复查通过，无剩余问题。
