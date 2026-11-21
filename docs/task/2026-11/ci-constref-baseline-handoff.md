# Jugg CI ConstRef 基线复用

> 核对日期 2026-11-21。本方案替换同名的首版交接稿：去掉 Gradle 期间预扫和旧仓库键迁移，把改动拆成三个提交，并处理评审发现的两处漏补编（§2.3）。

## 1. 问题

修改编译期常量后，引用方字节码里可能还内联着旧值。增量构建需要两样东西：基线中的旧常量定义，以及未变更文件中的引用记录，后者只有全量扫描才会写入。CI 场景下原有链路有三处缺口：

1. `buildIncrementalApk` 没有把调用方传入的变更登记到 `DeployFileManager`。`compiledFiles` 只来自这次登记，常量影响查询因此总是拿到空列表。编译继续到后续轮次时，合并 Dex 只使用最后一轮的输出，前几轮编译的类会丢失。
2. ConstRef DB 位于用户级目录，CI 恢复基线时带不走。CLI 进程也从不触发全量扫描：`initializeFullScan` 只在 IDE 调用的 `updateModuleInfos` 中执行。
3. 仓库键取 Git 公共目录的绝对路径。不同 runner、不同 clone 的路径各不相同，解析结果无法复用。

## 2. 方案与提交拆分

| 提交 | 内容 | 影响面 |
| --- | --- | --- |
| `[bugfix][cli]` | 编译前调用 `addChangedFile`；合并 Dex 时使用 staging 中各轮的全部输出 | 仅 CLI 增量 |
| `[feature][cli]` | 把 ConstRef 索引写入 CI 基线，并支持从历史基线复用 | 仅 CLI；IDE 只改为可注入引擎，默认行为不变 |
| `[optimize][compile]` | 仓库键优先使用规范化后的 `remote.origin.url`；查询和删除区分工作副本；DB schema 升到 10 | IDE 与 CI |
| `[bugfix][compile]` | 影响查询覆盖本项目已注册的全部仓库；CLI 增量在编译前注册基线模型的源码根 | IDE 与 CI |

### 2.1 CI 基线索引

- `buildGradleBase` 在 Gradle 完成后，按本轮项目模型扫描普通源码根，与依赖备份、classpath 备份并行。扫描排除模块构建目录，以及模型声明的外部构建生成源码目录。
- 基线扫描使用 `BASELINE_SCAN` 场景：关闭 IDE 全扫节流，跳过 mtime 快速命中，按内容 checksum 复用已有解析。
- 可选参数 `previousBaselineDir` 指向已解压、且与本轮输出分开的历史基线目录。Jugg 只把其中的 `database/const_ref/const_ref_shared.db` 复制到临时目录，作为扫描起点；缺失或复制失败时冷扫。参数保持目录形式，与 Android_Demo 现有编排兼容。
- 扫描后 `retainCurrentAnalyses` 只保留本轮源码文件的当前版本。关闭 SQLite 后，将索引原子发布到本轮基线；失败时只告警并丢弃索引，其他基线产物照常交付。
- CI 解析器使用 `ConstRefRepoPathResolver(projectDir)`。工作副本键取仓库相对主工程的位置 `baseline:<相对路径>`，在仓库没有可用网络 origin 时也作为仓库键，使索引跨 runner 可用。

### 2.2 仓库键改用 origin

- `remote.origin.url` 规范化为 `origin:<host>[:非默认端口]/<path>`。HTTPS 与 scp 风格的 SSH 写法可以归一，认证信息、`.git` 后缀和默认端口不计入。无可用网络 origin 时，IDE 回退到 Git 公共目录键，CI 回退到相对位置键。
- 共享仓库键后，同源的独立 clone 会共用解析记录。旧查询按 `analyzed_at` 取最新版本，一个 clone 刚分析过的版本会盖过另一个 clone 的当前版本。linked worktree 原本就共用 commondir 键，同样存在这个问题。现在查询优先使用当前 `worktreeKey` 在 `file_checksum_mtime_map` 中映射的 checksum。`removeFile` 保留其他工作副本仍在映射的版本。schema 不变。
- 旧键数据不做迁移，`DB_SCHEMA_VERSION` 从 9 升到 10。IDE 打开时重建并冷扫一次；CI 的旧基线在增量时被重建为空索引，按 best-effort 继续（§2.3），下一次全量基线生成新索引。

