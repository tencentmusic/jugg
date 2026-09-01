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
3. develop 每一至两周自行执行一次正式 main merge，复用 dogfood 已积累的 rerere 解法，但不继承 dogfood 日常同步产生的中间 merge。
4. dogfood 始终是本地、可重建、可删除的集成候选，不演变为第三条开发线。
5. develop/main 仅重写 commit 历史、最终 tree 不变时，可以无损重挂载已验证候选，避免完整重建。

## 3. 核心决策

采用“dogfood 持续增量集成，develop 周期性正式 merge”：

```text
dogfood C0
  + develop D1 -> C1
  + main M1    -> C2
  + develop D2 -> C3

定期同步：develop D2 + main M1 -> 正式 merge F
         rerere 复用 C1/C2/C3 中的冲突解法
         F 的 tree 必须与已验证 C3 一致
```

dogfood 在一次同步周期内保留历史，只 merge 新的 develop/main 提交。Git 通过祖先关系跳过已经集成的提交，rerere 只负责复用新冲突的文本解法：

```text
C0 + D1 -> C1
C1 + M1 -> C2
C2 + D2 -> C3
```

正式同步时不 fast-forward dogfood。develop 自己 merge main，复用共享 rerere，并与已验证 dogfood tree 对比。正式 merge 成功后，dogfood 对齐 develop，开始下一轮增量周期；rerere cache 继续保留。

当 develop 或 main 发生 squash/rebase，但记录 SHA 与当前 tip 的 commit tree 完全相同时，允许把已验证 dogfood tree 重挂载到新父节点：

```text
旧候选 C(old D, old M, tree=T)
新 develop D'，tree(D') = tree(old D)

Reanchor R(parent=D', parent=old M, tree=T)
  + main 后续提交 -> 新候选 C'
```

Reanchor 只替换候选的 Git 父节点，不重放文件变更，因此冲突解法、rerere 未覆盖的语义修复和自动合并后的人工兼容修改都会完整保留。tree 不完全一致时禁止使用该路径。

## 4. 分支职责与约束

| 分支 | 职责 | 约束 |
|---|---|---|
| `main` | 已发布稳定线 | 由 main worktree 独立维护 |
| `develop` | 下一版本开发线 | 每一至两周集中接收一次 main |
| `dogfood` | 本地增量集成候选 | 不 push、不设置 upstream、不开发独立功能、周期内保留 merge 历史 |

dogfood 不得包含只服务于自测的产品代码或长期配置。调试性本地状态必须留在未跟踪配置、IDE 设置或外部环境中，不能进入候选提交。

## 5. 一次性配置

多个 worktree 共享同一个 Git common dir，因此 rerere 记录可同时被 dogfood 和 develop worktree 使用：

```bash
git config --local rerere.enabled true
git config --local rerere.autoupdate false
git config --local merge.conflictStyle diff3
```

关闭 `rerere.autoupdate`，确保复用的冲突解法只更新工作区，不自动进入暂存区。每个文件仍需人工审查后再暂存。rerere 只缓存文本冲突解法，不能替代 dogfood 的 Git 祖先关系，也不能恢复自动合并文件中的语义修复。

## 6. Refresh：增量更新候选

### 6.1 固定输入

develop 和 main 先在各自 worktree 中完成更新。dogfood 只读取本地分支引用，不静默改用 `origin/develop` 或 `origin/main`。

开始前记录：

```bash
git rev-parse dogfood
git rev-parse develop
git rev-parse main
```

同时确认：

- dogfood worktree 干净。
- 当前没有 merge、rebase、cherry-pick 或 revert。
- dogfood 没有 upstream。
- develop、main 和 dogfood 都存在于本地。
- 上次记录的 develop/main SHA 仍分别是当前分支和 dogfood 的祖先。

通过 `git worktree list --porcelain` 定位 symbolic `HEAD` 为 `dogfood` 的 worktree。后续整个 Refresh 的 Git、Gradle、review、验证、提交和插件构建都必须以该 worktree 为实际工作目录，并先断言：

```bash
test "$(git symbolic-ref --short HEAD)" = "dogfood"
test -z "$(git status --short)"
```

禁止在 develop 或 main worktree 中继续执行 Refresh。后续命令块省略 `git -C` 仅以此工作目录约束为前提。

候选提交正文使用以下 trailers 记录上轮输入：

```text
Dogfood-Previous: <previous dogfood SHA>
Dogfood-Develop: <develop SHA>
Dogfood-Main: <main SHA>
```

现有旧候选没有 trailers 时，首次迁移可分别使用 `git merge-base dogfood develop` 和 `git merge-base dogfood main` 作为上次已集成 SHA，并在新的候选提交中补齐 trailers。

