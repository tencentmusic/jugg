# standalone CLI rebase 经验与执行手册

## 1. 本次 rebase 的可复用经验

1. **先备份，再改变提交图**
   - `feature/standlone_cli_old` 保留了 rebase 前的精确 HEAD，是冲突取舍、提交完整性和回滚的共同参照。
   - 备份分支不能覆盖；下一次应使用带日期或序号的新名字，并在 review 完成前保留。

2. **冲突解决按行为 owner，而不是按 ours/theirs**
   - 包迁移、modify/delete 和宿主依赖拆分时，feature 提供结构边界，main 提供较新的行为修正；两者必须逐项合并。
   - 重点保留的不是某一侧的整文件，而是可观察契约：锁范围、重试、路径字段、配置 fallback、缓存和测试断言。

3. **文本无冲突不代表语义无冲突**
   - 本次在 rebase 完成后才通过编译和定向测试发现旧包引用、`agpR8Classpath`/`buildDirRelativePath` 丢失，以及 mock `RunManager` service 缺失等问题。
   - 架构迁移类 commit 即使自动应用，也必须检查构造参数、包路径、宿主 service 和数据字段传播。

4. **删除旧能力必须单独做行为审计**
   - `SuggestRunConfiguration` 的删除没有形成 Git 文本冲突，但实际丢失了 Active Build Variant 同步能力。
   - 以后遇到“废弃/替换”提交，应列出旧入口的用户可观察行为，逐项确认新模型是否覆盖，不能把“能编译”当作等价替换。

5. **文档也是冲突资产**
   - 知识库冲突不能简单选择一侧；应保留 main 的最新排查和工程约束，再补入 feature 的新职责边界。
   - 每个冲突提交记录原提交、最终提交、冲突文件、双方意图、取舍理由和验证证据，才能支持后续 review。

## 2. 下一次 rebase 的建议流程

1. 确认工作区干净，记录当前分支 HEAD、`main` HEAD、共同祖先和待重放提交数。
2. 创建不可覆盖的备份分支，例如 `feature/standlone_cli_rebase_20260801_old`，并记录其指向。
3. 固定本轮 `main` 基线后开始 rebase；不要在 rebase 过程中隐式跟随移动的本地 `main`。
4. 每个冲突暂停点先读取 `git status`、`git show REBASE_HEAD` 和双方父提交 diff，再按行为 owner 解决；解决后只暂存当前冲突文件并继续。
5. 每完成一个架构迁移或宿主边界 commit，至少执行对应模块编译或最小定向测试；不要把所有语义问题推迟到最后。
6. rebase 完成后执行三类检查：
   - 提交完整性：用 `git range-diff` 对比备份分支与新分支，确认每个 feature commit 都有对应 patch。
   - 基线完整性：检查新分支相对 `main` 只包含 feature 变更，并抽查 main 最近变更涉及的行为 owner。
   - 行为完整性：执行 L1/L2/L3 定向测试、编译、`git diff --check`，并记录环境性失败及其证据。
7. rebase 产生的兼容修正使用独立 commit，避免把“重放冲突解法”和“后续发现的语义修复”混成无法审计的一步。
8. review 完成前保留备份分支；确认无误后再由维护者决定是否删除或长期归档。

## 3. 当前状态

- 当前工作分支：`develop`。
- 本轮 rebase 前备份：`backup/develop-before-rebase-20260813`，指向 `1e019b6b5`。
- 当前 main 基线：`cd6079fbe`（`3.2.5`）。
- 40 个原提交重放后的功能 HEAD：`e0f510467`。
- 验证完成时的功能 HEAD：`52bcc7699`，包含独立测试兼容修正；其后仅增加本轮记录文档提交。
- 最新冲突与验证详情参见 [standalone CLI rebase main 记录](standalone_cli_rebase_main_record.md) 第 18 节。
- review 完成前保留本轮备份引用；下一次 rebase 应新建备份，不复用当前备份名。
