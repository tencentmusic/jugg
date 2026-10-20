# Windows rsync 统一使用 Cygwin

> 当前工具选择已调整为只使用 PATH 首个 rsync，见 [PATH 与环境引导](windows_rsync_path_setup_prompt.md)。以下保留单方案收敛时的实现和验收记录。

## 范围与依据

用户确定后续只使用 Cygwin + rsync。此前真实 Mac 验证发现 oc-rsync 0.6.4 与系统 openrsync protocol 29 的多目录拉取不兼容（IPC 14），详见 [Mac 验收](windows_rsync_mac_real_acceptance.md)。本任务移除已放弃的方案，不增加自动安装或新的用户配置。

## 实现

- Windows 全部 rsync 传输统一使用已安装 Cygwin 的 rsync、ssh、cygpath；检测 JUGG_CYGWIN_HOME、C:/cygwin64、D:/cygwin64 和 PATH，缺失明确提示安装 rsync/openssh 包。
- 移除 Settings 临时开关、持久字段及模型接线、候选运行时覆盖、manifest/哈希解包缓存、系统 SSH 别名、远端 openrsync 引擎切换预检。
- 删除 oc-rsync 二进制、对应源码归档、候选打包脚本与其专属测试，更新第三方清单、notice、SBOM、校验和及生成器条目数。
- 保留密码/私钥认证、内置 askpass、三种同步布局、真实退出码/取消、classpath 原生链接单次恢复，以及非 Windows 原有行为。
- 独立验收和真实工程 benchmark 仅保留 Cygwin；历史双方案验收数据保留为取舍证据。同步测试配置说明、知识库与中英文远程编译指南。

## 验证策略与结果

复用 WindowsRsyncRuntimeTest（进程参数、认证隐私、原生链接恢复、缺失安装）、FetchClasspathCommandTest、SyncFileCommandTest 及 ControlPanel/Settings 定向回归；删除已失去业务能力的包哈希校验及引擎开关测试。无需为删除的实现结构新增断言。

真实 Mac 使用既有 RemoteGradleCompileClientTest：两种认证分别运行模拟产物三布局、取消和真实工程三阶段编译/拉取。原始私有 JSON 不输出、不修改，仅由进程生成隔离测试配置副本，结束后删除。

- 定向单测 25 项通过，4 项 Unix 专用用例在 Windows 跳过；两种认证各 5 项远程 Flow 全部通过，共 10 项，含 6 次真实 Gradle 编译及对应 APK/classpath 校验。
- Python 6 项通过；Wiki 镜像/链接校验、production build 和中英文 HTML 内容核对通过；第三方 inventory/SBOM 的 104 项对应关系及剩余源码 SHA-256 全部核对通过。
- 移除双方案分支后的真实工程 benchmark 串行冒烟 1 轮通过，覆盖全量、无变化、增量推送/拉取，逐普通文件 SHA-256 核对通过。原三轮性能基线仍见历史 Mac 验收记录。
- 首次运行曾出现 Cygwin 异常退出 0xC0000043、rsync 连接提前关闭（12）；独立 SSH 复查成功，停止并行传输后，两种认证完整 Flow 和 benchmark 串行复验通过。该异常未通过新增生产重试掩盖，不据此声称已定位系统级根因。
- 本机缺少 Ruby，第三方生成物按现有生成器相同算法更新，并核对 inventory、notice、SBOM 和源码校验和；生成器及 Gradle 发行合规检查条目数同步为 104；首次打包由旧 105 项门禁准确拦截，修正后重跑。
- 最终提交打包执行 :main:cleanProcessResources，防止旧 runtime.zip 残留；交付前检查最终 ZIP 中无 oc-rsync 资源/清单、askpass 保留，且 SOURCE.md 对应最终提交。设备安装和 IDE 图形界面操作不在本轮验证范围。

## 使用入口

安装 Cygwin x64 并选择 rsync、openssh；自定义目录设置 JUGG_CYGWIN_HOME 后重启 IDE。已有远程编译配置直接使用密码或 key，不再选择 Windows 引擎。详细 server 和测试配置见 [测试准备](windows_rsync_test_setup.md)。