如果已记录的源 SHA 不再是当前分支祖先，说明发生了 rebase、force-push 或历史替换。此时先进入第 6.2 节的精确 tree Reanchor 判定；不满足条件时停止增量更新，不静默重建 dogfood。

### 6.2 精确 tree Reanchor

Reanchor 是 Refresh 内部的无损快速路径，不是 patch-id 猜测、range-diff 映射或近似移植。只有 commit tree 完全一致才能证明历史重写没有改变最终文件内容。

固定以下快照：

- previous dogfood。
- previous dogfood trailers 中记录的 develop/main SHA。
- 当前本地 develop/main SHA。
- 上述 source SHA 的 commit tree ID。

必须同时满足：

1. previous dogfood 包含两个已记录 source SHA。
2. 至少一个已记录 source SHA 不再是当前 source branch 的祖先。
3. 每个被重写 source 的当前 tip tree 与其已记录 SHA tree 完全一致。
4. 未被重写 source 的已记录 SHA 仍是当前分支祖先；其后续提交留给正常增量 merge。
5. dogfood worktree 干净，且 previous dogfood 不包含独立产品改动。

父节点按 source 独立选择：被重写且 tree 等价的一侧使用当前 tip；未被重写的一侧使用已记录 SHA。develop 始终作为第一父节点，main 作为第二父节点。Reanchor commit 的 `Dogfood-Develop` / `Dogfood-Main` 必须记录这两个实际父节点；尚未合入的当前 source tip 只在正文记录为 pending snapshot，不能提前写入已集成 trailers。

移动 dogfood 前先创建仅本地保存的恢复 ref。同名 ref 已存在时必须先确认它仍指向 previous dogfood，禁止覆盖异常值；不存在时使用 must-not-exist old value 创建：

```bash
backup_ref="refs/dogfood-backups/<previous-dogfood-sha>"
git update-ref "$backup_ref" <previous-dogfood-sha> ""
```

使用 `git commit-tree` 创建 provisional merge commit：tree 必须直接取 previous dogfood tree，parents 使用上述映射，正文记录 source SHA、tree ID、计划验证命令以及 `Dogfood-Reanchor-From` trailer。

branch move 前必须证明：

```bash
test "$(git rev-parse <previous-dogfood-sha>^{tree})" = "$(git rev-parse <reanchor-commit>^{tree})"
git diff --exit-code <previous-dogfood-sha> <reanchor-commit>
test "$(git show -s --format='%P' <reanchor-commit>)" = "<reanchor-develop-parent> <reanchor-main-parent>"
git merge-base --is-ancestor <reanchor-develop-parent> <reanchor-commit>
git merge-base --is-ancestor <reanchor-main-parent> <reanchor-commit>
```

独立 subagent 只读复核固定 SHA、tree ID、父节点映射、恢复 ref 和 provisional commit，明确确认 tree 完全一致且无 blocking/high-risk finding。通过后再次确认 develop/main 未前进，从 `git worktree list --porcelain` 定位 dogfood worktree，只使用 compare-and-swap 更新 dogfood ref：

```bash
git -C <dogfood-worktree> update-ref refs/heads/dogfood <reanchor-commit> <previous-dogfood-sha>
test "$(git -C <dogfood-worktree> rev-parse HEAD)" = "<reanchor-commit>"
test -z "$(git -C <dogfood-worktree> status --short)"
```

禁止执行 `reset --hard`。previous dogfood 与 Reanchor tree 完全一致，干净 index/worktree 无需同步文件；从其他 worktree reset 反而可能误移动 develop 或覆盖错误目录。

Reanchor 后仍执行默认编译门禁和插件构建，因为 commit hash 派生的版本与 build metadata 已变化。验证失败时保留证据，仅当 dogfood 仍指向失败候选时执行以下原子回退，禁止继续尝试近似移植：

```bash
test "$(git rev-parse refs/dogfood-backups/<previous-dogfood-sha>)" = "<previous-dogfood-sha>"
git -C <dogfood-worktree> update-ref refs/heads/dogfood <previous-dogfood-sha> <reanchor-commit>
test "$(git -C <dogfood-worktree> rev-parse HEAD)" = "<previous-dogfood-sha>"
test -z "$(git -C <dogfood-worktree> status --short)"
```

验证成功后只 merge 尚未被新候选包含的 develop/main tip；没有后续提交时，Reanchor commit 即最终候选。

### 6.3 增量合并

只 merge 尚未被 dogfood 包含的固定 source snapshot SHA，固定顺序为 develop、main：

```bash
git merge --no-ff --no-commit <fixed-develop-sha>
test "$(git rev-parse MERGE_HEAD)" = "<fixed-develop-sha>"
git merge --no-ff --no-commit <fixed-main-sha>
test "$(git rev-parse MERGE_HEAD)" = "<fixed-main-sha>"
```

