# Jugg CI 增量构建接入样本方案

## 1. 目标与边界

当前 Jugg CI 接入要求业务方自行实现三类逻辑：计算 commit 之间的变更文件、选择最近可复用的基线、判断是否允许增量构建。接入方需要理解 Jugg 内部的 Git 规则和 `ChangedFile` 识别逻辑，导致首次接入成本较高，也容易出现业务方实现与 IDE 行为不一致。

本方案提供最小可复用能力、一个 localhost 基线服务和一个可直接修改的 Python 编排样本：

1. Jugg CLI 提供按照 Git commit 计算最近基线和完整 `ChangedFile` 列表的命令。
2. `ChangedFile` 以稳定 JSON 文件输出，供业务方继续过滤和决策。
3. Python 样本负责基线记录查询、外部场景匹配、变更文件过滤、增量/Gradle 分流和基线保存。
4. Demo 附带一个极简 localhost 服务，演示基线记录的保存、读取和可用基线查询；该服务只用于快速接入示例，不进入 Jugg 核心运行时。
5. Jugg 不读取业务方的基线存储，不生成或解释外部环境 key，不替业务方决定是否增量。

本方案不改变 IDE 编译、部署和现有 `buildGradleBase` / `buildIncrementalApk` 的行为。

## 2. 目标流程

业务方已有 CI 框架负责 checkout、提交构建任务和保存 artifact。Demo 使用 localhost 服务模拟基线记录存储；Python 样本接收当前工程目录、目标 commit 和业务方可见的构建场景信息后执行：

```text
从基线服务读取记录
  -> 按场景 key 过滤可复用记录
  -> findNearestBaseCommit：沿 target commit 的 first-parent 查找最近候选基线
  -> 未找到基线：buildGradleBase，保存新的基线记录
  -> 找到基线：getChangedFiles，得到完整 ChangedFile JSON
  -> 业务方过滤 ChangedFile 并判断增量资格
  -> 不允许增量：buildGradleBase，保存新的基线记录
  -> 允许增量：buildIncrementalApk
  -> 将输出 APK、Jugg 基线目录位置和场景元数据保存到基线服务
```

增量路径使用三个 Jugg 命令：

```text
findNearestBaseCommit
getChangedFiles
buildIncrementalApk
```

基线缺失或不允许增量时，分支改为执行 `buildGradleBase`。基线目录每次增量消费前都必须复制为独立工作副本，不能并发复用同一个目录。

## 3. 新增命令一：findNearestBaseCommit

### 3.1 职责

该命令只处理 Git 提交关系，不读取 artifact、不理解环境 key、不判断构建场景。调用方先根据外部条件过滤出候选基线 commit，Jugg 再从候选列表中找出目标 commit 的最近 first-parent 祖先。

### 3.2 参数

建议沿用当前 `key=value` 命令行风格：

```bash
cmd_line \
  cmd=findNearestBaseCommit \
  projectDir=/path/to/git/project \
  targetCommit=<target-commit> \
  candidateCommitsFile=/path/to/candidates.json \
  output=/path/to/base-result.json
```

参数约定：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `cmd` | 是 | 固定为 `findNearestBaseCommit` |
| `projectDir` | 是 | Git 工作区根目录；必须存在有效 Git 仓库 |
| `targetCommit` | 是 | 目标提交，可由 JGit 解析的 commit 名称；输出中保留调用方传入值和解析后的完整 SHA |
| `candidateCommitsFile` | 是 | UTF-8 JSON 文件，包含候选基线 commit 列表 |
| `output` | 是 | 输出 JSON 文件路径；父目录不存在时创建 |
| `logLevel` | 否 | 沿用现有日志级别，默认 `DEBUG` |

候选文件最小格式：

```json
{
  "commits": [
    "8d2f...",
    "1a93..."
  ]
}
```

也可以允许顶层直接使用字符串数组，若实现需要选择一种格式，应优先使用对象格式，便于后续增加非 Jugg 字段而不改变文件类型。

### 3.3 查找算法