### 2.3 评审发现的漏补编

| 问题 | 根因 | 处理 |
| --- | --- | --- |
| schema 9 的旧基线在增量时被重建成空索引，增量照常报告成功，调用方仍内联旧值 | 引擎打开不兼容的库会告警（`recreate db due to incompatible schema`）并重建为空库 | **不改，符合预期。** ConstRef 与 IDE 一致按 best-effort 设计，不阻塞编译。新版本生成的基线自带兼容索引，只有升级前的旧基线受影响；缺少索引的旧基线同样按空索引继续。需要避开过渡期时，由编排在升级 Jugg 时提升场景版本，强制生成新基线 |
| 主仓库常量变化后，源码根位于另一个 Git 仓库的调用方没有补编 | 影响查询只取变更文件所在仓库；CLI 增量也从不注册项目源码根，其他仓库的相对路径无法还原。本次改动前已存在，IDE 的多仓库工程同样受影响 | 影响查询使用本项目已注册的全部仓库；IDE 由 full scan 注册，CLI 在 `buildIncrementalApk` 初始化上下文后调用 `registerSourceDirs()`。单文件分析时的定义查找仍按文件所在仓库，避免放大全扫成本 |

### 2.4 放弃的方案

| 方案 | 放弃原因 |
| --- | --- |
| Gradle 运行期间预扫：用历史模型恢复源码根、映射历史路径、两阶段扫描 | Android_Demo 收尾约 8.4 秒，ConstRef 与之并行。冷扫约 9.3 秒，复用约 1.8 秒，预扫最多只能省约 1 秒，却要引入历史模型恢复、路径映射，以及"两阶段之间源码不变"的约束 |
| 访问时把旧键迁移到 origin 键 | 迁移 SQL 最难验证。升 schema 后重建一次就能得到同样结果 |
| 增量遇到旧索引时现场冷扫整个工程 | 增量命令的职责是快速出包；索引应由全量基线负责生成 |
| 增量遇到缺失或不兼容的索引时直接失败，由编排降级为全量 | 曾实现并验证，评审后撤回：常量分析是增强能力，按 best-effort 不阻塞编译，与 IDE 行为一致；影响只限升级前的旧基线 |
| IDE 保持 commondir 键，只在 CI 使用 origin | IDE 中同源的独立 clone 也需要共享解析结果 |

## 3. 性能影响

在本机 IDE 库副本（18,073 条分析记录，JOOX 单仓库 11,876 条）上对比新旧 SQL：

- 候选引用查询：旧版 20ms，新版 16ms。新版只对查询涉及的仓库开窗。
- 精确引用查询：新增"只取当前版本"过滤，约多 14ms，只在常量确实变化时触发。暂不优化，可选做法是只检查命中的引用记录是否为当前版本。

## 4. 验证

以下结论均在本次拆分后的代码上重新取得，场景为 `cmd_line/src/demo` 的 demo 工程。新增 `ConstRefDefs.VALUE = 111` 和调用方 `ConstRefCaller.read()`，先强制生成一份 Gradle 基线，再提交 `VALUE = 222` 并走增量，最后用 `dexdump` 读取 APK。