已被 dogfood 包含的固定 snapshot 直接跳过。两边都前进时，先完成第一个 merge commit，再开始第二个；最后一个 merge 保持未提交，完成审查和验证后再提交。两边都没有前进时不创建空提交。

固定 snapshot 后禁止继续 merge 可移动的分支名。完成本轮候选后重新读取 develop/main ref；若前进则开启下一轮 Refresh，若不再 fast-forward 包含本轮 snapshot，则重新进入 Reanchor 判定，禁止盲目增量 merge。

正常 Refresh 禁止 `git reset --hard develop`。只有正式同步成功后对齐 dogfood，或维护者明确要求重建时才允许 reset。

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

Git 自动合并成功的文件也可能存在语义冲突。审查范围包括本轮冲突文件、两个新增提交区间共同修改的文件，以及 `previous dogfood..new candidate` 中的人工兼容修改；日常 Refresh 不重新审查完整历史分叉。

错误的 rerere 解法只按文件清除：

```bash
git rerere forget <path>
```

不得为了单个错误删除整个 rerere cache。

## 8. 独立 Subagent Review 门禁

所有冲突解决完成、合并结果经过主 Agent 审查并暂存后，必须在最终验证和创建候选提交之前委派一个独立 subagent review merge 结果。

传递给 subagent 的上下文保持最小且可核验：

- 仓库路径、previous dogfood SHA，以及前后 develop/main SHA。
- 本轮增量 merge 产生的冲突文件清单，包括已经解决并暂存的文件。
- 要求审查 `previous dogfood..new candidate`、两个新增源区间和当前 behavior owner。

subagent 必须只读工作，不得修改、暂存、提交、reset 或 push。审查范围包含本轮手工解决的冲突，以及新增源区间中双方均修改但 Git 自动合并成功的高风险文件。输出先列 findings，按严重级别排序，并提供文件、行号、证据和修正方向；没有阻断或高风险错误时必须明确说明，并单独列出验证缺口。

主 Agent 必须等待审查结论后才能继续。不得把预期解法或主 Agent 的判断泄漏给 subagent，确保审查独立。

发现阻断或高风险错误时，修正并重新暂存受影响文件，再次委派独立 review，直到没有阻断或高风险 finding。review 期间 develop 或 main 前进时，只完成已经固定并审查的 snapshot 候选，再开启下一轮 Refresh；若新 ref 不再包含本轮 snapshot，则重新进入 Reanchor 判定。无法使用 subagent 或审查未完成时，不得创建候选提交。

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

合并结果通过已选验证后，创建 dogfood 候选 merge commit：

```text
[other] integrate main updates into current candidate

Develop: <develop SHA>
Main: <main SHA>

Preserve <develop behavior>.
Integrate <main behavior>.
Resolve <high-risk conflict decision>.

Verification:
- <command or dogfood scenario>
```

正文记录源 SHA、高风险行为决策和验证证据。简单 import、日期或格式冲突不必逐条记录。

正文末尾记录 `Dogfood-Previous`、`Dogfood-Develop` 和 `Dogfood-Main` trailers。后两个 trailers 必须始终记录本提交实际包含的 fixed develop/main SHA，不能记录审查或验证期间已经前进但尚未合入的当前 tip。提交前必须再次执行 `git rev-parse develop` 和 `git rev-parse main`，并与本轮固定的源 SHA 比较。任一分支已前进时，不得把可移动分支名继续合入当前候选；先安全完成已经固定并审查的 snapshot 候选，再开启下一轮 Refresh。新 ref 不再包含本轮 snapshot 时重新进入 Reanchor 判定，不静默回退到全量重建。

## 11. Promote：develop 正式 merge main

Promote 只能由维护者明确触发，不能因为“更新 dogfood”或“验证 dogfood”而自动执行。

正式同步前必须固定 develop、main 和已验证 dogfood SHA，并确认当前源分支仍被 dogfood 包含：

```bash
git merge-base --is-ancestor develop dogfood
git merge-base --is-ancestor main dogfood
```

任一检查失败，说明 develop 或 main 已前进，必须先增量 Refresh dogfood 并重新验证。

两个检查都通过且验证证据完整时，固定 develop/main SHA，并在实际修改前再次确认 ref 未移动。在 develop worktree merge 固定 main SHA：

```bash
git merge --no-ff --no-commit <fixed-main-sha>
test "$(git rev-parse MERGE_HEAD)" = "<fixed-main-sha>"
```

逐项审查 rerere 恢复的结果并暂存。正式提交前，develop worktree 的未提交 merge tree 必须与固定的已验证 dogfood SHA 完全一致：