1. 解析并校验 `targetCommit`。
2. 读取候选列表，去重但保留原始输入顺序用于输出诊断。
3. 从 `targetCommit` 开始遍历：当前 commit 先检查是否命中候选；未命中时只读取 `RevCommit.getParent(0)`，直到没有 first parent。
4. 返回第一个命中的候选 commit。候选列表不要求按时间排序。
5. 候选 commit 如果无法解析，应作为参数错误处理并失败，不能静默忽略，以免业务方误以为候选已参与匹配。
6. 目标 commit 不在候选列表且 first-parent 链无命中时，输出 `matchedCommit: null`，命令返回失败状态；失败原因必须可从日志和 JSON 判断为“未找到基线”，不能伪造成功。

first-parent 是默认且唯一的内置查找规则。任意祖先搜索、多分支优先级和 merge-base 策略留给业务方在候选列表生成阶段处理。

### 3.4 输出格式

```json
{
  "schemaVersion": 1,
  "targetCommit": "<input-name>",
  "resolvedTargetCommit": "<full-sha>",
  "candidateCommits": ["<full-sha>"],
  "matchedCommit": "<full-sha-or-null>",
  "matchStrategy": "first-parent",
  "matchedDistance": 12
}
```

`matchedDistance` 表示沿 first-parent 从目标 commit 到命中基线经过的边数；未命中时为 `null`。输出 JSON 即使未命中也应写出，便于编排层记录诊断；命令进程仍返回失败码。

## 4. 新增命令二：getChangedFiles

### 4.1 职责

该命令根据基线 commit 和目标 commit 计算完整 Git 变更，再使用现有 Jugg 工程上下文和 `FileChangesHandler` 将文件转换为 `List<ChangedFile>`。命令不做业务过滤，不判断是否允许增量。

### 4.2 参数

```bash
cmd_line \
  cmd=getChangedFiles \
  sourceProjectDir=/path/to/project \
  baseBuildJuggRootDir=/path/to/base-build-jugg \
  baseCommit=<base-commit> \
  targetCommit=<target-commit> \
  output=/path/to/changed-files.json
```

参数约定：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `cmd` | 是 | 固定为 `getChangedFiles` |
| `sourceProjectDir` | 是 | 当前目标 commit 的工程目录，也是输出绝对路径的归属根目录 |
| `baseBuildJuggRootDir` | 是 | 未消费的 Jugg 基线目录，用于恢复工程上下文和 `FileChangesHandler` scope |
| `baseCommit` | 是 | 基线提交 |
| `targetCommit` | 是 | 目标提交 |
| `output` | 是 | 输出 JSON 文件路径 |
| `logLevel` | 否 | 沿用现有日志级别，默认 `DEBUG` |

命令必须校验两个 commit 可解析、基线目录存在、工程目录存在。`targetCommit` 不强制要求当前 HEAD 等于目标 commit，但 Python 样本应在调用前保证工作区已经 checkout 到目标提交；命令的 Git diff 以 commit 内容为准，`FileChangesHandler` 的工程路径以当前工作区为准。

### 4.3 变更计算与 ChangedFile 复用

实现顺序：

1. 使用 `GitManager.getChangedFiles(baseCommit, targetCommit)` 获取 Git 变更路径。
2. 使用当前 `sourceProjectDir` 将结果规范化为绝对路径。
3. 使用恢复后的编译上下文初始化 `FileChangesHandler`。
4. 调用 `FileChangesHandler.filter(changedFiles)`，完全复用现有 `ChangedFile` 类型识别、模块归属和 build file 规则。
5. 将返回的 `List<ChangedFile>` 映射为 CLI 专用稳定 DTO。

不要在新命令中新增源码、资源、assets、native library 等分类判断；业务方需要的过滤应基于输出中的 `type` 完成。

删除、重命名和未识别文件继续遵循现有 `GitManager.getChangedFiles` 与 `FileChangesHandler.filter` 的行为。不要在本次功能中扩展新的 Git 状态模型；如果现有逻辑无法将某个路径转换为 `ChangedFile`，命令应按现有 filter 结果处理，并在日志中保留数量和路径诊断。

### 4.4 稳定 JSON schema

建议输出：

