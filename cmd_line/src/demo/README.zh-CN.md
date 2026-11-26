# Jugg CI 构建演示

这个 demo 演示 CI 如何登记 Gradle 基线，并为后续提交选择增量构建、导出已有 APK 或重新执行 Gradle。

需要 Python 3.8+、Git、工程兼容的 JDK、Android SDK 和已构建的 Jugg 命令行发行包。构建脚本会检查 `JAVA_HOME` 和 `ANDROID_HOME`，请在运行构建流程的终端中导出两者。macOS 示例：

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

JDK 版本应与 demo 工程的 Gradle/AGP 兼容。Python 脚本只使用标准库。以下命令从本目录执行。

## 目录与职责

```text
jugg_ci/
  README.md               单次构建与 CI 接入说明
  build.py                对当前提交执行一次 CI 构建，供流水线调用
  _common.py              命令执行和阶段日志
  base_build_server.py    本地基线索引和 ZIP 文件服务
demo_flow/
  run.py                  准备工程，连续运行六个场景
  baseline_service.py     基线记录的 HTTP 调用和 ZIP 传输
  prepare_project.py      准备独立演示工程并初始化 Git
  install_and_launch.py   安装指定轮次的 APK 并启动应用
  flow_common.py          连续演示的参数、进度和脚本调用
custom_compilers/         增量编译使用的两个 JAR
```

CI 的入口是 `jugg_ci/build.py`，构建任务、APK 路径、默认服务地址、文件阈值和注解规则都在文件头配置。`demo_flow/run.py` 模拟开发提交，并逐轮调用同一个构建入口。本地基线服务可由连续演示自动启动，也可单独运行 `jugg_ci/base_build_server.py`。`jugg_ci/build.py` 使用同目录的 `_common.py` 执行命令，并通过 `demo_flow/baseline_service.py` 访问基线服务。交付时保留发行包目录结构。

## 一次运行六个场景

运行下面一条命令即可：

```bash
python3 demo_flow/run.py
```

默认工作目录是 `demo/build/ci-demo`，可用 `--work-dir` 指定。流程会在本机动态端口启动基线服务，使用 `<work-dir>/server/` 保存本次基线和服务日志。成功、失败或按 Ctrl+C 退出时都会关闭服务。再次运行会清理本次演示的旧索引和 ZIP，并启动新实例。传入 `--server` 时使用指定服务，由该服务的维护者管理启停。

流程只在启动时删除并重建一次 `--work-dir`，该目录仅用于演示，其中的所有文件都会被清理。先准备工程并建立初始基线，随后每个场景在当前 HEAD 上创建新提交，保留前面修改及已有构建输出；中途不 reset、不重新准备工程、不执行 Gradle clean。原来的三个独立场景入口已合并到 `run.py`。

| 顺序 | 开发提交 | 预期结果 |
| --- | --- | --- |
| 初始基线 | 预先配置项目自定义 `config.json` 输入 | Gradle 构建并保存基线 |
| 1 | 修改 Kotlin Toast 和 XML 文案 | 增量 APK |
| 2 | 修改 `app/build.gradle`，添加 BuildConfig 字段 | BuildFile，Gradle 并保存新基线 |
| 3 | 新增并提交 101 个 Java 文件 | 超过 100 个，Gradle 并保存新基线 |
| 4 | 修改根目录 `README.md` | Jugg 报告 Unknown，编排主动过滤 README，传空列表给 Jugg 导出基线 APK |
| 5 | 修改现有 `@Route` 路径 | Jugg 增量处理 ARouter 路由变更 |
| 6 | 修改根目录 `config.json` | Unknown 未被过滤，Gradle 降级，APK 内配置更新 |

演示的 Gradle 配置通过 Copy task 将根目录 `config.json` 写入 APK 的 `assets/config.json`，因此它会改变构建结果。最终流程读取 APK 检查新配置，而不只是判断构建方式。完整记录写入 `<work-dir>/flow-results.json`；可从各步的 `commit`、`runDir` 和 `apkDir` 查看持续向前的提交与诊断文件。增量场景与后续场景相对最近基线计算 diff，后续列表可能同时包含前面尚未登记基线的提交。

仓库内运行需补充 `--cmd-line /absolute/path/to/bin/cmd_line`；发行包默认使用 `../bin/cmd_line`。手动运行构建脚本时默认连接 `http://127.0.0.1:18766`，项目命名空间用 `--project-id`。保留 `--scenario release` 参数，本轮仅验收 debug。添加 `--install` 可安装最终产物并启动应用。

`--quite`（按此拼写）隐藏子命令 stdout/stderr 和命令行回显，中文流程日志始终显示清理、准备、开发提交、实际构建方式、忽略的 README 数量、降级原因和 APK 路径。排查详细错误时可移除 `--quite`。Jugg CI 失败会尝试一次 Gradle 降级并显示原因；Gradle 或归档仍失败则非零退出，不打印演示完成。

## 分步操作与真实 CI 接入

单次构建的参数和配置见 [jugg_ci/README.zh-CN.md](jugg_ci/README.zh-CN.md)。