| 验证 | 代码 | 结果 |
| --- | --- | --- |
| 失败证据 | `6749c1af3`（改动前） | 增量只编译 `ConstRefDefs.java`，没有 continue compile。增量 Dex 中 `VALUE = 222`，`ConstRefCaller.read()` 仍返回 `111` |
| 提交 1 + 2 端到端 | `fcc9015cb` 工作区 | 日志出现 `constRef effected files=[ConstRefCaller.java]` 和 `continue compile`。增量 Dex 中定义方与调用方都是 `222`。基线 DB 完整性检查通过，150 个文件，键均为 `baseline:`，没有 `build/` 下的记录；收尾总耗时 2.4 秒，其中 `Publish ConstRef index` 1287ms |
| 基线复用 | 同上，手动传 `previousBaselineDir` | 150 个文件按 checksum 复用解析结果，只重新解析改过的 `ConstRefDefs.java`；发布耗时 989ms |
| 三个提交端到端 | `764824318`，demo 工程配置 `git@Example.com:org/jugg-demo.git` 为 origin | 定义方与调用方都是 `222`。归档 DB 的 schema 为 10，仓库键为 `origin:example.com/org/jugg-demo`，工作副本键为 `baseline:` |
| 评审复现：跨仓库调用方 | 评审夹具（`app` 引用 `caller-repo` 独立 Git 仓库的源码根），同一份 schema 10 基线 | 修复前 CLI 识别到 `111 -> 222` 但报告无受影响文件，`Caller.result()` 仍为 `111`；修复后命中 `caller-repo/.../Caller.java` 并补编，增量 Dex 中为 `222` |
| 定向自动化 | `ConstRefIntegrationTest`、`ConstRefCacheDatabaseTest`、`ConstRefEngineTest`、`RepoSharedFingerprintStoreTest`、`ConstRefSessionCacheTest`、`ConstDefinitionIndexTest`；`cmd_line` 的 `CmdLineTest`、`CiProjectInfoTest`、`GetChangedFilesCommandTest` | 最终 89 项与 15 项全部通过。提交 3 的同源 clone 测试、schema 测试和评审修复的跨仓库测试均先确认失败再实现。`:idea:compileKotlin` 通过 |

自动化测试的覆盖范围：

- 基线重建时删除已移出的源码根，并识别 mtime 不变但内容变化的文件。
- 基线复制到另一个 checkout 后，能找到未变更的调用方。
- 同源 clone 共享解析结果，但各自保留当前版本，引用查询和删除互不影响。
- schema 版本低于 10 的库会被重建。
- 常量定义所在仓库之外的仓库里的调用方能被找到。

提交 1 和 CLI 侧的源码根注册时机没有新增自动化测试：它们位于完整的 Android 构建链路上，用端到端复现代替。

注意事项：

- `main` 模块的测试编译目前被既有的 `LibraryTestApkBuildHistoryTest.FakeGitManager` 阻塞（缺少 `findNearestFirstParentCommit`）。本次跑测试时临时移开了该文件，没有修改。
- 手动调用 `buildGradleBase` 时，`baseBuildProjectDir` 要与工程记录的真实路径一致，例如 macOS 上应传 `/private/tmp` 而不是 `/tmp`。否则 classpath 备份会以 `/` 为 rsync 源目录遍历整盘。这个问题早已存在，与本方案无关。

## 5. 接入与排查

1. 需要复用时，编排在规则触发全量时传入 `previousBaselineDir=<已解压的候选基线目录>`。首次构建，或增量失败后的全量，省略该参数。
2. 全量日志应出现 `Scan Gradle source roots for baseline constant references.` 和 `Publish ConstRef index in Nms.`。归档中应包含 `database/const_ref/const_ref_shared.db`。
3. 增量日志应出现 `add changed files`、`constRef effected files` 和 `continue compile`，增量 Dex 中定义方和调用方都应更新。
4. 升级到本版本后，IDE 首次打开会因 schema 重建冷扫一次。CI 中旧基线（schema 9 或无索引）的增量按 best-effort 继续，常量变化不会补编调用方，日志有 `recreate db due to incompatible schema` 告警；生成新基线后恢复。需要避开过渡期时，升级 Jugg 的同时提升编排的场景版本。

相关参考：[常量引用分析知识库](../../ai_knowledge/03_deploy_const_ref.md)、[CI 命令与基线编排](../../ai_knowledge/04_engineering_compat.md#42-命令行入口)、[多仓库基线选择](ci-multi-repository-selection.md)。
