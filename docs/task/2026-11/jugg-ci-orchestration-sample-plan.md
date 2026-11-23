# Jugg CI 增量构建接入样本方案

**Purpose** — Demonstrate CI baseline orchestration.
**Decision** — Separate reusable build and artifact service logic from the demo scenarios.
**Impact** — CI owners can inspect baseline eligibility and resulting APKs.

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
  -> 将完整 Jugg 基线目录及场景元数据保存到基线服务，APK 已在目录内
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
cmd_line/src/demo/scripts/jugg_build.py
cmd_line/src/demo/README.md
```

脚本保持函数化和显式调用，不引入数据库 SDK、CI SDK 或通用插件框架。`tools/jugg_ci_sample.py` 是脱离 demo 的参考编排样本，`cmd_line/src/demo/scripts/jugg_build.py` 是可复用的单次构建入口，完整演示由 `demo_flow/` 编排。建议保留以下函数作为业务方修改入口：

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
- `filter_changed_files` 诊断文件保留全部 Jugg 输出，包括未识别的 Unknown；编译时排除 Unknown；
- `should_use_incremental` 默认只允许业务方显式保留的 `ChangedFile`，不把策略写死在 Jugg；
- 没有基线或策略拒绝增量时调用 `buildGradleBase`；
- 有基线且没有已识别变更时，直接返回基线 APK，不调用编译命令、不保存新基线；
- 基线构建成功后由 `save_base_build` 保存业务方需要的 artifact 和记录；demo 默认写入 localhost 基线服务；
- 增量构建前复制 `build/jugg` 到独立目录，避免 `.dirty` 导致下一次任务失败。

样本必须明确指出：checkout、artifact 上传下载、环境 key 生成和最终测试由业务方实现；localhost 服务只替代 demo 中的基线记录存储。

## 8. 极简 localhost 基线服务

### 8.1 职责

服务用于让 demo 在单机上演示完整的“构建基线 -> 保存记录 -> 后续构建查询并复用基线”流程。它将完整 Jugg 目录归档到 `base_build/{projectId}/{id}` 并保存元数据，不执行 Gradle、不复制源码工程、不计算 Git diff，也不判断是否允许增量。APK 已在 Jugg 目录的 `classpath/apk` 内，不增加 `apkPath` 字段或独立 APK 副本。

服务实现应优先使用 Python 标准库 `http.server`、`json` 和文件锁/原子替换能力，避免新增第三方依赖。服务作为 demo 进程启动，支持端口参数和数据文件路径参数。

### 8.2 基线记录

最小记录格式：

```json
{
  "id": "<stable-id>",
  "projectId": "<project-id>",
  "baseCommit": "<full-sha>",
  "scenarioKey": "release-with-plugin-set-a-v3",
  "compileCommand": "bundleReleaseToApk",
  "juggRootPath": "/path/to/base_build/<project-id>/<stable-id>",
  "createdAt": "2026-11-14T10:00:00Z",
  "metadata": {}
}
```

`scenarioKey`、`compileCommand` 和 `metadata` 由编排层生成，服务只保存和按条件匹配，不解释字段语义。`id` 可由 `baseCommit + scenarioKey + compileCommand + artifact 路径` 计算，也可由服务生成；实现时选择一种稳定方式并在 README 中说明。

### 8.3 HTTP API

服务只需要三个业务接口：

```text
PUT /base-builds?projectId=...
GET /base-builds?projectId=...
GET /base-builds/available?projectId=...&scenarioKey=...&compileCommand=...
```

- `PUT /base-builds`：保存或覆盖一条基线记录；请求体为完整记录 JSON，`juggRootPath` 为可访问的源目录，响应改为归档目录。兼容原 POST 写入入口，打印完整请求参数。
- `GET /base-builds`：读取项目内全部记录，供调试和清理使用，打印请求参数和完整返回列表。
- `GET /base-builds/available`：按请求中的场景字段做精确匹配，返回可交给 `findNearestBaseCommit` 的记录列表，打印请求参数和返回列表。服务不负责 first-parent 查找。

返回格式统一为 JSON，错误使用非 2xx 状态码和 `{"error": "..."}`。写文件必须采用临时文件写入后原子替换，避免 demo 进程中断留下半份 JSON。并发控制以单进程 demo 为目标，不增加数据库或分布式锁。

### 8.4 启动与停止

建议提供：

```bash
python3 cmd_line/src/demo/scripts/base_build_server.py \
  --host 127.0.0.1 \
  --port 18766
