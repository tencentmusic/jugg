# CI 多仓库变更查询

## 场景与范围

Android_Demo 使用外部源码仓库时，仅对主仓库做 Git diff 会漏掉 SDK 源码及其注解变更。调用方需要知道每个文件所属的 Git 根目录，也需要完整仓库列表以分析没有文件变更的仓库。本次包含 Jugg CLI 与 Android_Demo CI 脚本的配套改动，分别提交到各自仓库。

## 实现

- `getChangedFiles` 保留既有字段与 schemaVersion 1，新增 `files[].gitRoot` 和顶层 `repositories[].{gitRoot,baseCommit,targetCommit}`。
- 主仓库沿用命令参数；外部仓库从基线 deploy history 读取各自基线提交，以当前 HEAD 为目标，解析为完整 hash。仓库集合来自主工程、模块模型和 history；规范化包含 `../` 的旧路径。
- 查询比较提交；工作区状态和构建期间 HEAD 一致性由 CI 编排检查。
- CLI 合并 included-build 快照；基线备份、提交记录和增量恢复使用同一份完整模块模型。归档移动后从清单同目录读取快照；同名不同根模块及缺失快照明确失败。
- 非空增量文件可以位于模型声明的外部模块目录，继续复用原有文件识别与 BuildFile 校验。
- Android_Demo `scripts/jugg_ci/build.py` 遍历全部仓库检查注解补丁与删除/重命名，结果及 dry-run 计划保留仓库列表。正常外部源码变更保留增量；缺少外部基线提交时复用原有 Gradle 入口刷新，备用构建失败保留最终错误。

## 验证

测试价值：保护多仓库提交范围、路径归属、旧基线读取和 included-build 归档恢复等稳定行为，不增加测试专用生产接口。

- 原实现只查询主仓库，外部仓库单独变更会漏报；新增真实临时 Git 仓库用例先确认失败。
- 另一个失败证据：history 路径含 `../` 时报告 Missing baseline commit；规范化读取后通过。
- `GetChangedFilesCommandTest`：7 项，真实 Git 仓库，覆盖外部独立变更、基线提交记录、工程位于仓库子目录、旧单仓库基线、旧路径、外部提交缺失、工作区修改不混入提交查询（L2）。
- `CiProjectInfoTest`：2 项，覆盖 included-build 合并与归档移动、快照缺失（L2）；复用 `LibrariesBackupHelperTest` 1 项。
- Demo Python 编排回归 29 项，覆盖外部仓库增量、注解与删除降级、dry-run、仓库状态改变后拒绝发布（L2）。
- 本地 `:cmd_line:distZip` 构建通过；发行包尚未部署到 HTTP 后端。
- 尝试既有 `CmdLineTest.buildIncrementalApk` Flow（L3），在 `buildBase` 的 classpath 备份阶段失败，未进入增量。现场日志：`~/.jugg/log/android_demo_project_503da503/compile_2026-11-17_23-14-31.0.log`，异常 `Backup classpath and libraries failed`。此测试进程内的失败根因尚未确认。
- 采用等价的发行包 Flow 验证：对本仓库 Android demo 使用绝对路径执行真实 `buildGradleBase`，复制干净基线，调用 `getChangedFiles` 并断言仓库根目录和零变更结果，再显式编译 MainActivity.kt 与 activity_main.xml。2 个输入编译成功，APK 改写、签名及输出成功（L3）；日志为 `/tmp/jugg-ci-multirepo-cli-base.log`、`/tmp/jugg-ci-multirepo-cli-incremental.log`。外部多仓库差异由上述真实 Git 用例验证，Android_Demo 外部 SDK 的实际 APK 构建尚未验收。

## 文档与兼容

同步 `04_engineering_compat.md`、`98_code_map.md`、CLI Demo README，以及 Android_Demo 的脚本 README 与原任务记录。现有 Wiki 的显式 changedFiles 与 Unknown 说明仍适用，本次不修改 Wiki。

旧单仓库基线继续可读；外部仓库缺基线提交时需一次完整 Gradle 刷新。业务方部署新发行包后才能使用新增字段，也可先通过 Demo 的 `CMD_LINE` 配置本地新包。
