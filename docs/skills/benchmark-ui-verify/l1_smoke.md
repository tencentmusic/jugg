# L1 Smoke 冒烟用例

> 验证三个核心工具（`ui_find` / `view_locate`、`figma_layout_verify`、`eval_view`）
> 各自能在最基础场景下正常返回结果。L1 通过是 L2–L4 执行的前置门禁。

---

### TC-S01：通过文本定位唯一按钮

**级别**：L1
**工具**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到文本为 "Unique MCP Target" 的按钮，告诉我它的位置和大小

**期望调用序列**：
1. `ui_find(target={text: "Unique MCP Target"})` 或等价的 view_locate 调用
2. 返回元素位置与大小（bounds）

**关键参数**（必须精确匹配）：
- `text` = `"Unique MCP Target"`

**宽松参数**（允许偏差）：
- 可以额外传 `resourceId`、`className`

**期望输出行为**：
- 工具返回成功，包含 bounds 信息（x, y, width, height）
- LLM 正确报告元素位置

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 调用序列完全正确 + 关键参数正确 + 报告了 bounds |
| 4 | 参数正确但报告了不必要的信息 |
| 3 | 调用了正确工具，但对结果解读有偏差 |
| 2 | 使用了 layout_dump 代替定位工具，但结论正确 |
| 1 | 关键参数错误或使用废弃工具 |
| 0 | 未调用任何工具 |

---

### TC-S02：通过 resourceId 定位元素

**级别**：L1
**工具**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到 resource id 为 "btn_mcp_resource_target" 的元素，确认它是否存在

**期望调用序列**：
1. `ui_find(target={resourceId: "btn_mcp_resource_target"})`
2. 返回元素存在确认和位置信息

**关键参数**（必须精确匹配）：
- `resourceId` = `"btn_mcp_resource_target"`

**宽松参数**（允许偏差）：
- `className` 可缺省
- 完整 id 路径（含包名）也可接受

**期望输出行为**：
- 工具返回成功，元素存在
- LLM 确认元素存在并给出位置

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 调用序列完全正确 + resourceId 正确 + 结论正确 |
| 4 | 参数正确但结论表述不够准确 |
| 3 | 工具调用正确但参数使用了 text 代替 resourceId |
| 2 | 使用错误工具但结论正确 |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-S03：figma_layout_verify 基本验证（正例）

**级别**：L1
**工具**：figma_layout_verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity
- `docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json` 存在

**输入（LLM 收到的指令）**：
> 使用 docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json 作为 Figma 设计稿，验证当前页面布局是否符合设计

**期望调用序列**：
1. `figma_layout_verify(figmaJsonPath="docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json", androidJsonPath=<layout_dump路径>)`
2. 返回验证结果

**关键参数**（必须精确匹配）：
- `figmaJsonPath` = `"docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json"`

**宽松参数**（允许偏差）：
- `dpr` 参数可缺省（使用默认值）
- `androidJsonPath` 可由工具自动获取或手动传入

**期望输出行为**：
- 工具返回 PASS 或少量可接受偏差
- LLM 正确报告验证结果

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 调用序列完全正确 + figmaJsonPath 正确 + 结论正确 |
| 4 | 路径正确但报告措辞不准确 |
| 3 | 工具调用正确但对 diff 结果解读有误 |
| 2 | 使用了 layout_dump 代替 figma_layout_verify |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-S04：eval_view 读取文本属性

**级别**：L1
**工具**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "tv_mcp_style_title" 的 TextView 的文本内容

**期望调用序列**：
1. `eval_view(target={resourceId: "tv_mcp_style_title"}, expressions=["getText().toString()"])`
2. 返回文本值 `"Style Title"`

**关键参数**（必须精确匹配）：
- `resourceId` = `"tv_mcp_style_title"`
- `expressions` 包含 `"getText().toString()"` 或等价表达式

**宽松参数**（允许偏差）：
- `className` 可缺省

**期望输出行为**：
- 工具返回 `"Style Title"`
- LLM 正确报告文本内容

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 调用序列完全正确 + 表达式正确 + 结论正确 |
| 4 | 表达式有细微差异但结果正确 |
| 3 | 工具调用正确但表达式不够精确 |
| 2 | 使用了 ui_find 代替 eval_view |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-S05：eval_view 读取颜色属性

**级别**：L1
**工具**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "tv_mcp_style_title" 的 TextView 的文字颜色值

**期望调用序列**：
1. `eval_view(target={resourceId: "tv_mcp_style_title"}, expressions=["getCurrentTextColor()"])`
2. 返回颜色整数值（对应 #FF0000）

**关键参数**（必须精确匹配）：
- `resourceId` = `"tv_mcp_style_title"`
- `expressions` 包含 `"getCurrentTextColor()"` 或等价表达式

**宽松参数**（允许偏差）：
- 返回值格式（整数 vs 十六进制字符串）不做限制

**期望输出行为**：
- 工具返回颜色值
- LLM 识别该颜色为红色（#FF0000）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 调用序列完全正确 + 表达式正确 + 正确解读颜色 |
| 4 | 表达式正确但对颜色值的解读描述不够精确 |
| 3 | 工具调用正确但表达式有偏差 |
| 2 | 使用了截图方式判断颜色 |
| 1 | 方向性错误 |
| 0 | 未调用工具 |
