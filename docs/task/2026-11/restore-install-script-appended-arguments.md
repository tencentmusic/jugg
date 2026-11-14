# 恢复安装脚本自动追加参数

> 后续调整：当前按位置参数引用选择传参模式，见 [自定义脚本位置参数模式](./custom-script-placeholder-mode.md)。没有引用位置参数的脚本仍保留本方案的自动追加行为。

## 问题与依据

用户指出安装脚本原先与签名脚本一样，由 Jugg 自动追加参数；3c70cdb0 改成仅预设外层 Bash 的 `$1/$2` 后，原先只填写脚本路径的配置收不到参数。检查旧提交、`CustomApkInstallScriptRunner` 和 `CustomApkSignScriptRunner`，确认差异发生在命令组装边界。

## 实施范围

- 所有平台统一执行 `配置命令 + 当前 APK 绝对路径 + 当前设备 serial`，每个参数按现有宿主 shell 规则转义。
- 保留逐 APK、逐设备调用、顺序及失败停止、后续安装验证与缓存更新契约。
- 安装脚本仅需填写路径，固定选项在自动追加的两个参数之前；macOS/Linux 直接命令用 `bash -c '…' --` 接收参数，与签名直接命令的写法一致。
- 恢复输入框的脚本路径占位示例，纠正帮助文本、知识库及中英文 Wiki。保留原生提示排版。
- 原位置参数方案标记为被本方案替代，不引入脚本类型猜测或兼容分支。

## 验证

- 测试价值门禁：脚本实际收到的 argv 是稳定外部契约，具备自动化价值。复用 `CustomApkInstallScriptRunnerTest`，mock 进程执行边界后运行真实 Bash，检查实际输出参数，测试不依赖私有命令字符串。
- 修复前：5 项中 4 项失败；只填写脚本路径、直接命令、复合命令和逐 APK 调用均收到空参数；环境变量用例通过。
- 修复后：执行 Runner、`JuggDeployerInstallTest`、脚本配置 UI 定向用例，以及 `:idea:compileKotlin`。覆盖含空格、引号、Unicode、分号和 `$HOME` 的 APK 路径、含分号 serial、按顺序调用及失败停止、安装后缓存和失败边界。
- Windows 追加分支未改变，未做 Windows 实机验证。
- 结果：修复后上述定向测试及编译通过，Runner 5 项、安装 owner 与脚本 UI 回归均通过；Wiki 镜像/链接校验及 `git diff --check` 通过。