分步演示时，在另一个终端运行 `python3 jugg_ci/base_build_server.py`。随后可用以下脚本准备工程、构建和安装，在两次构建之间自行修改源码并提交：

```bash
python3 demo_flow/prepare_project.py --work-dir /tmp/jugg-manual
python3 jugg_ci/build.py --work-dir /tmp/jugg-manual
# 在 /tmp/jugg-manual/project 修改源码，并创建 Git commit。
python3 jugg_ci/build.py --work-dir /tmp/jugg-manual
# 从 build-result.json 读取明确的 apkDir，再安装对应产物。
python3 demo_flow/install_and_launch.py --apk-dir "$(python3 -c 'import json; print(json.load(open("/tmp/jugg-manual/build-result.json"))["apkDir"])')"
```

`prepare_project.py` 优先使用发行包的 `demo_project.zip`；仓库内回退复制 `android_demo_project`。若上一次流程在初始构建期间中断，可复用由脚本创建且仍停留在 `initial demo` 提交的 checkout；已进入开发提交或不是 demo checkout 时会拒绝覆盖。真实 CI 可以自行 checkout 到 `<work-dir>/project`，只调用 `build.py`。开始时检查主仓库处于干净状态，并读取目标提交。构建期间保持主仓库及参与构建的外部仓库固定。同一个 checkout 应串行构建。

交付其他团队时，使用 `--server` 传入提供的服务地址，连续演示会将它传给每一轮构建。单次构建的默认地址可在 `jugg_ci/build.py` 中修改 `DEFAULT_SERVER`。基线文件通过 HTTP 传输，后端可以与 CI 运行在不同机器上。

```bash
python3 demo_flow/run.py --work-dir /tmp/jugg-demo --server https://jugg-ci.example.com
python3 jugg_ci/build.py --work-dir /tmp/jugg-manual --server https://jugg-ci.example.com --dry-run
```

`--dry-run` 执行查询、下载、变更分析和构建决策，将计划和命中的基线、仓库提交与有效变更数量写入 `dry-run-result.json`。它跳过 APK 构建、导出和基线登记，已有 `build-result.json` 仍可继续使用。

`build.py` 内部流程：查询同场景基线 → 各仓库通过 first-parent 找最近候选 → 下载 ZIP 并解压到独立目录 → 获取完整变更清单 → 应用业务策略 → 构建、归档。每轮候选按记录 ID 去重，通过准入后选有效变更最少的一份，数量相同优先服务返回较早的一份。最近候选全部被拒绝时走 Gradle。没有兼容基线或策略拒绝增量时，直接调用 `buildGradleBase`，保留已有 Gradle 模块输出和缓存，不执行 `gradle clean`。

`getChangedFiles` 保留完整变更清单：能识别的文件返回原有类型，其他文件返回 `type: "Unknown"`、`module: ""`，例如工程根目录的 README。`Unknown` 是 CI 报告类型，不是编译器输入类型。

每个文件还包含 `gitRoot`，指向实际所属 Git 仓库的绝对路径。顶层 `repositories` 列出所有参与查询的仓库及各自的 `baseCommit`、`targetCommit`，包括没有变更的仓库。例如：

```json
{
  "files": [{"file": "/workspace/sdk/Library.kt", "type": "Kotlin", "module": ":sdk", "gitRoot": "/workspace/sdk"}],
  "repositories": [
    {"gitRoot": "/workspace/app", "baseCommit": "<app-base-hash>", "targetCommit": "<app-target-hash>"},
    {"gitRoot": "/workspace/sdk", "baseCommit": "<sdk-base-hash>", "targetCommit": "<sdk-head-hash>"}
  ]
}
```

以上只展示新增字段，原有顶层字段及 `schemaVersion: 1` 保留。外部仓库范围由项目模块模型和基线 deploy history 决定，基线提交来自 `subModulesFullCompileGitCommitHash`，目标为外部仓库当前 HEAD；主仓库仍使用命令参数。included-build 模型随完整基线一并合并和备份。业务脚本应遍历 `repositories` 执行自己的 diff 策略，不能只从非空文件列表推断仓库。旧单仓库基线继续可用；外部仓库缺基线提交或不可用时查询失败，编排按已有错误处理路径执行 Gradle。查询只比较提交，CI 编排应自行检查所有仓库的工作区状态和 HEAD。新增外部源码仓库后，先生成一次完整基线。

构建决策集中在 `build.py`：

