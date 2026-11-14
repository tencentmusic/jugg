# Windows rsync 内部探测输出收口

## 问题与范围

现场日志确认远端系统检查成功标记、cygpath 路径转换结果会进入控制台；版本探测同样复用命令输出通道，ssh 的正常版本信息还写入 stderr。

只隐藏这些内部命令的正常输出，保留失败诊断、退出码、取消语义和实际 rsync 传输输出。不改变同步编排与重试行为。

## 实现与验证

- 原生命令增加按调用启用的成功静默模式，缓存 stdout/stderr，失败时按原通道展示；输出收集保持有效。
- 版本探测和路径转换启用该模式；远端系统检查仅过滤成功完成标记。
- 测试价值：控制台可见性、失败诊断与退出码是稳定行为，在已有 CmdExecutorProcessTest 用真实子进程覆盖（L1），复用 WindowsRsyncRuntimeTest 回归环境与取消行为。
- 修复前 CmdExecutorProcessTest 新增用例在 `verifyNoInteractions(listener)` 失败（6 项中 1 项失败），证明成功输出泄漏。
- 修复后定向执行 CmdExecutorProcessTest、WindowsRsyncRuntimeTest、BaseSshCommandTest：18 项通过，1 项 Unix shell 用例在 Windows 按条件跳过，0 失败；包含真实子进程与 Cygwin runtime 回归。`git diff --check` 通过。
- 未安装插件或执行 IDE 远端构建复测；本次不改变编译、部署编排。
- 同步公共工具知识库。Wiki 的操作步骤、支持范围和排查建议未失准，无需修改。