```

基线服务由使用者显式启动；构建入口保留连接失败原因，README 提供手动启动方式，便于业务方替换为真实存储服务。

## 9. Demo Python 化

### 9.1 目标

`cmd_line/src/demo` 使用 Python 统一模拟完整流程，独立 `cmd_line/src/demo_release` 已移除，release 变体保留 `--scenario release` 入口；本轮只验收 debug：

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
cmd_line/src/demo/scripts/base_build_server.py
cmd_line/src/demo/scripts/jugg_build.py
cmd_line/src/demo/scripts/prepare_project.py
cmd_line/src/demo/scripts/install_and_launch.py
cmd_line/src/demo/demo_flow/normal_incremental.py
cmd_line/src/demo/demo_flow/build_file_fallback.py
cmd_line/src/demo/demo_flow/too_many_files_fallback.py
cmd_line/src/demo/demo_flow/_common.py
cmd_line/src/demo/README.md
```

`scripts/` 提供可复用操作；`demo_flow/` 负责演示修改、提交与编排。Python 脚本应使用 `subprocess.run` 调用 `cmd_line`，完整传递参数并在失败时保留 stdout、stderr 和命令行摘要。

### 9.3 演示场景

Demo 至少演示两次构建：

1. 第一次没有可用基线，调用 `buildGradleBase`，复制并保存 `build/jugg`、APK 和基线记录。
2. 修改 Kotlin/XML 或其他现有 demo 支持的文件，创建目标 commit；第二次从服务读取候选基线，调用 `findNearestBaseCommit` 和 `getChangedFiles`，默认保留全部 Jugg 文件后调用 `buildIncrementalApk`。

分别通过真实修改 `app/build.gradle` 和新增 101 个 Java 文件演示两种 Gradle 降级。`jugg_build.py` 统一判断 BuildFile 和变更数量，默认上限为 100，业务方可修改；场景脚本不传强制降级参数。

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
- `cmd_line/src/demo/README.md`：说明 localhost 服务、Python demo 的启动顺序、普通 debug 和 release 场景、artifact 位置及替换存储函数的位置。

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

### 目标与结构

原 `jugg_ci_demo.py` 把准备、两次构建、修改和安装放在同一进程，无法展示真实 CI 重复触发同一个构建入口的过程。本次移除该入口，把准备、单次构建、安装、localhost 服务放到 `cmd_line/src/demo/scripts/`。`demo_flow/` 内三个独立 Python 流程组合调用这些脚本；修改源码属于演示场景，不作为复用脚本。现有独立 release shell 示例保留。

### 构建决策与状态

- `jugg_build.py` 每次只处理当前 HEAD；首次无兼容基线走 Gradle，后续根据完整 ChangedFile 列表自动判断。
- `incremental_rejection_reason` 集中维护降级策略：BuildFile、超过 100 个文件、空变更、文件缺失和用户主动 `--force-gradle`；业务方可修改 `MAX_INCREMENTAL_FILES`，100 个允许，101 个拒绝，不裁剪输入。
- 三个演示分别修改 Kotlin/XML、Gradle BuildConfig 字段、101 个 Java 文件；两个降级场景检查实际原因，不靠强制标记模拟。三个场景均支持 debug/release。
- 每轮独立产物目录，成功后输出 `build-result.json`（commit、mode、reason、apkDir）；只登记未消费的 Gradle 基线，增量副本仍遵循 `.dirty` 契约。
- 增加英文注释解释两次相同构建调用、业务阈值、场景修改、基线生命周期、结果发布和业务替换位置。

### 验证与文档

失败证据：原策略对 101 个变更文件仍返回允许增量，新增 100/101 边界断言已先失败。回归 owner 为 Python CI 构建入口和演示编排；保留原资格规则与干净基线隔离测试，增加跨进程流程 fixture（真实 Git 和 HTTP 服务，Android CLI 编译替换为确定性 fixture），覆盖三个场景的 debug/release 六种组合、完整变更数量、产物、基线登记和 release mapping/usage。

