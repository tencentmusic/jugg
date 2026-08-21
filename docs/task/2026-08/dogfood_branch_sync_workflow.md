# Dogfood 分支同步工作流

> 批准日期：2026-08-21
> 适用仓库：Jugg
> 状态：已批准，作为 `jugg-dogfood-sync` Skill 的决策依据

## 1. 背景

Jugg 同时维护两个长期分支：

- `main`：已发布稳定线，持续接收线上版本修复和已发布能力更新。
- `develop`：下一版本开发线，通常每一至两周集中合并一次 `main`。

本地 `dogfood` 用于在正式同步前持续集成两条分支的最新代码并执行自测。该分支不 push，develop 发布完成后删除。

## 2. 目标

工作流必须同时满足：

1. develop 或 main 前进后，dogfood 能快速得到两边最新代码的合并结果。
2. develop 正式合并 main 时复用 dogfood 已确认的冲突解法，避免重复解决冲突或丢失一侧功能。
3. develop 每一至两周只保留一次正式 main merge，不继承 dogfood 日常同步产生的多次中间 merge。
4. dogfood 始终是本地、可重建、可删除的集成候选，不演变为第三条开发线。

## 3. 核心决策

采用“持续重建候选，周期性一次转正”：

```text
develop 快照 D + main 快照 M
        -> dogfood 合并候选 C
        -> 冲突解决与验证
        -> develop --ff-only dogfood
```

dogfood 追求内容始终最新，不保留每次更新的 merge 历史。develop 或 main 任一前进后，都从最新 develop 重新生成候选：

```text
D1 + M1 -> C1
D1 + M2 -> 丢弃 C1，重建 C2
D2 + M2 -> 丢弃 C2，重建 C3
```

前一个候选被重建后，其可复用冲突解法仍由 Git rerere 保存。正式同步时，develop 只 fast-forward 到最终通过验证的候选，因此历史中只有一次面向该同步周期的 main merge。

## 4. 分支职责与约束

| 分支 | 职责 | 约束 |
|---|---|---|
| `main` | 已发布稳定线 | 由 main worktree 独立维护 |
| `develop` | 下一版本开发线 | 每一至两周集中接收一次 main |
| `dogfood` | 本地集成候选 | 不 push、不设置 upstream、不开发独立功能、允许重建 |

dogfood 不得包含只服务于自测的产品代码或长期配置。调试性本地状态必须留在未跟踪配置、IDE 设置或外部环境中，不能进入候选提交。

## 5. 一次性配置

多个 worktree 共享同一个 Git common dir，因此 rerere 记录可同时被 dogfood 和 develop worktree 使用：

```bash
git config --local rerere.enabled true
git config --local rerere.autoupdate false
git config --local merge.conflictStyle diff3
```

关闭 `rerere.autoupdate`，确保复用的冲突解法只更新工作区，不自动进入暂存区。每个文件仍需人工审查后再暂存。

## 6. Refresh：重建最新候选

### 6.1 固定输入

develop 和 main 先在各自 worktree 中完成更新。dogfood 只读取本地分支引用，不静默改用 `origin/develop` 或 `origin/main`。

开始前记录：

```bash
git rev-parse develop
git rev-parse main
git merge-base develop main
git rev-list --left-right --count develop...main
```

同时确认：

- dogfood worktree 干净。
- 当前没有 merge、rebase、cherry-pick 或 revert。
- dogfood 没有 upstream。
- develop、main 和 dogfood 都存在于本地。

### 6.2 重建

只允许在干净且当前分支明确为 dogfood 的 worktree 中执行：

```bash
git reset --hard develop
git merge --no-ff --no-commit main
```

`reset --hard` 只用于丢弃旧 dogfood 候选。不得对 develop、main、仓库根目录或不明确的分支执行相同操作。

使用 `--no-commit` 保持合并结果未提交，先完成冲突审查和验证，再生成最终 merge commit。

## 7. 冲突解决

冲突文件通过以下命令定位：

```bash
git diff --name-only --diff-filter=U
git status --short
git rerere diff
```

每个冲突必须回答：

1. develop 在该位置拥有或迁移了什么行为。
2. main 新增、修复或优化了什么用户可观察结果。
3. 合并结果如何同时保留两边仍有效的契约。

禁止对核心文件整份接受 ours 或 theirs。包迁移、owner 变化和 modify/delete 冲突必须先定位当前 behavior owner，再把另一侧有效行为迁入正确位置。

Git 自动合并成功的文件也可能存在语义冲突。双方都修改过的编译、部署、项目模型、Runtime、版本和测试 owner 应进入人工审查范围。

错误的 rerere 解法只按文件清除：

```bash
git rerere forget <path>
```

不得为了单个错误删除整个 rerere cache。

## 8. 独立 Subagent Review 门禁

所有冲突解决完成、合并结果经过主 Agent 审查并暂存后，必须在最终验证和创建候选提交之前委派一个独立 subagent review merge 结果。

传递给 subagent 的上下文保持最小且可核验：

- 仓库路径和本轮记录的 develop/main SHA。
- merge 后产生的冲突文件清单，包括已经解决并暂存的文件。
- 要求对照两个源快照、当前 behavior owner 和暂存区结果判断合并是否同时保留双方有效行为。

subagent 必须只读工作，不得修改、暂存、提交、reset 或 push。审查范围包含所有手工解决的冲突，以及双方均修改但 Git 自动合并成功的高风险文件。输出先列 findings，按严重级别排序，并提供文件、行号、证据和修正方向；没有阻断或高风险错误时必须明确说明，并单独列出验证缺口。

