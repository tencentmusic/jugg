# Jugg MCP Benchmark

本目录包含 Jugg MCP 工具集的 LLM 能力评测用例。

## 目录结构

```
benchmark/
├── README.md                         # 本文件：执行说明 + 评分说明
├── fixtures/                         # 预制 Figma JSON
│   ├── mcp_test_main.json            # 正确版本（人工手绘导出）
│   └── mcp_test_wrong_spacing.json   # 故意偏差版本（负例用）
├── l1_smoke.md                       # L1 冒烟用例（~5条）
├── l2_view_locate.md                 # L2 Unit: view_locate（~10条）
├── l2_figma_layout_verify.md         # L2 Unit: figma_layout_verify（~10条）
├── l2_view_inspect.md                # L2 Unit: view_inspect（~10条）
├── l3_integration.md                 # L3 集成用例（~15条）
└── l4_adversarial.md                 # L4 对抗用例（~10条）
```

## 前置条件

所有用例执行前需满足：
1. Android 设备已连接并可通过 `device_list` 确认
2. App 已部署并停留在 **McpTestActivity**（通过 `activity_stack` 确认）
3. `fixtures/mcp_test_main.json` 和 `fixtures/mcp_test_wrong_spacing.json` 已由人工手绘 Figma 并导出

## 评分说明

所有用例共用以下 5 分制评分模型：

| 分数 | 判定标准 |
|------|---------|
| 5 | 调用序列完全正确 + 关键参数正确 + 结论正确 |
| 4 | 调用序列正确，宽松参数有偏差（多余/缺失可选参数）+ 结论正确 |
| 3 | 调用了正确工具，但顺序/次数有偏差，结论基本正确 |
| 2 | 调用了非预期工具（如 `layout_dump` 代替 `view_locate`），但结论凑对 |
| 1 | 工具调用方向性错误（调用已废弃工具，或关键参数完全错误） |
| 0 | 未调用任何工具，或崩溃，或完全跑偏 |

## 各级别说明

| 级别 | 文件 | 用例数 | 覆盖点 |
|------|------|--------|--------|
| L1 Smoke | `l1_smoke.md` | ~5 | 三工具各通一次，基本返回正确 |
| L2 Unit | `l2_view_locate.md` | ~10 | 文本匹配、resourceId匹配、多候选歧义、不存在元素、深层嵌套 |
| L2 Unit | `l2_figma_layout_verify.md` | ~10 | 正常验证(PASS)、错误fixture检测(FAIL+diff)、dpr不匹配告警、部分节点无法匹配 |
| L2 Unit | `l2_view_inspect.md` | ~10 | 基础getter(text/bounds)、样式getter(textColor/textSizeSp/backgroundColor)、链式表达式、paddingLeft |
| L3 Integration | `l3_integration.md` | ~15 | 页面导航Gate + 验证 + 结果判定完整流程；多工具组合 |
| L4 Adversarial | `l4_adversarial.md` | ~10 | 边界输入、错误处理、LLM抗干扰、dpr误传 |

总计：~60 条

## 注意事项

- `layout_dump` 是内部工具，**Benchmark 用例中不得出现** LLM 直接调用 `layout_dump` 的期望序列
- 所有用例中的 `figmaJsonPath` 使用相对路径，基准为项目根目录（例：`docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json`）
- L3 集成用例中，`restart_app` 仅作 Gate（不计分），Gate 失败则跳过整条用例
- `view_inspect` 的 `expressions` 字段使用真实 Android SDK getter 方法名（如 `getText().toString()`、`getCurrentTextColor()`）