同步 demo README、`04_engineering_compat.md` 和 `98_code_map.md`。Wiki 的 CLI 页面描述 IDE daemon CLI，已有 supported-packaging 页的两阶段 CI 说明未因本次 demo 组织和业务策略变化而失准，不修改 Wiki。真实 Android 编译和设备安装不包含在 fixture 验证中。

原方案阶段验证结果：`python3 -m unittest discover -s cmd_line/src/test/python -p 'test_demo*.py'` 通过 4 项测试，其中跨进程 Flow 包含三个场景 × debug/release 六种组合。用仓库实际 `android_demo_project` 执行准备与三种修改的 smoke，通过真实 Git diff 确认分别有 2、1、101 个提交变更文件。10 个 Python 文件通过 Python 3.8 语法检查，demo 新增方法均不超过 50 行，`git diff --check` 通过。

### 15. 发行包流程故障修复

用户从 `cmd_line/build/distributions/cmd_line/demo/README.md` 启动实际流程时，先遇到已有 `project/` 导致无法续跑、缺少 `JAVA_HOME` 说明、`app/build/` 与实际集中式 `build/app/` 输出路径不符、AGP 7.3.1 D8 无法处理 runtime 捕获匿名类，以及重复 Release 构建复用 AabResGuard 输出的问题。修复包括：只复用仍处于唯一 `initial demo` 提交的脚本工程；README 明确 JAVA_HOME / ANDROID_HOME；更新 debug/release APK 与 release mapping 路径；Gradle 基线前执行 clean；ViewHierarchyServer / ViewTapper 捕获型匿名回调改用 lambda；发行 demo zip 排除 Gradle/IDE/Python 本机状态和 local.properties；runtime 变更递增 agentVersion 到 1.0.93。

最终 `:cmd_line:distZip` 产物中的 demo zip 为 40 MB，确认不含 `.gradle`、`local.properties`、`__pycache__` 或 `.pyc`。演示服务与构建入口默认端口统一为 18766，避开本机已有服务占用的 18765。用 JDK 17 / Android SDK 真实运行最终发行包的三个 debug 和三个 release 场景，共六种 Flow：正常增量第二轮都执行 `buildIncrementalApk`；build 文件变更均得到 `build file changed` 并走 Gradle；101 个变更文件均得到 `101 changed files exceed limit 100` 并走 Gradle。六种场景都生成 APK；release 场景归档 mapping/usage。测试 fixture 4 项通过，涵盖三场景 × 两变体；直接用 Build Tools 33.0.1 D8 编译新的 runtime jar 通过。设备安装未执行。

### 16. 同目录重复演示

失败现场：重复运行正常增量场景时，已有 checkout 包含两个提交，`prepare_project.py` 拒绝复用。按用户要求，三个场景启动时直接删除并重建整个 `--work-dir`；清理在场景层，复用脚本不自动删除工作目录。构建入口过滤基线目录已不存在的记录，以便继续使用同一基线服务。

测试价值：同目录重跑能否完成、是否清理整个工作目录是明确可观察行为，扩展已有跨进程 Flow 回归。三个场景 × debug/release 每种组合连续运行两次，共用 HTTP 服务，检查旧文件被删除、Git 恢复两次提交和预期构建结果。新增断言修复前六种组合均失败；修复后通过全部 4 项 Python 测试。同步 README 和 CLI 帮助，说明每轮删除整个工作目录；同步 AI 知识库，Wiki 无受影响入口。

发行验证：JDK 17 下 `:cmd_line:distZip` 成功，8 个 demo Python 文件通过 Python 3.8 语法检查，发行脚本与源码逐文件一致。在用户报错的已有 `/tmp/jugg-demo-normal` 目录上运行最终发行包，确认先清理并重建 checkout，再真实完成 `buildGradleBase` 和 `buildIncrementalApk`，输出 APK，进程退出码为 0。两种降级场景的同目录重复运行由上述 fixture 覆盖，本次未重复执行其真实 Android 构建。

