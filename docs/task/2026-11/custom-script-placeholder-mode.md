# 自定义安装与签名脚本位置参数模式

## 需求与依据

用户要求直接支持 `adb -s "$2" install -r -d "$1"`，同时保留只填脚本路径时自动追加参数的约定；明确不兼容此前推荐、依赖末尾追加的嵌套 Bash 写法。

依据：`00_overview.md`、`99_index.md`、`98_code_map.md`、`03_deploy_core.md`、`03_deploy_system_app.md`、`05_utilities.md`、`06_testing.md`，以及 `CustomApkInstallScriptRunner`、`CustomApkSignScriptRunner`、`CmdExecutor`；Wiki 写作沿用已读 `10_wiki_authoring.md`、`10_wiki_architecture.md` 和中英文安装/签名能力页。

## 实现与边界

- 仅 macOS/Linux 启用文本检测。安装匹配完整字面 `$1` 或 `$2`；签名只匹配完整字面 `$1`，不误匹配 `$10`。
- 命中时先执行安全转义后的 `set -- …`，再执行用户命令，不追加参数；安装设置 APK 路径与 serial，签名设置临时 APK 路径。
- 未命中时保留既有末尾追加流程。Windows 始终保留 cmd 追加参数方式。
- 不解析 Shell 引号、转义或注释，不扩展 `${1}` 等其他语法；不兼容旧嵌套 Bash 写法。示例改为直接命令或脚本路径。
- 保留逐 APK 调用、取消、日志脱敏、验证、失败停止及缓存更新流程。未新增生产接口、类或测试专用注入点。
- 用户自行调整 help；不修改或提交运行配置帮助文案。同步安装/签名知识库、工具文档及中英文 Wiki。

## 验证

- 测试价值：真实脚本收到的 argv、命令是否被重复追加、检测边界属于稳定外部契约。安装复用 `CustomApkInstallScriptRunnerTest`；签名新增同责任 Runner 测试，避免依赖 APK/SDK 构建才能判断收参行为。均在 CmdExecutor 边界使用 Mockito，执行真实 Bash 并断言输出。
- 修改生产代码前，12 项测试中安装 4 项、签名 2 项失败；脚本自动追加和环境变量回归通过。
- 修改后执行 `:idea:compileKotlin` 以及安装 Runner（7 项）、签名 Runner（5 项）、JuggDeployerInstallTest（12 项）、脚本配置 UI（3 项）共 27 项定向测试，全部通过。
- 覆盖命令/脚本、复合命令、只引用 `$2`、`$10` 检测边界、含空格/引号/Unicode/分号/字面 `$HOME` 的路径、逐 APK 顺序与失败停止、缓存与失败边界。
- 未做 Windows 实机或真实 APK 签名服务验证；Windows 执行分支和后续签名校验流程未改变。
- Wiki 中英文路径、链接校验通过；本次暂存 diff 检查通过。运行配置 help 文件保留用户编辑，不纳入提交。
