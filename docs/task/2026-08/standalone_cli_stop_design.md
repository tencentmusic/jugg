# Standalone CLI Stop 方案

> 已由 `standalone_multi_project_runtime.md` 的 stop-all 语义取代；以下内容仅保留历史设计记录。

## 背景

Standalone Runtime 由全局 CLI 按项目自动拉起，并以独立进程持续运行。当前 CLI 没有停止命令；用户只能先通过 `pgrep` 查找 `StandaloneBootstrap`，再手工执行 `kill`。该方式依赖平台命令，且容易误停其他工程的 Runtime。

## 已批准行为

新增项目级命令：

```text
jugg stop
jugg --project-dir <path> stop
```

- 命令只控制 standalone CLI Runtime，不停止或修改 IDEA Runtime。
- 根据目标 Gradle 工程的规范化路径，仅停止命令行参数中注册了该工程的 `StandaloneBootstrap` 进程。
- 命令不执行 Runtime 端口发现，不会因为停止操作启动新的 Runtime。
- 平台支持正常终止时，先请求进程正常退出并等待最多 5 秒；仍未退出时强制终止。不支持正常终止的平台直接强制终止。
- 未找到目标 Runtime 时幂等成功。
- 保留 run configuration、Compile Context、编译历史、日志和其他项目状态。
- 如果一个 standalone daemon 显式注册了多个工程，命中任一目标工程会停止该 daemon，并在输出中说明被停止的进程。

## 实现方案

### CLI

- 在 `docs/skills/jugg-android-dev-loop/scripts/jugg.py` 注册 `stop`。
- 在 `docs/skills/jugg-android-dev-loop/scripts/py/help_registry.py` 增加命令帮助。
- 新增 `docs/skills/jugg-android-dev-loop/scripts/py/cmd/cmd_stop.py`。
- `cmd_stop.py` 只解析项目目录并同步调用已安装的 standalone launcher：

  ```text
  jugg-standalone --stop-project <canonicalProjectDir>
  ```

- `--runtime idea` 明确失败；默认和 `--runtime standalone` 均只执行 standalone 停止逻辑。

### Standalone bootstrap

- 在 `cmd_line/standalone_bootstrap/.../StandaloneBootstrap.java` 增加 `--stop-project` 控制模式。
- 控制模式在加载 active Runtime JAR 前执行，因此 Runtime 启动卡住或 active Runtime 无法完成初始化时仍可停止目标进程。
- 使用 Java `ProcessHandle` 枚举进程，排除当前控制进程，并同时校验：
  - 主类参数包含 `com.sickworm.intellij.jugg.bootstrap.StandaloneBootstrap`；
  - 参数中的 `--project-dir` / `--project-dir=<path>` 与目标工程 canonical path 一致；
  - `-Djugg.root.dir` 归属与当前 Jugg 根目录一致，未显式提供时按 `~/.jugg` 处理。
- 对 `supportsNormalTermination()` 为 true 的进程先调用 `destroy()` 并等待最多 5 秒；其余进程和等待后仍存活的进程调用 `destroyForcibly()`，再校验退出结果。

## 变更范围

预计修改：

- `docs/skills/jugg-android-dev-loop/scripts/jugg.py`
- `docs/skills/jugg-android-dev-loop/SKILL.md`
- `docs/skills/jugg-android-dev-loop/references/cli_manual.md`
- `docs/skills/jugg-android-dev-loop/scripts/py/help_registry.py`
- `cmd_line/standalone_bootstrap/src/main/java/com/sickworm/intellij/jugg/bootstrap/StandaloneBootstrap.java`
- `cmd_line/standalone_bootstrap/src/test/java/com/sickworm/intellij/jugg/bootstrap/StandaloneBootstrapTest.java`
- `cmd_line/src/test/java/com/sickworm/intellij/jugg/cmdline/CmdLineDistributionArchitectureTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggCliAutoUpdaterTest.kt`
- `docs/skills/jugg-android-dev-loop/tests/test_cmd.py`
- `docs/ai_knowledge/08_cli_tools_list.md`
- `docs/ai_knowledge/04_engineering_project.md`
- `docs/ai_knowledge/98_code_map.md`

预计新增：

- `docs/skills/jugg-android-dev-loop/scripts/py/cmd/cmd_stop.py`

不修改 MCP action、`IMcpRuntime`、IDEA Runtime 或 run configuration 存储。

## 验证方案

- Python CLI 定向测试：
  - `stop` 被公开且 help 完整；
  - `--runtime idea` 被拒绝；
  - 调用 launcher 时使用规范化项目路径；
  - launcher 返回非零状态时 CLI 正确失败；
  - stop 路径不调用 Runtime 端口发现和自动启动逻辑。
- Standalone bootstrap 进程级测试：
  - 只停止目标工程 daemon；
  - 其他工程 daemon 保持运行；
  - 无目标进程时幂等成功；
  - 同时兼容 `--project-dir <path>` 与 `--project-dir=<path>`。
- 执行 `:standalone_bootstrap:test` 定向测试、CLI Python 定向测试、CLI distribution 架构测试和 `git diff --check`。

## 范围外

- 不提供 `uninit`，不删除 run configuration。
- 不增加 MCP shutdown action。
- 不停止 IDEA 进程或 IDEA 内的 Jugg Runtime。
- 不增加 daemon 全局批量停止命令。
