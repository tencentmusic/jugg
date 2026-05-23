# MCP UI 验证执行情况检查清单

> 最后核对：2026-05-23
> 依据：`08_mcp_layout_verify_design.md`、`08_mcp_tools_list.md` 与当前 `McpToolActionRegistry.defaultActions()`。
> 用于验收 Agent 是否按当前公开 MCP 工具完成 UI 证据采集。每条问题要求给出事实回答；未遵守时说明原因。

---

## A. 页面与工具边界

1. 你是否先用 `activity-stack` 或等价证据确认当前页面就是目标页面？如果没有，为什么？
2. 你使用的 UI 工具是否都在 `tools/list` / `08_mcp_tools_list.md` 的公开工具清单内？
3. 你是否避免调用未注册的 `layout-verify`、`figma-layout-verify`、`screenshot` 作为默认验证工具？如果用了，请说明运行时 `tools/list` 证据。

## B. Expected Value 来源

4. 每个 expected value 来自哪里：设计稿、代码公式、产品文案，还是用户明确给出的数值？
5. 对尺寸、间距、对齐类断言，你是否写出至少两个公式，例如 `right.left - left.right` 或 `(left + right) / 2`？
6. 如果 Figma 数值是 px，你是否说明 `dpr` 并换算为 dp？

## C. Actual Value 证据

7. 元素位置、大小、间距、对齐是否来自 `view-locate` 的 `bounds` / `size`，而不是目测？
8. View 内部属性（颜色、字号、maxLines、ellipsize、enabled、clickable 等）是否来自 `view-inspect` 的 getter 输出？
9. 全局结构或 selector 失败时，是否用 `layout-dump` HTML 查找候选节点，而不是直接标记跳过？
10. 需要运行时闭环时，是否用 `wait-logs` 的 `marker` / `crash` / `timeout` 结果支撑结论？

## D. Selector 与多命中

11. selector 是否优先使用稳定 `resourceId`，再考虑 `text` / `contentDesc`？
12. `view-locate.data.matchCount > 1` 时，你是否消歧后再做断言或点击？
13. selector 查不到元素时，你是否用 `layout-dump` 检查真实 text/id/contentDesc，而不是只重试同一个 selector？
14. 对隐藏或 GONE 节点，你是否区分“属性仍可读”和“不能作为安全点击目标”？

## E. 数值与单位

15. 所有 bounds、size、spacing 结论是否以 dp 表达？
16. `view-inspect` 返回 px 或原始 getter 值时，你是否用 `density` 做 px -> dp 换算？
17. 近似判定是否显式写出容差口径（推荐 `<= 2dp` 或 `<= 5%`），而不是使用不存在的 `tolerance` 参数？
18. 颜色值是否说明格式来源；若转成 hex，是否保留 alpha（`#AARRGGBB`）？

## F. 交互验证

19. 点击前是否确认 `tap` 的模式：coordinate、percent 或 element？
20. 元素模式点击前是否确认没有多匹配？多匹配时是否改用更强 selector 或坐标？
21. 点击后是否重新采集页面证据，而不是复用点击前的 `layout-dump` / `view-locate` 输出？

## G. Verification Report

22. 报告是否逐条列出 `Expected`、`Actual`、`Diff`、`Evidence tool`、`Verdict`？
23. 是否没有 silently omit 失败或无法验证的断言？
24. FAIL 项是否包含具体修复方向，并指明优先查看的文件、布局或 View getter？
25. 修复后是否完整 re-verify 受影响页面，而不是只检查上一次 FAIL 的单个数值？