```json
{
  "schemaVersion": 1,
  "baseCommit": "<input-name>",
  "resolvedBaseCommit": "<full-sha>",
  "targetCommit": "<input-name>",
  "resolvedTargetCommit": "<full-sha>",
  "sourceProjectDir": "/absolute/project/path",
  "files": [
    {
      "file": "/absolute/project/path/app/src/main/java/Foo.kt",
      "type": "Kotlin",
      "module": "app"
    }
  ]
}
```

约束：

- `schemaVersion` 固定从 `1` 开始；字段新增或语义变化时递增。
- `file` 必须是规范化绝对路径。
- `type` 使用现有 `CompileFile.Type.name`，不重新命名。
- `module` 输出 `ChangedFile.module.name`，用于业务方诊断和过滤；如果模块信息缺失，应按现有对象状态输出安全值或使命令失败，不能编造模块。
- `extraInfo`、`compiledTimes`、`isManuallySelected` 等运行态字段不输出，避免泄露内部状态并保持 schema 稳定。
- 文件列表顺序应保持 `FileChangesHandler.filter` 返回顺序，避免业务方依赖排序时产生不必要变化。
- 输出文件写入成功后再返回命令成功；终端只打印日志和摘要，不打印完整 JSON。

## 5. Git API 变更

### 5.1 `IGitManager`

新增最小语义方法，建议命名为：

```kotlin
fun findNearestFirstParentCommit(targetCommit: String, candidateCommits: Set<String>): FirstParentCommitMatch?
```

如果项目已有适合复用的 commit 结果类型，优先复用；否则新增包内数据类，字段只包含：命中的完整 SHA 和距离。接口不接收环境 key、不访问外部存储。

### 5.2 `GitManager`

使用现有 JGit repository / `RevWalk`：

- 解析目标 commit；
- 循环读取当前 commit 的第一个 parent；
- 用解析后的 `ObjectId` 集合比较候选，避免短 SHA 和完整 SHA 字符串比较不一致；
- 对非法 commit 抛出与现有 Git API 一致的异常，由 CLI 命令转换为可读失败日志。

应增加单元测试覆盖线性历史、merge commit first-parent、目标即候选、无命中和非法候选。

## 6. CLI 代码组织

预计修改或新增：

| 路径 | 变更职责 |
| --- | --- |
| `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/CmdLine.kt` | 注册两个新命令并保持现有命令行为 |
| `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/.../ParamsParser.kt` | 新命令参数校验；建议按命令领域新增 parser，避免继续扩大现有增量 parser |
| `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/.../FindNearestBaseCommitCommand.kt` | 命令编排、日志、输出和返回码 |
| `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/.../GetChangedFilesCommand.kt` | 初始化工程上下文、计算变更、输出 DTO |
| `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/.../CiJsonWriter.kt` | 最小 JSON 文件写出能力；复用仓库已有 JSON 库，不新增依赖 |
| `main/src/main/java/com/sickworm/intellij/jugg/git/IGitManager.kt` | 声明 first-parent 查找能力 |
| `main/src/main/java/com/sickworm/intellij/jugg/git/GitManager.kt` | 实现 first-parent 查找 |
| `cmd_line/src/test/...` | 新增命令和 Git 行为的定向测试 |

实际包路径以现有项目组织为准；不要为了两个命令新增通用命令框架或服务接口。

## 7. Python 编排样本

建议新增：

```text
tools/jugg_ci_sample.py
tools/jugg_ci_sample.md
cmd_line/src/demo_ci/jugg_ci_demo.py
cmd_line/src/demo_ci/README.md
```

脚本保持单文件、函数化和显式调用，不引入数据库 SDK、CI SDK 或通用插件框架。`tools/jugg_ci_sample.py` 是脱离 demo 的参考编排样本，`cmd_line/src/demo_ci/jugg_ci_demo.py` 是可直接运行的完整 demo。建议保留以下函数作为业务方修改入口：