主 Agent 必须等待审查结论后才能继续。不得把预期解法或主 Agent 的判断泄漏给 subagent，确保审查独立。

发现阻断或高风险错误时，修正并重新暂存受影响文件，再次委派独立 review，直到没有阻断或高风险 finding。review 期间 develop 或 main 前进时，当前候选失效并重新 Refresh。无法使用 subagent 或审查未完成时，不得创建候选提交。

## 9. 验证门禁

所有验证遵循 `docs/ai_knowledge/06_testing.md`。默认集成门禁为：

```bash
git diff --cached --check
./gradlew :idea:compileKotlin :main:compileTestKotlin :cmd_line:compileTestKotlin
```

再根据冲突和双方重叠修改的 behavior owner 执行定向测试。禁止无 `--tests` 过滤的全量 `:main:test` 或 `:idea:test`。

正式转正前还必须执行：

- `./gradlew :idea:buildPlugin`
- 涉及编译或部署编排时的对应 L3 Flow 或已有等价回归
- main 本轮用户可见变化
- develop 当前重点能力
- 高风险冲突对应的实际 dogfood 场景

编译成功只能证明 API 接线与类型兼容，不能替代插件运行、增量编译、部署和 IDE 行为验证。

验证发现合并问题时，先保留失败证据，再修正并重新暂存 merge 结果、重新委派独立 review，最后重跑受影响的验证。暂存区 merge 结果发生任何变化，之前的 subagent review 都立即失效。

## 10. 候选提交

合并结果通过已选验证后，创建面向 develop 的 merge commit：

```text
[other] integrate main updates into develop

Develop: <develop SHA>
Main: <main SHA>

Preserve <develop behavior>.
Integrate <main behavior>.
Resolve <high-risk conflict decision>.

Verification:
- <command or dogfood scenario>
```

正文记录源 SHA、高风险行为决策和验证证据。简单 import、日期或格式冲突不必逐条记录。

提交前必须再次执行 `git rev-parse develop` 和 `git rev-parse main`，并与本轮固定的源 SHA 比较。任一分支已前进时，不得提交失效候选，必须重新 Refresh。

## 11. Promote：同步到 develop

Promote 只能由维护者明确触发，不能因为“更新 dogfood”或“验证 dogfood”而自动执行。

转正前必须确认当前源分支仍被候选包含：

```bash
git merge-base --is-ancestor develop dogfood
git merge-base --is-ancestor main dogfood
```

任一检查失败，说明 develop 或 main 已前进，当前候选失效，必须重新 Refresh 和验证。

两个检查都通过且验证证据完整时，在 develop worktree 执行：

```bash
git merge --ff-only dogfood
```

不得在 develop 上再次执行 `git merge main`，也不得在 fast-forward 失败后降级为普通 merge。失败意味着候选已不再是当前 develop 的精确集成结果，应返回 Refresh。

转正后验证 develop 和 dogfood 指向同一 commit。dogfood 分支引用不 push，但候选 merge commit 已成为 develop 历史的一部分，可随 develop 后续发布流程正常处理。

## 12. 候选失效与失败处理

| 情况 | 处理 |
|---|---|
| 验证期间 develop 或 main 前进 | 当前候选失效，重新 Refresh |
| dogfood worktree 有未提交改动 | 停止，不自动 stash 或覆盖 |
| 冲突解法错误 | 只 forget 受影响文件并重新解决 |
| Subagent review 不可用或未完成 | 停止，不创建候选提交 |
| Subagent 发现阻断或高风险错误 | 修正合并结果并重新委派 review |
| 编译或测试失败 | 保留失败证据并修正合并结果，不伪造成功 |
| `--ff-only` 失败 | 不普通 merge，重新 Refresh |
| dogfood 出现 upstream | 停止并移除误配置后再继续 |
| 需要 dogfood 专属产品改动 | 拒绝加入候选，改用外部本地配置 |

## 13. Cleanup：发布后清理

develop 发布完成并确认不再需要 dogfood 后：

1. 确认 dogfood 已被 develop 包含。
2. 如果 dogfood 正在当前 worktree checkout，先 detach 到 develop commit。
3. 删除本地 dogfood 分支。
4. 清理过期 rerere 记录。

```bash
git branch -D dogfood
git rerere gc
```

不删除 worktree，不手工清空共享 rerere cache，不执行 push。

## 14. 资产职责

| 资产 | 职责 |
|---|---|
| 本文档 | 保存批准背景、长期约束、状态流转和失败策略 |
| `.agents/skills/jugg-dogfood-sync/SKILL.md` | 指导 Agent 安全执行 Refresh、Verify、Promote 和 Cleanup |
| merge commit 正文 | 保存单轮源 SHA、高风险冲突决策和验证证据 |
| 独立 subagent review 结果 | 证明冲突解法和高风险自动合并经过独立语义审查 |
| Git rerere | 复用已确认的文本冲突解法 |
| 构建、测试和 dogfood 运行结果 | 证明当前候选满足风险对应的验证门禁 |

不为每轮同步持续追加本文档，避免形成重复且快速过期的冲突流水账。

## 15. 非目标

- 不把 dogfood 建设为长期第三开发线。
- 不通过 CI 或远端分支自动合并。
- 不自动 push dogfood、develop 或 main。
- 不用脚本替代冲突中的行为判断。
- 不因 dogfood 自测修改产品行为或引入专属配置。
