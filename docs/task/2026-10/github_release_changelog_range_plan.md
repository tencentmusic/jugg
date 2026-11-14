# GitHub Release 按发布区间汇总 changelog 的实现方案

创建日期：2026-10-25。更新日期：2026-10-27。状态：已实现并完成定向验证，已生成本地模拟结果。

## 1. 目标与现状证据

发布版本为 `current`，上一个已发布正式版本为 `previous`，GitHub Release 说明应包含：

```text
previous < changelog.version <= current
```

下界不包含，上界包含。版本按数字比较，不按发布日期、YAML 文件顺序或字符串大小比较。

改动前 `.github/workflows/release.yml` 调用 `tools/generate_release_notes.rb <tag>`；脚本只从中英文 RC YAML 中精确匹配当前版本，未查询历史 Release，也不读取 HTML 汇总内容。

2026-10-25 调查时的实际验证记录：

- GitHub Releases API 返回的最高正式版本是 `3.5.5`，没有 `3.6.x` Release；`dev-latest`、`canary-nightly` 属于预发布。
- `ruby tools/generate_release_notes.rb 3.6.6` 每种语言只输出 2 条。
- 两份 RC YAML 中，`3.5.5` 之后有 `3.6.0、3.6.2、3.6.3、3.6.4、3.6.5、3.6.6`，合计 16 条/语言；HTML 已汇总这些版本，但现有发布链路不读取 HTML。

验收目标：发布 `3.6.6` 时汇总上述六个版本的 16 条/语言，不包含 `3.5.5`。所有条目合并到本次 Release 下，依次按 feature、optimize、bugfix 分类，组内按时间从早到晚排序；标题只保留本次发布版本，不展示内部版本标题。

## 2. 上一个正式 Release 的确定规则

在现有 Generate bilingual release notes 步骤中，通过 GitHub API 获取仓库全部 Release，再交给 Ruby 脚本选择下界：

1. 只接收 `draft == false` 且 `prerelease == false` 的 Release。
2. tag 只识别 `X.Y.Z` 或 `vX.Y.Z`，三个分量为非负整数，除 `0` 外不接受前导零；规范化时移除可选的 `v`。
3. 只保留规范化版本严格小于 `current` 的候选。
4. 按数字版本取最大值作为 `previous`；同一个版本的带 `v` 和不带 `v` tag 视为同一候选。

“上一个”指版本序列中最近的已发布正式版本。它不是最近发布时间、最近 Git tag 或 GitHub 的 Latest 标记。这样重跑当前 Release 时不会把自己当作下界；之后出现更高版本，也不会影响较低版本重跑时的候选。

不增加 Git 提交祖先关系筛选；本方案沿用用户确认的版本区间语义。

| 场景 | 处理 |
|---|---|
| `current=3.6.6`，正式版最高为 `3.5.5` | 下界为 `3.5.5` |
| 已存在正式 `3.6.6`，重跑 `3.6.6` | 排除自身，仍取 `3.5.5` |
| 已存在正式 `3.7.0`，重跑 `3.6.6` | 排除更高版本 |
| `3.6.5` 只有 Git tag，未发布 Release | 不构成下界 |
| `3.6.5` 是草稿或预发布 | 不构成下界 |
| 候选包含 `3.6.9`、`3.6.10`，当前为 `3.6.11` | 下界为 `3.6.10` |
| 没有低于当前版本的正式 Release | 明确失败；本仓库已有历史正式版本，本轮不增加首次发布策略 |

Release 被删除、草稿被正式发布等外部状态变化，可能改变后续重跑的下界；本轮不新增历史快照存储或手工覆盖参数。

## 3. 最小实现与职责

### 3.1 Workflow：读取 Release 元数据

现有 Generate bilingual release notes 步骤增加 `GH_TOKEN: ${{ github.token }}`，使用当前仓库的 `GITHUB_REPOSITORY`，完整分页获取数据：