```bash
git diff --exit-code <fixed-dogfood-sha>
```

存在差异说明 rerere 没有完整恢复 dogfood 中的冲突解法或语义兼容修改，必须定位并修正，不能仅凭“无冲突”或“可以编译”继续。tree 一致后，在 develop worktree 重新执行约定验证。提交前再次确认 develop、main、dogfood 三个 ref 仍分别等于固定 SHA；任一 ref 已移动时，安全 abort 未提交的正式 merge，按需 Refresh dogfood，再从新固定快照重新 Promote。

正式 merge 成功后，在干净的 dogfood worktree 执行 `git reset --hard develop`，让 dogfood 以新的正式 merge 为下一周期基线。该 reset 必须在已定位的 dogfood worktree 内执行，只移动本地 dogfood；仓库级 rerere cache 继续保留。不得 fast-forward develop 到 dogfood，也不得由本工作流 push。

正式同步后验证 develop 和 dogfood 指向同一 commit。dogfood 的周期内中间 merge 不进入 develop 历史，只有 develop 自己创建的正式 main merge commit 进入后续发布流程。

## 12. 候选失效与失败处理

| 情况 | 处理 |
|---|---|
| Refresh 期间 develop 或 main 前进 | 完成已固定并审查的 snapshot 候选或安全中止，再开启新周期；祖先关系被重写时重新判定 Reanchor |
| 已记录源 SHA 不再是当前分支祖先 | 仅在重写 source 当前 tip tree 与已记录 tree 完全一致时 Reanchor；否则要求显式重建 |
| Reanchor tree 不一致、父节点映射不明确、恢复 ref 缺失或独立复核失败 | 不移动 dogfood，停止并要求显式重建 |
| Reanchor 更新 ref 后验证失败 | 保留失败证据，通过恢复 ref compare-and-swap 回退 dogfood 并停止 |
| dogfood worktree 有未提交改动 | 停止，不自动 stash 或覆盖 |
| 冲突解法错误 | 只 forget 受影响文件并重新解决 |
| Subagent review 不可用或未完成 | 停止，不创建候选提交 |
| Subagent 发现阻断或高风险错误 | 修正合并结果并重新委派 review |
| 编译或测试失败 | 保留失败证据并修正合并结果，不伪造成功 |
| develop merge tree 与 dogfood 不一致 | 停止提交，定位差异并恢复 tree 一致 |
| dogfood 出现 upstream | 停止并移除误配置后再继续 |
| 需要 dogfood 专属产品改动 | 拒绝加入候选，改用外部本地配置 |

## 13. Cleanup：发布后清理

develop 发布完成并确认不再需要 dogfood 后：

1. 确认 dogfood 已在上次正式同步后对齐 develop。
2. 如果 dogfood 正在当前 worktree checkout，先 detach 到 develop commit。
3. 删除本地 dogfood 分支。
4. 清理过期 rerere 记录。

```bash
git branch -D dogfood
git rerere gc
```

默认保留并列出 `refs/dogfood-backups/*`。只有维护者明确要求删除某个恢复 ref 时，才核对并删除该精确 ref；不得无条件清空整个 namespace。

不删除 worktree，不手工清空共享 rerere cache，不执行 push。

## 14. 资产职责

| 资产 | 职责 |
|---|---|
| 本文档 | 保存批准背景、长期约束、状态流转和失败策略 |
| `.agents/skills/jugg-dogfood-sync/SKILL.md` | 指导 Agent 安全执行 Refresh（含精确 tree Reanchor）、Verify、Promote 和 Cleanup |
| dogfood merge commit 正文 | 保存 previous dogfood、本轮源 SHA、高风险冲突决策和验证证据 |
| 独立 subagent review 结果 | 证明冲突解法和高风险自动合并经过独立语义审查 |
| Git rerere | 复用已确认的文本冲突解法 |
| `refs/dogfood-backups/*` | 在 Reanchor 更新 dogfood 前保存可原子恢复的旧候选 |
| 构建、测试和 dogfood 运行结果 | 证明当前候选满足风险对应的验证门禁 |

不为每轮同步持续追加本文档，避免形成重复且快速过期的冲突流水账。

## 15. 非目标

- 不把 dogfood 建设为独立开发线；它只能累计 develop/main 的增量 merge 和必要冲突解法。
- 不通过 CI 或远端分支自动合并。
- 不自动 push dogfood、develop 或 main。
- 不用脚本替代冲突中的行为判断。
- 不用 patch-id、range-diff、部分 tree 匹配或 allowlist 近似证明历史重写等价。
- 不因 dogfood 自测修改产品行为或引入专属配置。
