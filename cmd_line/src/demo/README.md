# Python CI 打包演示

需要 Python 3.8+、Git、工程兼容的 JDK、Android SDK 和已构建的 Jugg 命令行发行包。构建脚本会检查 `JAVA_HOME` 和 `ANDROID_HOME`，请在启动基线服务和运行流程的终端中导出两者。macOS 示例：

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

JDK 版本应与 demo 工程的 Gradle/AGP 兼容。Python 脚本只使用标准库。以下命令从本目录执行。

## 目录与职责

```text
scripts/
  prepare_project.py       准备独立演示工程并初始化 Git
  jugg_build.py            对当前提交执行一次 CI 构建，自动选择 Gradle 或增量
  install_and_launch.py    安装指定目录的 APK 并启动
  base_build_server.py     localhost 基线归档与查询服务
demo_flow/
  run.py                   一次准备工程，连续运行五个场景
  _common.py               演示参数、中文日志与脚本调用辅助
custom_compilers/         增量编译使用的两个 JAR
```

`demo_flow` 负责清理演示工作目录、模拟开发操作并组合调用复用脚本；源码修改逻辑只放在演示层。`jugg_build.py` 不修改源码、不准备工程、不安装。连续流程在独立进程中反复调用同一个构建入口，参数一致，由入口自行判断构建方式。

## 一次运行五个场景

先在一个终端启动服务：

```bash
python3 scripts/base_build_server.py --host 127.0.0.1 --port 18766
```

另一个终端运行完整流程：

```bash
python3 demo_flow/run.py --work-dir /tmp/jugg-demo --quite
```

流程只在启动时删除并重建一次 `--work-dir`，该目录仅用于演示，其中的所有文件都会被清理。先准备工程并建立初始基线，随后每个场景在当前 HEAD 上创建新提交，保留前面修改及已有构建输出；中途不 reset、不重新准备工程、不执行 Gradle clean。原来的三个独立场景入口已合并到 `run.py`。

| 顺序 | 开发提交 | 预期结果 |
| --- | --- | --- |
| 初始基线 | 预先配置项目自定义 `config.json` 输入 | Gradle 构建并保存基线 |
| 1 | 修改 Kotlin Toast 和 XML 文案 | 增量 APK |
| 2 | 修改 `app/build.gradle`，添加 BuildConfig 字段 | BuildFile，Gradle 并保存新基线 |
| 3 | 新增并提交 101 个 Java 文件 | 超过 100 个，Gradle 并保存新基线 |
| 4 | 修改根目录 `README.md` | Jugg 报告 Unknown，编排主动过滤 README，传空列表给 Jugg 导出基线 APK |
| 5 | 修改根目录 `config.json` | Unknown 未被过滤，Gradle 降级，APK 内配置更新 |

演示的 Gradle 配置通过 Copy task 将根目录 `config.json` 写入 APK 的 `assets/config.json`，因此它会改变构建结果。最终流程读取 APK 检查新配置，而不只是判断构建方式。完整记录写入 `<work-dir>/flow-results.json`；可从各步的 `commit`、`runDir` 和 `apkDir` 查看持续向前的提交与诊断文件。增量场景与后续场景相对最近基线计算 diff，后续列表可能同时包含前面尚未登记基线的提交。

仓库内运行需补充 `--cmd-line /absolute/path/to/bin/cmd_line`；发行包默认使用 `../bin/cmd_line`。服务地址可用 `--server` 修改，项目命名空间用 `--project-id`。保留 `--scenario release` 参数，本轮仅验收 debug。添加 `--install` 可安装最终产物并启动应用。

`--quite`（按此拼写）隐藏子命令 stdout/stderr 和命令行回显，中文流程日志始终显示清理、准备、开发提交、实际构建方式、忽略的 README 数量、降级原因和 APK 路径。排查详细错误时可移除 `--quite`。Jugg CI 失败会尝试一次 Gradle 降级并显示原因；Gradle 或归档仍失败则非零退出，不打印演示完成。

## 分步操作与真实 CI 接入

可直接使用复用脚本，在两次构建之间自行修改源码并提交：

