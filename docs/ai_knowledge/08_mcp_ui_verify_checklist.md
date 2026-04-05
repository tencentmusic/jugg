# MCP UI 验证执行情况检查清单

> 基于 `docs/skills/jugg-android-dev-loop/references/guide_layout_verify_assertion.md` 全部章节。
> 用于验收 Agent 是否按规范执行了 UI 检查流程。每条问题要求 Agent 给出**事实回答**，如未遵守需说明原因。
> 最后更新：2026-03-10

---

## A. Core Principle & Sub-Agent 隔离

1. 你是否按 Sub-Agent Delegation Policy（§Core Principle）将 **UI 检查** 和 **UI 修复** 分配给了两个独立的 sub-agent？如果没有，为什么？
2. 检查 sub-agent（A）和修复 sub-agent（B）之间，是否存在 context 共享？Main agent 是通过结构化摘要传递信息的吗？
3. 修复完成后，你是否 spawn 了一个 **全新的** 检查 sub-agent 实例做 re-verify，而不是复用上一轮的 A？

## B. 三层原则 & 期望值来源

4. 你的 expected value 来自哪里——代码推导、设计稿数值、还是你自己目测猜的？请列出至少 2 个 expected value 的推导公式或设计稿出处。
5. 你是否执行了 Step 1.5（Code-Derive Expected Values），为尺寸/间距关键元素写出了显式公式？如果没有，为什么？

## C. 工具使用优先级

6. 你优先使用了 `layout_verify` 和 `view_inspect` 吗？如果某些检查跳过了这两个工具，请逐条说明原因。
7. 如果你使用了 `screenshot` 作为判定依据（而非仅作为辅助截图），是因为 `layout_verify` / `view_inspect` 无法覆盖该属性吗？请说明具体是哪个属性。
8. 你是否存在"截图看起来对了就 PASS"的情况？有没有每个判定都有 `layout_verify` / `view_inspect` 的数据层数值支撑？

## D. Selector Fallback Chain（§1.3.1）

9. 遇到 selector 匹配失败时，你是否按 5 级 fallback chain 逐级尝试了（`resourceId` → `+className` → `text+className` → `contentDesc+className` → `layout_dump` 手动提取）？
10. 有没有因为第一次 selector 失败就直接标记 INCONCLUSIVE 或跳过检查的情况？

## E. 完整性 & SOP 步骤

11. 你是否按 §4 的 Step 0 ~ Step 7 完整走完了所有步骤？如果跳过了某个 Step，是哪个，为什么？
12. Step 0（Screenshot 可疑区域表）你生成了吗？是表格形式还是自由文本？
13. Step 6（Cross-Check Against §2 Component Checklist）你做了吗？对页面上存在的组件类型（Grid / Image / Banner / Spacing / Corner radius / Scrollable），是否逐条过了 §2 的 mandatory check？

## F. 断言生成 & 分组

14. 你的 `layout_verify` 调用是否按 target 分组了（同一元素的所有 checks 放在一个 `checks[]` 里）？
15. 对于 §1.4 列出的 `layout_verify` 不支持的属性（maxLines / ellipsize / cornerRadius 等），你用 `view_inspect` 补充了吗？还是直接跳过了？

## G. 数值规范

16. 你的 `textColor` 值是否使用了 `#AARRGGBB` 格式（含 alpha）？有没有漏掉 alpha 写成 `#RRGGBB` 的？
17. 近似匹配你用了 `gte` + `lte` 一对检查吗？有没有使用不存在的 `tolerance` 字段？
18. 所有数值是 dp 单位吗？如果来源是 px，你做了 `dp = px / density` 转换吗？

## H. Verification Report（§7）

19. 你在改代码 **之前** 是否先输出了完整的 Verification Report？
20. Report 里是否每一条断言都出现了——有没有 silently omitted 的？
21. Report 的 "Actual" 列是来自 `layout_verify` / `view_inspect` 的工具输出，还是你自己的目测估算？
22. FAIL 项是否包含了具体的修复建议（含文件/位置提示）？

## I. Re-Verify 完整性

23. 改完代码后，你是否重新执行了 **完整的** Step 0 → Step 7 验证，而不是只 re-check 了之前 FAIL 的那几项？
24. 每次 re-verify 是否生成了新的 Report，并保留了上一轮 Report 用于 diff？
