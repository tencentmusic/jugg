# 修复 IndexNow 提交脚本在基准 Commit 缺失时的执行失败

## 1. 任务背景
在 GitHub Pages 发布工作流 `.github/workflows/wiki-pages.yml` 的 `Notify IndexNow of Wiki changes` 步骤中，脚本 `docs/wiki/scripts/submit-indexnow.mjs` 会执行 `git diff $INDEXNOW_BEFORE HEAD -- docs/wiki` 计算改动页面。
当分支遭遇 force push 或历史被 amend 重写时，GitHub push 事件中的 `github.event.before` 指向的 commit 在 Runner 干净检出的仓库中并不存在（远程 Git 服务不会传输未引用的孤立对象），导致 `git diff` 抛出 `fatal: bad object <commit-hash>`，工作流红灯中断。

## 2. 根因分析
- Runner 上执行 `actions/checkout@v6` 时即使带 `fetch-depth: 0`，也只能拉取到当前活跃分支/tag 可达的 commit。
- Force push / amend 导致旧的 `before` commit 成为 dangling commit。
- 脚本缺乏对基线 commit 可达性的判断，直接运行 `git diff` 导致命令抛出异常退出码 128。

## 3. 修复方案
遵循 Best-effort 与奥卡姆剃刀原则：
1. 在 `docs/wiki/scripts/submit-indexnow.mjs` 中添加 `hasCommit(commit)` 检查：利用 `git cat-file -e "${commit}^{commit}"` 判断基线 commit 是否在本地 Git 数据库中存在。
2. 当 `before` commit 存在且合法时，保持原有的增量计算逻辑。
3. 当 `before` commit 不存在时，打印 warning 提示，并安全回退为全量提交（`allPageUrls()`），避免工作流中断。

## 4. 验证结果
- **无基线 commit 场景**：`INDEXNOW_BEFORE=0123456789abcdef0123456789abcdef01234567 node docs/wiki/scripts/submit-indexnow.mjs --dry-run` 正常打印 warning 并降级输出全量 284 个 URL，退出码为 0。
- **正常增量场景**：`INDEXNOW_BEFORE=HEAD~1 node docs/wiki/scripts/submit-indexnow.mjs --dry-run` 正常执行 `git diff`，退出码为 0。
- **显式全量场景**：`INDEXNOW_ALL=true node docs/wiki/scripts/submit-indexnow.mjs --dry-run` 正常输出全量 URL，退出码为 0。
- **参数缺失保护**：未提供任何参数时正常抛出 `INDEXNOW_BEFORE is required for a push`，退出码为 1。