```bash
gh api --paginate --slurp "repos/$GITHUB_REPOSITORY/releases?per_page=100" \
  > "$RUNNER_TEMP/releases.json"
ruby tools/generate_release_notes.rb "$GITHUB_REF_NAME" "$RUNNER_TEMP/releases.json" \
  > "$RUNNER_TEMP/release-notes.md"
cat "$RUNNER_TEMP/release-notes.md" >> "$GITHUB_STEP_SUMMARY"
```

`--slurp` 产生“各页数组组成的外层数组”，Ruby 明确按这一格式解析并展平一层。已通过本地 `gh api` 实际读取验证；不将 `--slurp` 与 `--jq` 混用。

获取数据与生成说明均在现有插件构建、GitHub Release 创建/更新之前执行。API 请求失败直接结束当前步骤，不能将空列表视为成功，也不回退成只读取当前版本。沿用已有 token 权限，不新增 secret、配置开关或自动重试。

### 3.2 Ruby：选择区间、校验、生成说明

脚本命令调整为两个必填位置参数：

```text
ruby tools/generate_release_notes.rb <current-tag> <releases-json>
```

仓库内唯一生产调用方是上述 workflow，同步更新。省略第二参数时明确报错，不保留容易产生遗漏的单版本默认行为。网络读取只由 workflow 负责，Ruby 从输入文件读取 JSON，可在本地离线复现和测试。

脚本继续使用现有中英文 RC YAML，增加以下职责，使用少量私有方法即可，不引入新类或第三方依赖：

1. 校验当前版本、Release JSON 结构及参与选择的 Release 元数据，按第 2 节选择 `previous`。
2. 按相同版本规则分别从两份 YAML 选取 `(previous, current]` 内的版本；历史 `-rc` 条目不参与正式版本区间。
3. 当前版本在两份 YAML 中必须各存在且仅存在一次。
4. 区间内每个版本不得重复，中英文版本集合必须相同。
5. 对区间内每个版本保留已有校验：`updates` 为非空字符串数组，中英文日期和条目数一致；日期必须是合法的 `YYYY.MM.DD`，用于后续排序。
6. 将选中版本的条目展开合并，按首个标签 `[feature]`、`[optimize]`、`[bugfix]` 分类，固定以此顺序输出；保留原有功能领域标签和条目原文。
7. 每个分类内按所属 YAML 版本的日期从早到晚排序；同一天按所属版本号从小到大排序，同一天同一版本保留原条目顺序。中英文对应条目的分类标签须一致，保证双语分组及顺序对应。
8. 全部校验成功后输出双语 Markdown，移除内部版本分节和可见的版本区间说明。

区间内未登记的版本号不要求连续，例如缺少 `3.6.1` 不报错。不要求下界版本在 YAML 中存在，因为下界内容不参与输出。合并只改变分组和排序，保留全部条目，不跨版本去重或改写原文。分类以条目开头的第一个标签为准；选中条目缺少上述三类标签或中英文分类不一致时，明确报错并定位条目，不静默丢弃。区间外历史条目不新增逐条校验。

诊断信息写入 stderr，包含下界、上界、选中版本及每种语言总条数；stdout 只输出最终 Markdown。元数据无法可靠解释或选中条目不合法时，说明具体文件/版本并非零退出。

### 3.3 Release 说明格式

GitHub Release 标题继续由现有 `--title "$GITHUB_REF_NAME"` 设置，只展示本次发布版本，例如 `3.6.6`。正文保留中英文两大段，每种语言下将全部选中版本合并为一份分类清单：

```markdown
## English

### Features
- ...

### Optimizations
- ...

### Bug fixes
- ...

## 中文

### 新功能
- ...

### 优化
- ...

### 问题修复
- ...
```

分类固定按 feature、optimize、bugfix 排列，空分类省略。各分类内部按日期从早到晚排序。例如同属 feature 的 `2026.09.18` 条目先于 `2026.10.15` 条目；先完成分类，再对各组独立排序。

