# 清理 Git 历史中的 Android Studio 实现 JAR

## 背景

提交 `3145229f` 已将当前树中的 Android Studio 实现 JAR 替换为版本化 `stubapi.jar`，但旧实现 JAR 仍存在于 Git 历史对象中。

## 目标

- 保留当前仓库的提交拓扑、作者、时间和提交信息。
- 从所有本地 branch、tag 和 remote-tracking refs 的历史中删除 `deploy_compat/v_*/libs/*.jar`。
- 保留 `deploy_compat/stub_api/v_*/stubapi.jar`。
- 不向任何远端推送改写后的历史。
- 保护并恢复任务开始前的用户工作区修改。
- 对比清理前后的 Git 对象规模，并验证所有 compat 实现 JAR 已从全部 refs 中消失。

## 实施步骤

1. 记录清理前的 HEAD、refs、`git count-objects -vH`、`.git` 磁盘占用及目标 JAR blob 数量和大小。
2. 在仓库外创建完整 mirror 备份，作为原始历史恢复点。
3. 暂存并保护任务开始前的用户工作区修改。
4. 使用 `git filter-repo` 对全部 refs 执行精确路径清洗：

   ```text
   deploy_compat/v_*/libs/*.jar
   ```

5. 恢复 remote 配置和用户工作区修改。
6. 执行 reflog 过期与垃圾回收，使已删除 blob 不再占用当前仓库对象空间。
7. 验证全部 refs、Git objects、当前 tracked files 和 stubapi 保留状态。
8. 执行 compat 定向编译及现有 `DeployCompatArchitectureTest`。
9. 提交清洗后的文档与边界守卫改动；不提交任务开始前的用户修改。

## 验证标准

- `git log --all -- 'deploy_compat/v_*/libs/*.jar'` 无结果。
- `git rev-list --objects --all` 不包含目标路径。
- `git ls-files 'deploy_compat/v_*/libs/*.jar'` 无结果。
- 10 个版本化 `deploy_compat/stub_api/v_*/stubapi.jar` 仍被跟踪。
- `git fsck --full` 通过。
- 所有 compat 模块使用 Stub API 完成定向编译。
- `DeployCompatArchitectureTest` 通过。
- 输出清理前后的 Git 对象规模和 `.git` 磁盘占用。

## 风险与回退

- 所有受影响提交及其后代的 commit hash 都会变化，协作者需要在远端历史替换后重新 clone。
- `3145229f` 之前的历史提交不保证可独立编译，因为真实 JAR 被移除且 Stub 不倒灌到旧提交。
- 原始历史 mirror 备份保存在仓库外，不得发布或推送到清洗后的公开远端。
- 清洗失败时丢弃改写结果，通过 mirror 备份恢复原始仓库。
