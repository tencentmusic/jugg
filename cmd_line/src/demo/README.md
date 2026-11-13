# Python CI 打包演示

需要 Python 3.8+、Git、项目兼容的 JDK、`ANDROID_HOME` 和已构建的 Jugg 命令行发行包。脚本使用标准库，无 Python 第三方依赖。

先在一个终端启动基线服务（仅用于单机演示）：

```bash
python3 base_build_server.py --host 127.0.0.1 --port 18765 --data-file .demo-ci/base-builds.json
```

另一个终端执行完整 debug 流程：

```bash
python3 jugg_ci_demo.py --work-dir /tmp/jugg-demo-debug
```

脚本从发行包的 `demo_project.zip` 准备工程；仓库内运行时使用 `android_demo_project`。每次演示指定新的工作目录，避免覆盖已有源码和构建现场。仓库内运行需通过 `--cmd-line /absolute/path/to/bin/cmd_line` 指定发行包入口。

一次执行完成：

1. 复制工程并初始化独立 Git 仓库，提交初始源码。
2. 查询 localhost 基线记录，首次执行 `buildGradleBase`，保存 APK 和干净的 `build/jugg` 快照。
3. 修改 Kotlin Toast 和 XML 文案并创建目标提交。
4. 调用 `findNearestBaseCommit`，按 first-parent 选择相同场景、相同构建任务的基线。
5. 复制基线到本轮工作目录，调用 `getChangedFiles`，保留全部输出；空结果、文件缺失或 BuildFile 拒绝增量。
6. 调用 `buildIncrementalApk`，加载 `custom_compilers` 中的两个现有 JAR，输出 APK。

release 使用同一入口，保留 `bundleReleaseToApk`、mapping/usage 和自定义 compiler JAR：

```bash
python3 jugg_ci_demo.py --scenario release --work-dir /tmp/jugg-demo-release
```

`mapping.txt` 必须存在；有 `usage.txt` 时一并归档。debug/release 使用不同场景 key，互不复用基线。服务还按 `metadata.projectDir` 隔离演示仓库，避免将其他独立 Git 仓库的 commit 交给当前仓库解析。

主动拒绝第二轮增量、重新执行 Gradle 并保存新基线：

```bash
python3 jugg_ci_demo.py --work-dir /tmp/jugg-demo-fallback --force-gradle
```

添加 `--install` 可在第二轮完成后执行 `adb install-multiple -r` 并启动 MainActivity。设备测试仍需使用者执行。

产物位于工作目录的 `runs/<id>/`：`apk/`、`candidates.json`、`base-result.json`、`changed-files.json`、增量工作副本 `jugg/` 或干净快照 `baseline/`。命令失败保留 stdout/stderr 和现场，不解析日志获取结构化结果。

服务只保存元数据，不传输 artifact。记录 ID 是本轮输出目录的 SHA-256；重复 POST 同一 ID 覆盖记录。示例只发布干净 Gradle 基线，增量消费过的目录包含 `.dirty`，不再登记为可复用基线。每轮始终从干净快照复制独立工作副本。

接入真实 CI 时，替换 `request` 的存储调用、`should_use_incremental` 的业务策略，以及场景 key 生成；checkout、环境信息、artifact 上传下载和最终测试由业务方实现。示例 key 只区分演示变体，不代表完整环境兼容性。