### 17. 中文流程日志与命令输出开关

三个场景增加 `--quite`（用户指定拼写），仅在场景层隐藏 Python/Git/Gradle/Jugg/ADB 子进程输出及命令回显。中文日志独立显示清理、准备、首次构建、源码修改、Git 提交、再次构建、实际构建方式、降级原因、产物路径及可选安装。中文日志不受开关影响，子命令失败显示中文提示并返回非零退出码，禁止继续输出演示完成。

测试 owner 复用跨进程 Flow fixture：每个场景 × debug/release 各运行普通与 quite 模式，验证 stdout/stderr 可见性及中文流程；增加 quite 模式子命令失败验证。修复前 7 个断言场景失败，修复后 4 项 Python 测试通过，覆盖 12 次成功流程和 1 次失败流程。同步 demo README 和 AI 知识库，Wiki 无受影响入口。

发行验证：`:cmd_line:distZip` 成功，8 个 demo Python 文件通过 Python 3.8 语法检查并与发行包一致。最终发行包以 `normal_incremental.py --quite` 真实完成基线及增量 APK 构建，退出码为 0，终端仅保留中文流程、提交、文件数量和产物路径；两种降级场景的输出模式和失败输出由上述 fixture 验证。未执行设备安装。

### 18. 多项目基线记录与存储保留

默认存储改为 `base_build_server.py` 同级的非隐藏 `base-builds.json`，保留 `--data-file` 显式覆盖。服务代码按用户要求使用中文注释，说明项目隔离、旧数据读取、保留顺序及原子替换。运行时 JSON 不提交且从发行包排除。

场景与单次构建入口增加 `--project-id`，默认 `default`；每次 HTTP 请求携带 `projectId`，服务不绑定单一项目。相同记录 ID 按项目独立更新，各项目按接收保存顺序保留最近 100 条；覆盖视为最新保存，淘汰只删除元数据。缺少 projectId 的旧记录归入 default，不做破坏性迁移。

测试价值：项目隔离、覆盖及保留上限、脚本相对默认存储属于稳定外部契约。新增真实 HTTP 测试先复现旧服务不支持项目查询和脚本默认位置的问题；验证两个项目同 ID、105 次保存淘汰最旧记录、覆盖更新次序、available 查询和旧数据读取。跨进程 Flow 复用三场景 × debug/release × 普通/quite 回归，显式传入项目标识并断言保存记录的归属。同步 README 和 AI 知识库，Wiki 无受影响入口。

验证结果：全部 6 项 Python 测试通过；`:cmd_line:distZip` 成功，并对最终发行脚本再运行 2 项 HTTP/默认位置测试通过。Python 3.8 语法、发行脚本与源码一致性、运行时 JSON 不入包及 `git diff --check` 均通过。本次未修改 Android 编译行为，构建链由跨进程 fixture 验证，未重复真实 APK 编译。

### 19. 基线目录统一归档与服务请求日志

按最新需求，服务从只保存元数据改为复制请求的完整 `juggRootPath` 到索引文件同级 `base_build/{projectId}/{id}`，不增加 `baseline/` 子目录。记录移除 `apkPath`；Gradle 基线轮省略可选 `outputApkDir`，直接使用归档中的 `classpath/apk`，不额外保存 APK 副本。增量 APK 继续独立输出；构建结果保留实际 `apkDir`，增加 `runDir` 定位诊断文件，场景中文变更数量日志改读该目录。release mapping/usage 仍保存在对应轮次目录。

服务提供 PUT 写入并兼容原 POST，GET 打印请求参数和完整返回列表，PUT/POST 打印请求参数及完整记录。覆盖同 ID 时完整替换目录，目录复制或索引写入失败恢复原归档和记录。每个项目最近 100 份限制同时作用于索引与服务拥有的归档，清理失败单独记录，历史外部目录不删除。projectId/id 必须是单层目录名，防止越界写入或删除。运行时归档加入 Git 和发行包排除项。

服务归档独立于演示工作目录，旧归档会跨重跑保留。为维持三个演示每轮从无基线开始的契约，准备脚本在 `.git/jugg-demo-id` 创建本轮标识，构建入口匹配 `metadata.demoCheckoutId`，避免同路径重建 Git 后复用上轮演示基线；真实 CI 自行 checkout 时无需该标识。写入请求现在包含完整目录复制，超时从元数据请求的 10 秒调整为 120 秒，查询仍为 10 秒。

