# AAPT2 host compatibility correction

Purpose: Restore the packaged x86-64 Linux and Windows host compatibility boundaries after the standalone cross-build migration.
Decision: Pin Zig 0.16.0, the Linux glibc 2.14 target and the Windows Vista target, and reject unreviewed binary dependency changes.
Impact: No ARM64 target or resource-link algorithm changes. Native runtime validation remains separate from artifact inspection.

## 范围与失败证据

- 只修正 Linux x86_64 与 Windows x64，不新增 ARM64。
- 迁移前参考提交为 `39669cb164147e5912b3732b69a2fce3e0911feb`。
- 旧 Linux 最高 GLIBC 符号要求 2.14；当前产物含 `pow@GLIBC_2.29`。编译包装脚本未指定 glibc 版本，Zig 默认目标使用 2.31。
- 旧 Windows PE 系统/子系统版本为 6.0，没有强制导入 `GetSystemTimePreciseAsFileTime`；新工具链默认 Windows 10，使 libc++ 时间实现直接导入该函数。

## 实施与验收

- 在仓库构建入口提供固定 Zig 版本、目标与 CPU baseline 的包装脚本生成，继续允许显式提供其他工具链。
- Release 构建必须通过架构、动态依赖及 Linux GLIBC/Windows PE 导入检查。
- 保存旧产物 Windows 导入表作为基线；工具链产生的新导入须单独审查，不能把整个新产物直接当成兼容基线。
- 先确认旧参考产物通过检查、当前回归产物失败，再重建和检查修正候选。
- 验证采用实际二进制兼容检查及已有 `smoke.py`，不增加绑定生产源码字符串的测试。
- Linux/Windows 原生环境未提供：本机无 Docker/Wine，UTM 无已注册虚拟机。不能将 macOS smoke 作为其他平台原生运行证据。

## 完成记录

- 两个平台使用独立干净目录 `/tmp/aapt2-compat-linux`、`/tmp/aapt2-compat-windows` 完整构建通过；最终脚本入口重跑也通过 Release 检查。
- Linux 最高符号要求恢复为 `GLIBC_2.14`；`pow` 恢复引用 `GLIBC_2.2.5`。依赖均在旧产物的允许集合内。
- Windows PE 系统/子系统保持 6.0，已无 `GetSystemTimePreciseAsFileTime` 强制导入；保留 `GetProcAddress`、`GetModuleHandleW` 和 `GetSystemTimeAsFileTime`，与固定目标下 libc++ 的动态查询/回退实现一致。
- 新增 Win32 导入逐项保存在 `reviewed_additions`：文件 API、模块查询、错误格式化、进程查询及 x64 SEH 均为 Vista 前已有 API。x64 SEH 导出的历史可复核 [Kernel32 export history](https://www.geoffchappell.com/studies/windows/win32/kernel32/api/index.htm)。新增 CRT 导入逐项匹配 Zig 0.16.0 所带 MinGW 的既有 UCRT `l1-1-0` 导出定义；没有新增 DLL。
- 实际检查结果：旧参考产物通过；修正前 Linux/Windows 产物分别因 GLIBC 2.29 / precise-time 强制导入失败；修正候选通过；错误 build number 被拒绝。采用真实产物检查作为回归证据，无需生产代码测试接缝。
- `./gradlew :aapt2-inclink:processResources --no-daemon` 通过；检查生成资源与候选的 SHA-256 一致，生成资源重新通过兼容检查。
- 两个平台打包资源已替换，macOS 资源未改。只调整工具链和发布检查，没有修改资源增量算法、调用方或数据格式。
- 原生 Linux/Windows `smoke.py` **未执行**。本次完成构建及静态兼容约束修正，仍缺最低目标环境的运行证据；不据此宣称完整运行兼容性已验证。

| Packaged artifact | SHA-256 |
|---|---|

| linux x86-64 | `b8de0752cfa490e72d3766943b733aaf5880ba360bf236aa920c3872549daf43` |
| windows x86-64 | `1cd261048297f551048d85dfc52c1ccc707d7ed0f7f1db1df4aab0e91263dc79` |
