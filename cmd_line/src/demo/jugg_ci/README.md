# Jugg CI 单次构建

`build.py` 针对当前 Git 提交查询兼容的 Gradle 基线，自动选择增量构建、导出已有 APK 或完整 Gradle 构建。完整构建成功后会上传新的基线 ZIP 并登记记录。

## 在流水线中使用

准备一个干净的 checkout，放到 `<work-dir>/project`。保留 cmd_line 发行包的原目录结构，构建脚本会使用其中的启动器、基线 HTTP 模块和演示自定义编译器。需要 Python 3.8+、Git、与工程兼容的 JDK 和 Android SDK。在发行包的 `demo/` 目录执行：

```bash
python3 jugg_ci/build.py --work-dir /path/to/ci-work --server https://jugg-ci.example.com
```

常用参数：

| 参数 | 用途 |
| --- | --- |
| `--server` | 基线后端 URL。单次构建的默认值在 `build.py` 的 `DEFAULT_SERVER` 中配置 |
| `--project-id` | 服务端项目标识，默认 `default` |
| `--scenario` | 选择 demo 的 `debug` 或 `release` 构建配置 |
| `--cmd-line` | 改用指定的 cmd_line 启动器 |
| `--dry-run` | 查询和分析基线，将决策写入 `dry-run-result.json` |
| `--force-gradle` | 强制建立新的 Gradle 基线 |

接入自己的工程时，在 `build.py` 中调整构建任务、APK 输出路径、场景 key 和业务降级规则。同一个 checkout 内的构建应串行运行。成功后从 `<work-dir>/build-result.json` 的 `apkDir` 收集 APK，从 `runDir` 查看本轮诊断。

## 本地试跑

分步操作时，先在单独的终端启动服务，再运行构建脚本：

```bash
python3 jugg_ci/base_build_server.py
```

服务默认监听 `127.0.0.1:18766`，索引和 ZIP 分别写入 `jugg_ci/base-builds.json` 和 `jugg_ci/artifacts/`。如需复用旧目录的索引，可通过 `--data-file` 指向原有文件。

想一次跑完全部场景时，执行 `python3 demo_flow/run.py`。它会自动启动和关闭本地服务。完整步骤见 [Demo README](../README.md)。
