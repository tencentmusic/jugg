# 多仓库最近基线选择

## 目标

主仓库不变而外部仓库持续推进时，仅按主仓库提交和保存顺序会选到较旧的外部版本。选择优先保留增量资格，再比较有效变更文件数量，同时控制常规路径分析的候选数量。

## 方案与落点

- Android_Demo PUT 保存完整 `repositories: [{gitRoot, commit}]`；数据来自本次完整构建 history，上传前校验工作区及 HEAD。保留 baseCommit，固定 checkout 路径约束不变。后端已有 opaque JSON 存取，无需修改服务实现。
- 每仓复用现有 Jugg `findNearestBaseCommit`，只沿 first-parent 链找候选提交，映射到完整记录。GET 按服务端 uploadedAt 倒序，同提交取第一份。合并并去重，三仓首轮最多三个候选。
- 各候选独立下载、验证记录/history、识别文件与执行原业务策略；BuildFile、未过滤 Unknown、注解、删除等仅拒绝当前候选。先满足增量，再在本轮选有效文件最少者；同数量保留服务顺序。
- 首轮全部不适合时排除已尝试记录继续查找，处理较旧基线到目标恰好消除 BuildFile 变化的情形。找到可用候选即停止扩展；最终没有可用候选才执行一次 Gradle。
- 缓存同一轮重复仓库范围的业务补丁与状态，相同提交跳过 diff。没有承诺每仓仅一次底层 diff：Jugg 文件识别、源码补丁、name-status 仍各有职责。
- Jugg 查询通过 `CmdLineContextManager.init(initializeApkData = false)` 跳过 APK 解析和 APK 数据库初始化；实际构建默认路径继续初始化。保持查询输出契约及单仓 nearest API 不变。
- 诊断增加 selection、candidates、candidate-results.json，实际选择记录用 baseId 标记；模拟复用全部决策，继续跳过构建和发布。

## 验证与失败证据

测试价值：选择结果、拒绝后的局部扩展、发布提交一致性及模拟副作用是稳定可观察契约。选择与编排由 Demo Python 回归保护（L2）；Jugg 查询副作用采用真实发行包替代验证，无测试专用生产 seam。

- 新选择用例先运行失败：旧代码没有多仓库候选、扩展与元数据提交集合；日志 `/tmp/jugg-selection-red.log`。
- 查询优化前真实零变更查询会调用 ApkParserProcess，并改写复制基线的一份 APK 数据库；优化后相同命令成功、数据库 mtime 集合保持一致且没有 APK 解析进程。本机单次查询约 1.3 秒，仅作为现场测量，不作为通用性能保证。
- Demo Python 回归：44 项通过，覆盖实际 build 决策接线、BuildFile/Unknown 扩展、候选最小有效文件、同提交顺序、旧记录、候选错误隔离、记录/history 不一致、Git 范围缓存、完整 PUT 和 dry-run。
- Kotlin 定向回归：GetChangedFilesCommandTest、CiProjectInfoTest、LibrariesBackupHelperTest 共 10 项通过，distZip 成功。
- 后端现有 HTTP 回归 8 项通过；后端其他工作区改动由独立工作流持有，本任务不修改。
- 新发行包对真实三仓 Git 历史执行最近提交查询，首轮 3 次查询返回去重候选；脚本与现场结果位于 `/tmp/jugg-selection-real-git.py`、`/tmp/jugg-selection-real-git.log`。
- 复制已有 Android demo 完整基线，执行真实查询后显式增量编译 MainActivity.kt 和 activity_main.xml，APK 输出成功（等价 L3）；`/tmp/jugg-selection-incremental.log`。原源码工作区存在既有未提交改动，未清理或覆盖；本次仅使用独立基线副本验证构建默认初始化路径。

## 文档与限制

同步 `04_engineering_compat.md`、Android_Demo README 和本任务记录。现有 Wiki 的显式 changedFiles、Unknown 和基线要求保持适用，未修改 Wiki。

发行包尚未部署；外部 SDK 的实际 Android APK 构建未额外验收。历史基线没有 repositories 时仍可沿主仓库查询，但无法参与外部仓库的最近提交竞争，需后续完整构建自然补全。
