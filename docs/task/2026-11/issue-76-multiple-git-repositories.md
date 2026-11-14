# Issue #76：多 Git 仓库变更误移除

## 证据与原因

Issue：https://github.com/tencentmusic/jugg/issues/76；报告 f4e60a85，3.5.5-release。
11 月 3 日 13:10 已识别 6 个变更文件，过滤阶段却只登记主仓库和 userCenter，随后全部判为 rollback。
DeployHistoryDb.filterUnchangedFiles 使用 firstNotNullOfOrNull 遍历，正常分支返回 Unit，首个条目后结束，导致其他子仓库文件按主仓库比较。

## 实施范围

改为 forEach 遍历全部子仓库；空 commit 只跳过当前条目。保留历史 CRC 优先和既有 Git 比较行为。同步编译知识库。

## 验证

先确认新增回归失败，再修改实现。`DeployHistoryDbCrcCacheTest` 定向通过，覆盖首个、中间、末尾仓库、已提交与未提交改动、新增文件、真实回退及空基线条目；性能基准按既有开关跳过。
本次提交拆分仅调整 Git 历史和任务方案，生产代码及测试内容与已验证版本一致。