```python
def load_base_builds(context): ...
def build_scenario_key(context): ...
def filter_compatible_base_builds(records, context): ...
def run_jugg_command(args, cwd): ...
def select_nearest_base(context, records): ...
def filter_changed_files(changed_file_document, context): ...
def should_use_incremental(changed_files, context): ...
def save_base_build(record, artifacts): ...
def build(context): ...
```

默认样例逻辑：

- `load_base_builds` 从 localhost 基线服务读取记录；脱离 demo 使用时，业务方替换该函数；
- `build_scenario_key` 使用示例字符串，不能假设真实 AGP/JDK/插件信息；
- `filter_compatible_base_builds` 按示例 key 精确匹配；
- `select_nearest_base` 写候选 commit JSON，调用 `findNearestBaseCommit`；
- `filter_changed_files` 默认保留全部 Jugg 输出，示例中展示如何按 `type` 过滤；
- `should_use_incremental` 默认只允许业务方显式保留的 `ChangedFile`，不把策略写死在 Jugg；
- 没有基线或策略拒绝增量时调用 `buildGradleBase`；
- 基线构建成功后由 `save_base_build` 保存业务方需要的 artifact 和记录；demo 默认写入 localhost 基线服务；
- 增量构建前复制 `build/jugg` 到独立目录，避免 `.dirty` 导致下一次任务失败。

样本必须明确指出：checkout、artifact 上传下载、环境 key 生成和最终测试由业务方实现；localhost 服务只替代 demo 中的基线记录存储。

## 8. 极简 localhost 基线服务

### 8.1 职责

服务用于让 demo 在单机上演示完整的“构建基线 -> 保存记录 -> 后续构建查询并复用基线”流程。它只保存基线元数据，不执行 Gradle、不复制工程、不计算 Git diff，也不判断是否允许增量。

服务实现应优先使用 Python 标准库 `http.server`、`json` 和文件锁/原子替换能力，避免新增第三方依赖。服务作为 demo 进程启动，支持端口参数和数据文件路径参数。

### 8.2 基线记录

最小记录格式：

```json
{
  "id": "<stable-id>",
  "baseCommit": "<full-sha>",
  "scenarioKey": "release-with-plugin-set-a-v3",
  "compileCommand": "bundleReleaseToApk",
  "apkPath": "/path/to/base.apk",
  "juggRootPath": "/path/to/build-jugg",
  "createdAt": "2026-11-14T10:00:00Z",
  "metadata": {}
}
```

`scenarioKey`、`compileCommand` 和 `metadata` 由编排层生成，服务只保存和按条件匹配，不解释字段语义。`id` 可由 `baseCommit + scenarioKey + compileCommand + artifact 路径` 计算，也可由服务生成；实现时选择一种稳定方式并在 README 中说明。

### 8.3 HTTP API

服务只需要三个业务接口：

```text
POST /base-builds
GET  /base-builds
GET  /base-builds/available?scenarioKey=...&compileCommand=...
```

- `POST /base-builds`：保存或覆盖一条基线记录；请求体为完整记录 JSON。
- `GET /base-builds`：读取全部记录，供调试和清理使用。
- `GET /base-builds/available`：按请求中的场景字段做精确匹配，返回可交给 `findNearestBaseCommit` 的记录列表。服务不负责 first-parent 查找。

返回格式统一为 JSON，错误使用非 2xx 状态码和 `{"error": "..."}`。写文件必须采用临时文件写入后原子替换，避免 demo 进程中断留下半份 JSON。并发控制以单进程 demo 为目标，不增加数据库或分布式锁。

### 8.4 启动与停止

建议提供：

```bash
python3 cmd_line/src/demo_ci/base_build_server.py \
  --host 127.0.0.1 \
  --port 18765 \
  --data-file .demo-ci/base-builds.json
```

`jugg_ci_demo.py` 可以在服务未运行时给出明确提示；是否由 demo 自动拉起服务应保持为一个简单可替换函数，不把后台进程管理做成通用框架。README 同时提供手动启动方式，便于业务方替换为真实存储服务。

## 9. Demo Python 化

### 9.1 目标

现有 `cmd_line/src/demo` 和 `cmd_line/src/demo_release` 由多段 shell 脚本串联。改造后使用 Python 统一模拟完整流程：

