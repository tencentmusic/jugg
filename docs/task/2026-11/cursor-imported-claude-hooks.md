# Cursor 导入 Claude Hook 的协议兼容与重复处理修复

## 问题与证据

报告 c8eb018c（Windows、Jugg 3.2.0-release）在 Cursor 调用 Jugg CLI 前，被 `command.py --client claude` 的 invalid JSON 拒绝。同一现场的 payload 含 `cursor_version`，且 Cursor/Claude 两套 Hook 均触发。本地 Cursor 3.20.21 / macOS 实际执行 echo，确认双执行但未复现拒绝。

## 实施

- 四类 Hook 在状态操作前统一识别实际 Cursor 宿主。
- 对当前事件识别用户级 Cursor 标准安装配置；存在原生 owner 时导入分支输出空 JSON 并结束；否则按 Cursor 协议完整执行。
- 兼容导入 Edit payload，统一状态作用域，修复 Start/Stop 与空编辑路径的空响应。
- 不增加事件缓存、时间窗口、跨进程锁或修改用户第三方导入设置。

## 验证

测试价值：保护外部 JSON 协议、会话状态、单次提醒与客户端兼容行为，适合 Python 子进程协议回归。owner 为现有 `test_hooks_guard.py`（L1），使用临时 home、真实 Hook 子进程和 fake Jugg CLI。

新增初始三条回归在生产修改前均因空 stdout 解析失败。最终 `python3 -m unittest discover -s docs/skills/hooks/tests -p 'test_*.py'` 通过 87 条回归；追加 Gradle command deny/allow 断言后，新增 6 条场景定向回归再次通过。`./gradlew help --console=plain` 成功生成资源 ZIP，五个 bundled Hook 脚本与源码逐字节一致，`git diff --check` 通过。覆盖 Claude 原路径、Cursor 原生、仅导入、双通道、四类事件跳过、异常配置回退、Stop 工程解析与只提醒一次。

## 边界与交付

去重采用配置归属，无法验证原生 Hook 本次已加载或执行成功；仅覆盖 Jugg 用户级标准安装配置。Cursor 仍会启动两次脚本，其中导入通道立即返回。Windows 用户原报错需更新 Hook 后现场验收。

知识库同步 `08_cli_tools_list.md`；现有 Wiki 安装步骤仍有效，无需修改。SKILL.md 从 1.0.39 升至 1.0.40（日期 2026-11-06），CLI 从 1.0.24 升至 1.0.25。自动更新重新安装已检测到的客户端 skill 时会解压整个资源包，覆盖共享 hooks；手动 Install Jugg Skills 仍可用于即时更新。

2026-11-06 10:17 复测发现本机仍为旧 Hook，根因为上次交付未递增版本，自动更新未触发。此次补齐版本并纠正此前对间接 Hook 更新链路的误判。版本变更使用资源 ZIP 内容检查和现有自动更新 owner 回归验证，不新增仅断言版本常量的测试。

补充验证：`./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.logic.JuggCliAutoUpdaterTest" --console=plain` 成功；自动更新 owner 的 11 条测试全部通过。新资源 ZIP 的 SKILL/CLI 版本与五个 Hook 脚本均逐字节匹配源码；本机版本 1.0.39 低于 bundled 1.0.40，满足升级条件。`git diff --check` 通过。