日期取 RC YAML 的版本级 `date`，不查询每条更新对应的 commit 时间。所属版本和原条目下标只用于相同日期的稳定排序；它们不出现在条目文案中。正文不添加 `3.6.0` 等内部版本小标题、版本前缀或 `Release range` 行；这些定位信息只保留在 workflow 诊断日志中。

继续将同一个 `release-notes.md` 传给现有 `gh release create/edit --notes-file`，已有 Release 更新时整体替换说明。

## 4. 实际改动与文档落点

| 文件 | 实际修改 |
|---|---|
| `.github/workflows/release.yml` | 获取全部 Release 元数据，更新脚本调用 |
| `tools/generate_release_notes.rb` | 选择正式版本下界、按区间读取、逐版本双语校验、按类别合并并按日期排序 |
| `tools/test_generate_release_notes.py` | 使用标准库 unittest，通过 Ruby CLI 校验生成结果和失败行为 |
| `.github/workflows/pr-build.yml` | 在构建前增加上述定向脚本测试，不调用真实 GitHub API |
| `docs/ai_knowledge/98_code_map.md` | 补充发布工具入口及版本区间、重跑边界的简短说明 |
| `docs/ai_knowledge/99_index.md` | 增加发布说明任务路由，指向上述入口 |
| 本任务方案 | 已记录实际结果、验证证据和模拟命令 |

本轮不修改插件版本、RC YAML、HTML、插件内 changelog、Marketplace changelog 或历史 Release 内容。发布说明继续以 RC YAML 为数据源。Wiki 的安装步骤、支持范围和插件使用结果不变，无需同步。

## 5. 验证策略与实施顺序

behavior owner 是 `tools/generate_release_notes.rb` 对外的 CLI 输入、退出码和 Markdown 输出。版本选择与生成结果属于独立稳定契约，适合 L1 脚本行为测试；使用 Python 标准库创建临时仓库布局，将真实 Ruby 脚本与 YAML fixture 放入对应目录后执行，不为测试给生产脚本增加可注入路径或 mock 专用参数。

已先增加区间汇总失败用例，得到 `AssertionError: 16 != 2`，再修改脚本和接入 workflow。已执行：

```bash
python3 -m unittest tools.test_generate_release_notes
ruby -c tools/generate_release_notes.rb
git diff --check
```

| 验证项 | 预期结果 |
|---|---|
| 以 2026-10-25 现场的 3.6.x YAML 内容和 `3.5.5` 基线 fixture 生成 | 汇总六个版本的 16 条/语言，统一分类，不包含 `3.5.5` 内容或内部版本标题 |
| 当前版本 `3.5.5`，下界 `3.5.4` | 保留现有 14 条/语言内容 |
| 已存在当前 Release、同时存在更高 Release | 不选择自身或更高版本 |
| 草稿、预发布、只有 tag 的内部版本、非版本 tag | 不作为正式发布下界 |
| `v` 前缀、版本号 `3.6.9` 与 `3.6.10` | 前缀规范化，按数字比较 |
| 多页 JSON，合法基线位于后页 | 能选择后页候选，不截断历史 |
| 无下界、参数缺失、JSON 损坏或结构错误 | 非零退出，不输出成功说明 |
| 当前条目缺失、区间内重复版本、双语版本集合不同 | 非零退出，定位问题版本 |
| 任一选中版本 updates 非法、日期非法、双语日期/数量不一致 | 非零退出，定位问题条目或版本 |
| 条目缺少支持的分类标签、中英文对应分类不一致 | 非零退出，定位问题条目 |
| 区间外存在重复历史版本、缺少内部连续版本号、日期非单调 | 不影响合法区间结果 |
| 分类顺序 | 所有版本的条目合并，依次输出 feature、optimize、bugfix；空分类不输出 |
| 同一分类的日期与版本顺序相反 | 按日期从早到晚，不能用版本顺序替代时间顺序 |
| 同一天不同版本、同版本多条同类更新 | 同一天按数字版本升序，同版本内保持原条目顺序 |
| Release 标题与正文 | 标题沿用本次发布 tag，正文没有内部版本标题、版本前缀或区间说明 |
| 原文与双语对应 | 全部条目及领域标签保留，中英文分类和顺序一致 |