```bash
python3 scripts/prepare_project.py --work-dir /tmp/jugg-manual
python3 scripts/jugg_build.py --work-dir /tmp/jugg-manual
# 在 /tmp/jugg-manual/project 修改源码，并创建 Git commit。
python3 scripts/jugg_build.py --work-dir /tmp/jugg-manual
# 从 build-result.json 读取明确的 apkDir，再安装对应产物。
python3 scripts/install_and_launch.py --apk-dir "$(python3 -c 'import json; print(json.load(open("/tmp/jugg-manual/build-result.json"))["apkDir"])')"
```

`prepare_project.py` 优先使用发行包的 `demo_project.zip`；仓库内回退复制 `android_demo_project`。若上一次流程在初始构建期间中断，可复用由脚本创建且仍停留在 `initial demo` 提交的 checkout；已进入开发提交或不是 demo checkout 时会拒绝覆盖。真实 CI 可以自行 checkout 到 `<work-dir>/project`，只调用 `jugg_build.py`。构建针对当前 HEAD，开发修改必须先提交。

`jugg_build.py` 内部流程：查询同场景基线 → first-parent 选择最近基线 → 复制干净基线 → 获取完整变更清单 → 仅过滤已确认无关的 README → 决策 → 构建、归档。没有兼容基线或策略拒绝增量时，直接调用 `buildGradleBase`，保留已有 Gradle 模块输出和缓存，不执行 `gradle clean`。

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

构建决策集中在 `jugg_build.py`：

- BuildFile 变更：走 Gradle。
- 处理后的变更数超过 `MAX_INCREMENTAL_FILES = 100`：走 Gradle；恰好 100 个仍可增量。
- 仅工程根目录 `README.md` 被编排显式忽略，原始清单仍保留该 Unknown。其他 Unknown 不忽略，走 Gradle，原因为 `unrecognized changed files`。
- 有兼容基线且过滤后 `files: []`：传 `changedFiles=` 给 Jugg，直接导出基线 APK，不执行编译、不登记新基线，结果 `mode` 为 `reuseBaseApk`。
- 没有兼容基线、输入缺失或显式 `--force-gradle`：走 Gradle。
- 其他情况：完整传入处理后的已识别变更列表执行增量，禁止截取前 100 个文件；原始清单仍完整保存在 `changed-files.json`。
- 基线查找、变更查询、增量编译或空输入导出遇到 Jugg 错误：记录原因并尝试一次 Gradle 基线构建；Gradle 失败保留最终错误，不伪造成功。

100 是业务示例策略，业务方可直接修改 `jugg_build.py` 的常量；调整阈值后，相应修改文件过多演示的文件数和预期原因。该策略只影响 CI 样本，不改变 Jugg 核心编译器限制。

直接调用 `buildIncrementalApk` 时，必须显式提供 `changedFiles` 参数。`changedFiles=` 表示空列表：命令从基线元数据读取 APK 并复制到 `outputApkDir`，跳过编译、自定义编译器和 APK 改写，也不写 `.dirty`；缺少参数仍报错。非空列表必须能被完整识别，直接传 README 等文件仍会报 `not all files are compilable`。demo 负责主动忽略明确无关的文件，并对其余 Unknown 或 Jugg 错误执行 Gradle 降级。

```bash
../bin/cmd_line cmd=buildIncrementalApk \
  sourceProjectDir=/tmp/jugg-demo/project \
  baseBuildJuggRootDir=/absolute/path/to/clean-base \
  outputApkDir=/tmp/jugg-export changedFiles=
```

构建只比较基线提交与当前 HEAD，开发修改需先提交。

## 产物与基线

每轮诊断文件位于 `<work-dir>/runs/<id>/`，按实际路径生成 `candidates.json`、`base-result.json`、`changed-files.json` 和增量工作副本 `jugg/`。增量 APK 输出到该轮的 `apk/`；Gradle 基线轮直接使用服务归档中已有的 `classpath/apk/`，不额外复制 APK，也不再创建 `baseline/` 子目录。

