# 远端同步根目录按实际 HOME 解析

## 问题与范围

默认远端目录按普通用户 `/data/home/<user>`、root `/root` 拼接，macOS 现场在 `/data` 创建目录时返回只读文件系统错误，但提示误导为同步客户端未启动。Linux 自定义 home 同样不能靠用户名推断。

仅调整运行参数的目录解析、SSH 使用边界、mkdir 错误信息及日志映射。保留同步协议、设置字段、持久化格式和显式绝对路径行为，不新增平台选项或认证重试。

## 实现

- 连接前以 `$HOME/remote` 或 `$HOME/<相对配置>` 展示目录；已配置绝对目录直接展示。
- SSH shell ready 后，首次使用远端目录时查询 `$HOME`。查询失败或结果不是绝对路径时提示配置绝对目录；显式绝对路径跳过查询。
- 运行参数通过 transient 字段共享本次连接的绝对目录，使构建、推送、拉取和日志映射一致。重新登录清除解析结果，不持久化到运行配置。
- mkdir 失败保留目标、退出码和 `mkdir:` 原始原因。

## 验证

- 失败证据：既有 macOS 现场 `/data: Read-only file system`；新增远端回归在旧实现下确认默认目录定位错误、无效 HOME 未被拒绝、mkdir 失败结果缺少目标与原因。
- 测试价值：保护真实 SSH 工作目录与失败契约，不新增测试专用注入点。复用 `RemoteGradleCompileClientTest` 远端 Flow owner，并回归现有 shell、运行参数与传输流程。
- 定向验证通过：main 层 39 项（38 通过，1 项因 Windows 平台跳过）；真实 macOS SSH Flow 9 项全部通过，覆盖默认目录、相对目录、显式目录、自定义 HOME、无效 HOME、mkdir 错误、简单/完整 rsync、多项目和取消。默认目录 Flow 已核对远端源文件内容及拉取的 APK/classpath 产物；:idea:compileKotlin 通过。
- Wiki 已明确默认目录为 `$HOME/remote`，本次让实现符合现有说明，未改变操作入口，无需同步 Wiki。