失败证据与测试价值：先扩展真实 HTTP owner，原服务对 PUT 返回 501，默认归档目录不存在，新增断言失败。归档内容、同 ID 替换去除残留、覆盖失败恢复、路径边界、项目隔离和物理目录保留上限均为可观察契约，使用 Python 标准库 HTTP/文件系统验证；沿用跨进程 Flow owner 验证三个场景 × debug/release × 普通/quite，共 12 次成功流程和 quite 失败路径。最终 8 项 Python 测试全部通过，最终发行脚本另执行 4 项 HTTP/默认目录回归通过。

发行验证：JDK 17 下 `:cmd_line:distZip` 成功，发行 README 与 8 个 Python 脚本和源码一致，Python 3.8 语法、方法长度和运行时索引/归档不入包检查通过。最终发行包真实运行正常增量 debug 流程，确认 Gradle 基线直接使用服务归档内 APK，随后成功增量编译；服务归档保持干净，没有本地 `baseline/` 或 Gradle 轮独立 APK 副本。现场保留于 `/private/tmp/jugg-demo-archive-smoke-oac077a4`。两种降级及 release 路径由 Flow fixture 覆盖，本次未重复真实构建；未执行设备安装。同步 README、AI 知识库和本方案，Wiki 现有入口与支持范围未改变，不更新。

### 20. Unknown 变更报告、无变更复用和移除 clean

最新需求覆盖四点：`getChangedFiles` 必须报告未识别路径，类型为 Unknown；无可编译变更直接返回已有 APK；确认原始增量命令对非源码输入的行为；移除 demo 的 `gradle clean` 与独立 `demo_release`，本轮不验收真实 release 构建。

`GetChangedFilesCommand` 继续复用 `FileChangesHandler` 识别类型与模块，再按 Git 变更清单逐项输出；未识别项为 `type: Unknown`、`module: ""`，不向核心编译器添加 Unknown 输入类型。补齐命令结束时的 scope/disposer 释放。原始 `buildIncrementalApk` 保持严格输入契约：空参数在解析时失败，包含 README 等未识别文件时报 `not all files are compilable`。

`jugg_build.py` 保留完整诊断清单，排除 Unknown 后判断是否执行增量；100 个文件上限只统计已识别变更。存在兼容基线且没有已识别变更时，直接返回该基线 `classpath/apk`，发布 `mode: reuseBaseApk`、`reason: null`，不调用 Gradle/增量编译、不复制独立 APK、不登记新基线。BuildFile、超过数量限制、缺失输入、没有兼容基线及显式 force 的原有 Gradle 路径保留。Gradle 基线前不再执行 clean，由 Gradle 自行复用模块输出与缓存。

统一 demo 保留 `--scenario release` 入口，删除独立 release shell 示例与发行配置；README 明确本轮仅验收 debug。此前第 14～15 节记载的独立 release 示例保留和全量 clean 处理，已由本节最新实现取代。本轮不修复或验收 AabResGuard 的 release 重复构建问题。

失败证据：扩展既有 Flow fixture，用模块输出文件保护跨 Gradle 基线构建的保留行为，旧 clean 在两个降级场景 × 两变体造成 4 个失败；真实旧发行包对仅 README 的提交返回 `files: []`，没有 Unknown 项，现场在 `/private/tmp/jugg-ci-unknown-red-mwbptji6`。扩展现有 Python policy owner，空变更重复构建会新增基线、101 个 Unknown 错计入增量限制，两项断言先失败。

测试价值与落点：复用 Python policy owner 验证 APK 路径/内容复用、基线数量、Unknown 排除和混合源码编译输入；复用跨进程 Flow owner 保护模块输出、三个既有场景和 quite 失败行为。8 项 Python 回归通过，其中 release 仅为 CLI 编译替身 fixture，不代表真实 release 验收。Unknown JSON 的真实识别依赖已初始化的 Android 基线上下文，采用发行包 CLI 实测，不新增测试专用接口或生产注入点。Kotlin 定向编译与 distZip 成功，Python 3.8 语法、方法长度、diff 格式和发行目录检查通过。

