# Windows cygpath 共享冲突单次恢复

## 问题与证据

MyApplicationIntellij 在 2026-10-27 22:22:16 的 cygpath 进程以 -1073741757（0xC0000043 / STATUS_SHARING_VIOLATION）退出，源码同步中断。22:22:47 后同一工具恢复，上传成功；尚未确认冲突文件及占用来源。

## 实施边界

- 仅参数和环境准备阶段的 path_conversion_failed 且结构化退出码为 -1073741757 时，等待至少 3 秒重试一次。
- 复用 RemoteGradleCompileClient 的取消状态及执行器现有启动取消窗口；等待期间每 100 毫秒检查取消，不重放实际 rsync 传输。
- WindowsRsyncSetupException 增加可空退出码，保留原有四参数 JVM 构造入口；WindowsRsyncRuntime 透传真实结果，不解析提示文案，不修改 CmdExecutor。
- 第二次失败沿原处理发布最终异常；其他错误、版本探测和符号链接恢复保持原行为。

## 验证

恢复策略具有独立可观察结果，通过测试价值门禁。L2 owner 为 RemoteGradleCompileClient，使用 Mockito 隔离 SSH 和 Cygwin，经公开 fetchClasspathResult 入口验证恢复、环境准备失败恢复、重试耗尽、普通错误、正常路径和取消。真实共享冲突无法稳定制造，使用历史现场作为失败证据，模拟进程结果验证恢复编排；不宣称已经消除系统层冲突。

旧实现先运行五个准备阶段测试，恢复、重试耗尽和等待期间取消三项按预期失败；普通错误和正常路径通过。

最终验证：

- `:main:test --tests '*WindowsRsyncPreparationTest' --tests '*WindowsRsyncRuntimeTest' --tests '*CmdExecutorCancellationTest'`：19 项通过，1 项因平台条件跳过。包含至少 3 秒延迟、取消后不再准备或传输、最终异常对象原样上报，以及 cygpath 退出码透传。
- `:idea:test --tests '*RemoteGradleCompileClientTest.testSimulatedArtifactFetch' --tests '*RemoteGradleCompileClientTest.testSimulatedCancellation'`：两项失败，现有仓库外 `remote.json` 对应 SSH 服务连接超时；产物测试在登录/清理阶段失败，取消测试等待远端就绪超时，未完成真实传输与取消回归。其他现有 `remote-key.json`、`remote-pass.json` 的 TCP 连接检查也超时，未重复执行相同失败条件下的 Flow。
- 上述命令包含主代码、IDE Kotlin 及 IDE 测试源码编译，均通过。`javap` 确认旧四参数 Java 构造入口保留；`git diff --check` 通过，新增日志按字符串拼接处换行并核对缩进。

未验证：真实 Windows 共享冲突的占用来源及 3 秒是否足以覆盖其持续时间；可用 SSH 服务上的真实 Flow；设备部署。本次模拟验证不能代替这些现场结果。

## 文档影响

同步兼容、工具和远端编译知识库的重试边界。操作入口、支持范围及故障后的手动恢复建议不变，无需修改 Wiki。