- BuildFile 和过滤后缺失的输入：走 Gradle。删除和重命名按 Jugg 返回的变更及文件过滤策略判断。
- `DIFF_FALLBACK_ANNOTATIONS` 匹配源码补丁中的注解增删行，`FULL_FALLBACK_ANNOTATIONS` 可配置为检查整个变更源码文件。demo 仅在 release 对 `@Keep` 增删启用差异降级。全文规则启用 `@Inject`、`@AndroidEntryPoint`、`@HiltAndroidApp`，用于 Hilt 源码参与构建时重新生成依赖注入代码。Jugg 已支持的注解以注释示例列出。
- 处理后的变更数超过 `MAX_INCREMENTAL_FILES = 100`：走 Gradle；恰好 100 个仍可增量。
- 仅工程根目录 `README.md` 被编排显式忽略，原始清单仍保留该 Unknown。其他 Unknown 不忽略，走 Gradle，原因会标明需由 Gradle 处理的输入路径。
- 有兼容基线且过滤后 `files: []`：传 `changedFiles=` 给 Jugg，直接导出基线 APK，不执行编译、不登记新基线，结果 `mode` 为 `reuseBaseApk`。
- 没有兼容基线、输入缺失或显式 `--force-gradle`：走 Gradle。
- 其他情况：完整传入处理后的已识别变更列表执行增量，禁止截取前 100 个文件；原始清单仍完整保存在 `changed-files.json`。
- 基线查找、变更查询、增量编译或空输入导出遇到 Jugg 错误：记录原因并尝试一次 Gradle 基线构建；Gradle 失败保留最终错误，不伪造成功。

100 是业务示例策略，业务方可直接修改 `build.py` 的常量；调整阈值后，相应修改文件过多演示的文件数和预期原因。该策略只影响 CI 样本，不改变 Jugg 核心编译器限制。

直接调用 `buildIncrementalApk` 时，必须显式提供 `changedFiles` 参数。`changedFiles=` 表示空列表：命令从基线元数据读取 APK 并复制到 `outputApkDir`，跳过编译、自定义编译器和 APK 改写，也不写 `.dirty`；缺少参数仍报错。非空列表必须能被完整识别，直接传 README 等文件仍会报 `not all files are compilable`。demo 负责主动忽略明确无关的文件，并对其余 Unknown 或 Jugg 错误执行 Gradle 降级。

```bash
../bin/cmd_line cmd=buildIncrementalApk \
  sourceProjectDir=/tmp/jugg-demo/project \
  baseBuildJuggRootDir=/absolute/path/to/clean-base \
  outputApkDir=/tmp/jugg-export changedFiles=
```

构建只比较基线提交与当前 HEAD，开发修改需先提交。

如果流水线有独立的 APK 重签步骤，可在实际增量构建时传入 `skipSign=true`。Jugg 仍改写和对齐 APK，但不需要 keystore，不执行签名或验签；安装或分发前必须由下游重签并验签。省略参数或传 `skipSign=false` 时，继续使用原有签名流程。空变更导出基线和 Gradle 基线构建不受影响。

## 产物与基线

构建脚本按阶段输出日志，例如 `[03]` 查询基线、`[05]` 分析变更、`[07]` 构建、`[08]` 上传 ZIP 和 `[09]` 登记。Jugg 的输出实时显示并完整写入日志。每轮诊断保存在 `<work-dir>/runs/<id>/`，包括实际调用的 Jugg 命令日志、候选分析、各候选的 `changed-files.json`、Git 补丁与独立的 `jugg/` 副本。所有构建方式将交付 APK 输出到该轮的 `apk/`。成功结果 `build-result.json` 记录提交、模式、原因、`apkDir`、`apks` 和 `runDir`，构建失败时命令非零退出。

场景 key 包含实际 Gradle 命令和 `local.properties` 的内容摘要，用于查询兼容基线。`compileCommand` 保留在登记记录中。release 使用 `bundleReleaseToApk`，Gradle 轮保留 mapping 和可用的 usage。接入方应将影响产物的任务参数和环境输入纳入场景 key。多仓记录以相对路径保存仓库位置，下载后校验 ZIP 内 history；不同机器复用时应保持工具链、依赖和仓库相对布局兼容。

### 本地服务和交付协议

- `POST /artifacts` 上传只包含 `jugg/` 的 ZIP，返回 `artifactId`。随后用 `PUT /base-builds?projectId=...` 独立登记 JSON 记录。记录包含 `id`、`artifactId`、主仓 `baseCommit`、`repositories`、`scenarioKey` 和 `compileCommand`。
- `GET /base-builds?projectId=...` 按服务端登记时间从新到旧返回记录。也可使用 `GET /base-builds/available` 增加 `scenarioKey` 和 `compileCommand` 过滤。客户端按记录中的 `artifactId` 从 `GET /artifacts/{artifactId}` 下载 ZIP。
- `DELETE /base-builds?baseCommit=<完整提交>` 删除该提交的基线记录及没有其他记录引用的本地 ZIP，可用 `projectId` 限定项目。
- 默认索引为 `jugg_ci/base-builds.json`，ZIP 放在 `jugg_ci/artifacts/`。可以通过 `--data-file /absolute/path/base-builds.json` 指定存储目录，也可用它继续读取原有索引。每项目保留最近登记的 100 条记录，记录淘汰与 ZIP 生命周期独立。旧的本地目录归档记录需要重新构建并登记为 ZIP 基线。
- `prepare_project.py` 为每次演示写入独立的 checkout 标识，重复演示时只使用本次生成的基线。自行 checkout 的 CI 按项目、场景和提交选择基线。