```text
准备 demo 工程
  -> 启动或连接 localhost 基线服务
  -> 根据 commit/场景查询候选基线
  -> 调用 findNearestBaseCommit
  -> 调用 getChangedFiles
  -> 业务过滤并选择增量或 Gradle
  -> 调用 buildIncrementalApk 或 buildGradleBase
  -> 保存基线记录和 APK artifact
  -> 可选 adb 安装、启动和验证
```

### 9.2 Demo 文件

建议保留现有 demo 工程和自定义编译器资源，新增或替换为：

```text
cmd_line/src/demo_ci/base_build_server.py
cmd_line/src/demo_ci/jugg_ci_demo.py
cmd_line/src/demo_ci/demo_config.py
cmd_line/src/demo_ci/README.md
```

`demo_config.py` 只保存路径、命令和示例场景值，不承载流程逻辑。Python 脚本应使用 `subprocess.run` 调用 `cmd_line`，完整传递参数并在失败时保留 stdout、stderr 和命令行摘要。

### 9.3 演示场景

Demo 至少演示两次构建：

1. 第一次没有可用基线，调用 `buildGradleBase`，复制并保存 `build/jugg`、APK 和基线记录。
2. 修改 Kotlin/XML 或其他现有 demo 支持的文件，创建目标 commit；第二次从服务读取候选基线，调用 `findNearestBaseCommit` 和 `getChangedFiles`，默认保留全部 Jugg 文件后调用 `buildIncrementalApk`。

还应提供一个显式触发 Gradle 回退的配置或文件变更示例，展示业务方如何在 Python 过滤函数中拒绝增量并重新建立基线。

Release demo 继续覆盖 `bundleReleaseToApk`、mapping/usage 文件和自定义 compiler jars；普通 debug demo 继续覆盖 `assembleDebug`。不要求两个场景共享同一个基线记录，场景 key 应明确区分。

### 9.4 Demo 输出

每一步打印：

- 当前 commit 和场景 key；
- 选中的 base commit 或未命中原因；
- Jugg 命令；
- `ChangedFile` 总数和业务过滤后的数量；
- 最终选择的 `buildGradleBase` / `buildIncrementalApk`；
- APK 输出路径和基线服务记录 ID。

完整 JSON 文件保留在 demo 输出目录，便于业务方查看和修改过滤逻辑；终端不依赖解析日志来获得结构化结果。

## 10. 测试与验证

### 10.1 测试价值

新增命令具有稳定的外部 JSON 和 Git 选择行为，属于值得保护的用户可观察契约。测试不验证私有实现细节，优先验证命令输入、输出和 Git 历史结果。

### 10.2 定向测试

至少覆盖：

1. `findNearestFirstParentCommit`：目标即候选、线性历史最近命中、merge commit 只沿 parent 0、无命中、非法候选。
2. `findNearestBaseCommit`：候选文件读取、输出 schema、未命中输出和失败码、输出目录创建。
3. `getChangedFiles`：基线/目标 commit 变更经过 `FileChangesHandler` 后输出绝对路径、现有 type、module 和 schema version。
4. 参数错误：缺少 commit、工程目录、候选文件或 output；output 无法写入时失败且不输出成功 JSON。
5. 未命中 filter 的原有路径保持现有行为，不把无法识别的文件静默加入结果。
6. localhost 服务的保存、读取、场景过滤、原子写入和错误响应。
7. Python demo 的命令构造、基线未命中回退、增量路径和 Gradle 回退路径；外部 `cmd_line` 可通过 demo fixture 或 dry-run seam 验证，不在生产代码新增测试专用注入点。

测试应使用仓库已有 Git fixture / test utilities；不新增只为 mock 生产代码的 provider、supplier 或 factory。

### 10.3 编译验证

执行命令应使用定向范围，例如：

```bash
./gradlew :cmd_line:test --tests 'com.sickworm.intellij.jugg.cmdline.*'
./gradlew :cmd_line:compileKotlin
```

如果 `cmd_line` 没有独立 Kotlin 编译任务，使用仓库现有可用的 CLI 编译任务。不要运行无 `--tests` 过滤的全量 `:main:test` 或 `:idea:test`。