另进行一次只读接线验证：使用真实分页 API JSON 与仓库 YAML，通过新命令生成临时 Markdown，核对区间和条目数；通过 workflow YAML 解析及 shell 语法检查核对接线。该验证不执行 Release 创建、上传或 Marketplace 发布。`gh` 网络失败由步骤退出码阻断，使用失败响应的隔离命令验证，不触发真实写操作。

2026-10-27 完成实施验证：19 项 CLI 行为测试全部通过；Ruby 语法检查通过；两个 workflow YAML 可解析，其中 11 个 shell 块通过 `bash -n`。隔离模拟 API 返回退出码 23，生成步骤立即失败，没有写出说明或更新 Summary。使用真实 GitHub API 成功执行完整 Generate bilingual release notes 步骤，输出与本地模拟文件一致；逐条对照原始 YAML，3.6.6 保留 16 条/语言，3.5.5 保留 14 条/语言，没有遗漏或改写。

## 6. 生效边界与 review 要点

workflow 和脚本均从触发 tag 对应的提交检出。因此，必须先将实现提交纳入待发布 tag；仅修改 main 后重跑旧 tag，不会自动使用新逻辑。

已采用以下取舍：

- 下界采用低于当前版本的最高正式 Release，忽略发布时间和 Latest 标记。
- 说明合并全部选中条目，依次按 feature、optimize、bugfix 分组，组内按日期从早到晚排序；标题只保留当前 Release 版本。
- 无法取得可靠下界或双语数据不一致时，停止发布，不生成部分说明。

## 7. 依据

- `AGENTS.md`。
- `docs/ai_knowledge/00_overview.md`、`99_index.md`、`98_code_map.md`。
- `docs/ai_knowledge/06_testing.md`；文档落点检查参考 `01_architecture.md`、`04_engineering_compat.md`、`97_maintenance_manual.md` 的相关内容。
- `.github/workflows/release.yml`、`.github/workflows/pr-build.yml`、`tools/generate_release_notes.rb`。
- 中英文 `change_log/change_log_rc*.yaml`、`change_log/change_log*.html`，以及现有 `tools/test_check_pr_plan.py` 的测试组织方式。
- 本地 `gh api --help` 与对 `repos/tencentmusic/jugg/releases?per_page=100` 的只读分页查询。


## 8. 本地模拟结果与复现命令

已生成 `build/release-preview/3.6.6.md`，直接用 Markdown 预览打开即可查看最终双语正文：每种语言包含 8 条 feature、2 条 optimize、6 条 bugfix，各分类内部按日期从早到晚排序，正文没有内部版本小标题。实际 GitHub Release 标题仍为 `3.6.6`，由现有 `--title` 设置。

另有 `build/release-preview/3.5.5.md` 供单版本回归对照。模拟文件及 Release 元数据位于被 Git 忽略的 `build/` 下，未提交到仓库。

在仓库根目录执行以下命令可重新获取真实 Release 列表并生成模拟说明；整个过程只读取 GitHub 数据并写本地文件：

```bash
mkdir -p build/release-preview
gh api --paginate --slurp 'repos/tencentmusic/jugg/releases?per_page=100' \
  > build/release-preview/releases.json
ruby tools/generate_release_notes.rb 3.6.6 build/release-preview/releases.json \
  > build/release-preview/3.6.6.md
```

已下载过 `releases.json` 时，只执行最后一条 Ruby 命令即可离线重生成。预览其他版本时替换 Ruby 命令中的版本与输出文件名。

实际实现与已确认方案一致。验证仅执行了生成步骤，未执行插件构建、Release 创建/更新、上传或 Marketplace 发布；本次只改发布说明生成，无需 Gradle 编译或设备回归。
