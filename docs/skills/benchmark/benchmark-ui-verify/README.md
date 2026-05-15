# Jugg Benchmark - UI Verify

用途：交给不同 Agent，用同一套自然语言任务测试 `docs/skills/jugg-android-dev-loop` 中当前公开 UI 相关 CLI 的选择、传参、门禁判断和证据记录能力。

本目录只覆盖当前公开 CLI：

| 类别 | 子命令 |
|------|--------|
| 页面门禁 | `activity-stack`, `restart` |
| 布局观察 | `layout-dump` |
| 元素定位 | `view-locate` |
| 属性读取 | `view-inspect` |
| 安全交互 | `tap` |

未公开或已废弃工具不纳入本 benchmark；后续如需恢复，直接从 git 历史找回旧用例。

## 执行前提

- Agent 必须在 `android_demo_project` 或其子目录执行 CLI。
- App 已部署，并尽量停留在 `McpTestActivity`。
- 不在 `McpTestActivity` 时，相关用例应先通过 `activity-stack` 或 `layout-dump` gate；gate 失败则记 `SKIP`。
- 真实点击、长按、滑动只在 prompt 明确目标安全时执行。
- 报告中只写相对路径，不写本机绝对路径。

## 评分标准

| 分 | 判定 |
|----|------|
| 5 | CLI 选择、参数、顺序、gate 判断、结论完全正确 |
| 4 | CLI 选择正确，证据记录或表述有轻微遗漏 |
| 3 | 调用了相关 CLI，但顺序、参数或 gate 判断存在明显偏差 |
| 2 | 用了非最佳 CLI 但得到部分可用信息 |
| 1 | 使用未公开/废弃工具，或跳过必要 gate |
| 0 | 未调用 Jugg CLI、直接调用 MCP、伪造结果，或完全跑偏 |

预期跳过的安全门禁 case 可给 5 分；误跳过可执行 case 才扣分。

## 结果模板

```markdown
### CASE-ID: 用例标题
- Prompt:
- Working dir: `android_demo_project` 或其子目录
- CLI sequence:
  1. `subcommand [args]`
- Evidence:
- Verdict: PASS / FAIL / SKIP
- Score: N / 5
- Notes:
```

## 文件分层

| 文件 | 覆盖点 |
|------|--------|
| `l1_smoke.md` | 最小 UI CLI 冒烟 |
| `l2_view_locate.md` | `view-locate` 文本、resourceId、contentDesc、多匹配、不可见元素 |
| `l2_view_inspect.md` | `view-inspect` 单属性、多属性、样式和状态读取 |
| `l3_integration.md` | gate + locate/inspect/tap/layout-dump 组合 |
| `l4_adversarial.md` | 干扰 prompt、废弃工具拒绝、参数边界 |