成功后写入 `<work-dir>/build-result.json`，包含 `commit`、`mode`、`reason`、`apkDir` 和诊断目录 `runDir`；`mode` 为 `buildGradleBase`、`buildIncrementalApk` 或 `reuseBaseApk`，安装时始终读取本轮的 `apkDir`。`reason` 为 Gradle 决策原因，增量或复用基线 APK 时为 null。命令失败保留日志和现场，调用方必须检查退出码，不能将前一轮结果当作本轮成功。

release 使用 `bundleReleaseToApk`，Gradle 基线轮归档必需的 mapping 和存在时的 usage；增量轮复用基线中的混淆信息。debug 使用 `assembleDebug`；两者使用不同场景 key。

基线服务将请求中的 `juggRootPath` 完整复制到 `scripts/base_build/{projectId}/{id}/`，目录内直接是 Jugg 基线内容，例如 `classpath/apk/`、`database/`，没有额外的 `baseline/` 层。元数据只保存新的 `juggRootPath`，不需要 `apkPath`。这是单机文件系统复制，调用方和服务需要能访问同一源目录。

默认索引文件是脚本同级的 `scripts/base-builds.json`，不受启动目录影响；可用 `--data-file /absolute/path/base-builds.json` 覆盖，归档目录相应位于该文件同级的 `base_build/`。此前 `.demo-ci/base-builds.json` 中的数据如需继续使用，可通过该参数显式指定。运行时索引和归档不提交、不打入发行包。

每个 project 独立保留最近 100 条记录和对应归档，按服务接收保存的顺序排列；覆盖相同 ID 会完整替换该目录并移到最新位置。超限时删除最旧记录及服务自己保存的归档；历史记录指向的外部目录不会被删除。目录复制或索引写入失败返回错误，覆盖失败保留原记录和归档；过期目录清理失败会单独打印原因。

一个服务可以供多个 project 共用。连续流程和单次构建脚本均支持 `--project-id`，默认值为 `default`；服务不在启动时绑定项目，而是按每次请求的 `projectId` 隔离查询、更新和保留数量。例如：

```bash
python3 demo_flow/run.py --work-dir /tmp/project-a-demo --project-id project-a --quite
python3 demo_flow/run.py --work-dir /tmp/project-b-demo --project-id project-b --quite
python3 scripts/jugg_build.py --work-dir /tmp/jugg-manual --project-id project-a
```

直接调用 HTTP API 时，GET/PUT `/base-builds?projectId=project-a`（兼容原 POST 写入），可用基线查询使用 `/base-builds/available?projectId=project-a&scenarioKey=demo-debug-v1&compileCommand=assembleDebug`。写入 JSON 必须包含 `id` 和存在的 `juggRootPath`；服务返回归档后的完整记录。记录包含 `projectId`，不同项目允许使用相同记录 ID；请求和记录中的 `projectId` 必须一致。两个标识均为单层目录名称，不接受 `.`、`..` 或路径分隔符。未提供项目的请求和旧格式记录均归入 `default`。

服务终端打印 GET 的请求路径、查询参数和完整返回列表；PUT/POST 打印请求路径、查询参数和完整 JSON 记录。这些日志由服务进程输出，场景的 `--quite` 不影响服务终端。

在 project 隔离之外，构建入口继续按 `metadata.projectDir` 匹配同一 checkout，避免跨仓库解析提交。`prepare_project.py` 在 `.git/jugg-demo-id` 写入本轮演示标识，并随基线保存为 `metadata.demoCheckoutId`；清理工作目录后重新准备工程会得到新标识，因此可以共用同一服务重复演示，旧归档仍按保留策略保存。真实 CI 自行 checkout 时无需此标识。只登记干净 Gradle 基线；每次增量复制独立工作副本，实际编译后目录带 `.dirty`，不能作为新基线登记；空列表导出不消费基线。

业务方接入时需替换 `request` 的存储调用、场景 key 和增量资格策略；checkout、环境兼容信息、artifact 上传下载和最终测试由业务方负责。示例 key 仅区分变体，localhost 服务用于单机演示。