## 11. 文档同步

实现后同步：

- `docs/ai_knowledge/04_engineering_compat.md`：补充 CLI 两个新命令、first-parent 基线查找和 CI 两阶段/三命令样本边界；
- `docs/ai_knowledge/08_cli_tools_list.md`：新增两个 CLI-only 子命令、参数和 JSON 输出约定；
- `docs/ai_knowledge/98_code_map.md`：补充新增命令入口和 Python 样本路径；
- 必要时更新 `cmd_line/src/demo` 或新增 demo 说明链接，但不把业务方环境 key 或存储实现写入 Jugg 用户 Wiki。
- `cmd_line/src/demo_ci/README.md`：说明 localhost 服务、Python demo 的启动顺序、普通 debug 和 release 场景、artifact 位置及替换存储函数的位置。

## 12. 风险与排除项

- `GitManager.getChangedFiles` 当前返回的是 Git diff 路径，`FileChangesHandler.filter` 决定最终 `ChangedFile` 集合；本次不扩展删除、重命名或未跟踪文件模型。
- `getChangedFiles` 需要基线 Jugg 上下文来初始化模块和 source/resource scope；不能退化为只返回 Git 路径，否则无法满足复用 `List<ChangedFile>` 的要求。
- first-parent 查找不会覆盖所有可能的分支祖先；这是明确的默认策略，复杂分支策略由业务方生成候选列表或替换样本函数。
- 业务方基线记录如果没有保存构建命令、场景 key 和环境信息，Jugg 无法判断 artifact 是否可复用；样本只提供记录字段示例，不替业务方补全。
- 输出 schema 一旦对外使用，字段和 `CompileFile.Type` 名称需要保持兼容；后续字段变化必须递增 `schemaVersion` 并同步文档。
- localhost 服务是 demo 辅助组件，不提供鉴权、跨机器访问、持久化一致性或并发写保证；业务方接入时应替换 `load_base_builds` / `save_base_build`，而不是直接把该服务作为生产基线中心。
- Demo Python 化不能把 shell 里的路径、平台命令和 macOS 专用 `sed -i` 语法原样搬入 Python；路径处理、文件修改和进程调用必须使用跨平台 Python 实现或明确标注平台要求。

## 13. 实施顺序

1. 先实现并测试 `GitManager` first-parent 查询。
2. 实现 `findNearestBaseCommit` 命令及 JSON writer。
3. 实现 `getChangedFiles` 命令，接入现有编译上下文和 `FileChangesHandler`。
4. 增加 CLI 定向测试和样例 JSON fixture。
5. 编写 `tools/jugg_ci_sample.py` 及说明。
6. 实现 localhost 基线服务及 HTTP API 测试。
7. 将 demo shell 流程迁移为 Python，覆盖首次基线、增量构建和 Gradle 回退。
8. 同步 AI 知识库、代码索引和 demo README。
9. 运行定向测试、CLI 编译、Python 静态检查和 demo dry-run/真实 smoke 流程。
10. 对照本方案检查 diff，确保生产 Jugg 只增加通用事实能力，未引入业务方基线存储或外部环境判断。


## 14. Demo 实施补充

Demo 统一放在 `cmd_line/src/demo/`，以 `jugg_ci_demo.py` 替代 debug 的四段 shell；localhost 服务同步迁入该目录。统一入口支持 debug/release、两次真实构建、Git 提交变化、独立基线副本、APK 归档、主动 Gradle 回退和可选 adb 安装。release 的原有独立 shell 示例保留。只登记未消费的 Gradle 基线；增量输出归档但不登记为可复用基线。

已通过 2 项 Python 定向测试；流程 fixture 检查 Gradle/增量/强制回退的命令和 artifact，并通过语法检查、临时工程准备/修改/Git 提交 smoke 和 JDK 17 下的 `:cmd_line:compileKotlin`。默认 JDK 21 与 Gradle 7.3.3 不兼容，切换现有 JDK 17 后通过。未执行真实 Android APK 打包及设备安装。