真实 debug 验收现场 `/private/tmp/jugg-ci-unknown-acceptance-fhetfy9n`：先完成基线构建，再分别验证空提交差异和仅 README 返回同一基线 APK，Unknown 元数据完整，基线条数保持 1；直接向增量命令传 README 和空参数均得到预期失败；README 混合 Kotlin 成功增量，原始基线 APK 哈希与干净状态不变；再次 force Gradle 基线构建保留既有模块输出，条数增加至 2。未执行真实 release 构建或设备安装。

同步 demo README、`04_engineering_compat.md` 和本方案。Wiki 现有 CI 页面仍要求调用方提供明确的编译文件集合，其两阶段命令契约未变，具体 Unknown/复用用法已在发行 README 提供，不修改 Wiki。


### 21. 连续演示、显式忽略和空列表 CLI 导出

最新要求取代第 20 节“忽略全部 Unknown”和“CLI 空列表报错”：编排只主动忽略项目明确声明无关的根目录 README.md，其他 Unknown（包括自定义 config.json）降级至 Gradle；Jugg CI 基线选择、变更查询、增量编译或空输入导出失败，同一轮尝试一次 Gradle 基线构建，备用失败保留最终异常。成功降级时演示继续推进提交。

三个独立场景入口合并为 `demo_flow/run.py`。只在启动时清理一次 work-dir，准备工程、配置 config.json 自定义 Gradle Copy task，建立初始基线。随后正常 Kotlin/XML 增量、BuildFile 降级、101 文件降级、README 主动过滤、config.json Unknown 降级依次创建提交，不 reset、不清理构建目录、不重新初始化工程。共 7 个 Git 提交、6 轮结果，`flow-results.json` 保留提交、产物和诊断目录。config.json 作为根目录业务输入写入 APK assets，最终读取 APK 校验配置确实更新。

`buildIncrementalApk` 必须显式提供 changedFiles 参数，空字符串表示空列表；直接从基线 APK 元数据导出到 outputApkDir，跳过编译上下文初始化、自定义编译器、编译与 APK 改写，不写 dirty。实际非空编译仍严格检查类型和 BuildFile、使用独立副本，并在编译前标记 dirty。原始未知非空输入仍失败，由编排执行降级；缺少参数仍失败，已消费基线仍不可再次使用。

失败证据：此前真实空参数现场报 `Param 'changedFiles' not found`；扩展既有 policy owner 后，Unknown 仍被忽略、config.json 未触发降级的 2 项断言先失败。新增回归保留可观察契约：README 与 config 区别、空输入走 CLI、未识别输入降级、Jugg 三处调用失败后改走 Gradle及基线数量。复用跨进程 Flow owner，验证普通/quite、debug/release CLI fixture、同目录重跑、只清理一次、7 个连续提交、五种决策、APK JSON 内容和 Jugg 失败后成功降级；Gradle 失败则流程非零退出。

验证：8 项 Python 回归通过，Kotlin 定向编译和 distZip 成功；发行包真实 debug 连续五场景通过，现场 `/private/tmp/jugg-ci-continuous-qjx0myhj`，6 轮结果和 7 个提交顺序一致，仅归档 4 份干净基线。README 对应 Jugg 空列表导出成功，config.json Unknown 降级后 APK assets 配置更新。直接对同一干净基线连续两次空列表导出成功，APK SHA-256 与基线一致且没有 dirty；最终发行包再次验证两次空导出，并用返回 7 的增量命令包装器触发真实 Gradle 降级成功，APK 配置保持更新、基线数增加为 5。完整五场景结果仍保存在 flow-results.json。未执行真实 release 构建与设备安装。

文档同步：发行 README、04_engineering_compat、98_code_map 和本方案；Wiki supported-packaging 原“基线仅消费一次”说明需补齐空列表导出例外，按 wiki-writer 同步中英文两页并说明 Unknown 由编排主动过滤或降级。Wiki 镜像/链接验证和 production build 通过，两种语言生成页面均含 changedFiles= 说明。
