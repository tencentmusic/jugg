# Jugg Benchmark - UI 验证工具

- 本 benchmark 包含 jugg-android-dev-loop skill 提及的 cli 工具集（UI 验证工具部分）的 LLM 能力评测用例。
- 本 benchmark 不测试 MCP，仅测试 skill。
- l2_figma-layout-verify.md 未完成，不执行。

## 目录结构

```
benchmark/
├── README.md                         # 本文件：执行说明 + 评分说明
├── fixtures/                         # 预制 Figma JSON
│   ├── mcp_test_main.json            # 正确版本（人工手绘导出）
│   └── mcp_test_wrong_spacing.json   # 故意偏差版本（负例用）
├── l1_smoke.md                       # L1 冒烟用例（~5条）
├── l2_view_locate.md                 # L2 Unit: view_locate（~10条）
├── l2_view_inspect.md                # L2 Unit: view_inspect（~10条）
├── l3_integration.md                 # L3 集成用例（~15条）
└── l4_adversarial.md                 # L4 对抗用例（~10条）
```

## 前置条件

所有用例执行前需满足：
1. Android 设备已连接并可通过 `devices` 确认
2. App 已部署并停留在 **McpTestActivity**（通过 `activity-stack` 确认）
3. `fixtures/mcp_test_main.json` 和 `fixtures/mcp_test_wrong_spacing.json` 已由人工手绘 Figma 并导出

## 评分说明

所有用例共用以下 5 分制评分模型：

| 分数 | 判定标准 |
|------|---------|
| 5 | 调用序列完全正确 + 关键参数正确 + 结论正确 |
| 4 | 调用序列正确，宽松参数有偏差（多余/缺失可选参数）+ 结论正确 |
| 3 | 调用了正确命令，但顺序/次数有偏差，结论基本正确 |
| 2 | 调用了非预期命令（如 `layout-dump` 代替 `view-locate`），但结论凑对 |
| 1 | 命令调用方向性错误（调用已废弃命令，或关键参数完全错误） |
| 0 | 未调用任何命令，或崩溃，或完全跑偏 |

## 各级别说明

| 级别 | 文件 | 用例数 | 覆盖点 |
|------|------|--------|--------|
| L1 Smoke | `l1_smoke.md` | ~5 | 三命令各通一次，基本返回正确 |
| L2 Unit | `l2_view_locate.md` | ~10 | 文本匹配、resourceId匹配、多候选歧义、不存在元素、深层嵌套 |
| L2 Unit | `l2_view_inspect.md` | ~10 | 基础getter(text/bounds)、样式getter(textColor/textSizeSp/backgroundColor)、链式表达式、paddingLeft |
| L3 Integration | `l3_integration.md` | ~15 | 页面导航Gate + 验证 + 结果判定完整流程；多命令组合 |
| L4 Adversarial | `l4_adversarial.md` | ~10 | 边界输入、错误处理、LLM抗干扰、dpr误传 |

总计：~60 条

## 注意事项

- `layout-dump` 是内部命令，**Benchmark 用例中不得出现** LLM 直接调用 `layout-dump` 的期望序列
- 所有用例中的 `figmaJsonPath` 使用相对路径，基准为项目根目录（例：`docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json`）
- L3 集成用例中，`restart` 仅作 Gate（不计分），Gate 失败则跳过整条用例，不评分
- `view-inspect` 的 `expressions` 字段使用真实 Android SDK getter 方法名（如 `getText().toString()`、`getCurrentTextColor()`）

## ⚠️ 评测边界（AI 必读）

**本 benchmark 仅评测 skill 的 LLM 调用行为，不验证底层实现。**

### Agent 执行角色

执行 benchmark 时，agent 扮演**观察者 + 记录者**角色，不是问题解决者：

- 命令成功 → 记录返回值，按评分标准打分，继续下一条
- 命令失败 / 报错 / 超时 → **直接记录错误输出和 exit code，打分，继续下一条**
- **禁止对失败命令做任何补救**：不重试不同参数、不分析失败原因、不调整调用方式

### 禁止行为

1. **禁止查找 `jugg` 可执行文件路径**：`jugg` 路径由 skill 的 `SKILL.md` 明确提供，不得执行 `which jugg`、`find` 等查找命令。
2. **禁止读取或调试 CLI 内部实现**：不得读取 `bin/cmd/*.sh` 或任何 CLI 内部脚本，不得排查 MCP server，不得修改任何实现代码。
3. **禁止直接调用 MCP**：用例要求通过 `jugg` CLI 执行，不得绕过 CLI 直接调用 MCP 工具或 HTTP 接口。

违反以上任一条，本次用例视为无效（不计分）。
