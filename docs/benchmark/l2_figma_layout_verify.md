# L2 Unit：figma_layout_verify

> 覆盖布局验证工具的各类场景：正常 PASS、错误 fixture 检测（FAIL+diff）、
> 节点无法匹配、dpr 参数影响等。

---

### TC-FLV01：正确 fixture 全量验证（PASS）

**级别**：L2
**工具**：figma_layout_verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity
- `docs/benchmark/fixtures/mcp_test_main.json` 已就绪

**输入（LLM 收到的指令）**：
> 使用 docs/benchmark/fixtures/mcp_test_main.json 验证当前页面布局是否符合 Figma 设计，给出验证报告

**期望调用序列**：
1. `figma_layout_verify(figmaJsonPath="docs/benchmark/fixtures/mcp_test_main.json", androidJsonPath=<layout_dump_path>)`
2. 返回 PASS 或偏差在容忍范围内的报告

**关键参数**：
- `figmaJsonPath` = `"docs/benchmark/fixtures/mcp_test_main.json"`

**期望输出行为**：
- 工具返回验证通过
- LLM 报告布局符合设计

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | figmaJsonPath 正确 + 报告 PASS + 结论正确 |
| 4 | 路径正确但报告措辞不够清晰 |
| 3 | 工具调用正确但对结果有误解 |
| 2 | 使用了 layout_dump 直接读取而非 figma_layout_verify |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-FLV02：错误 fixture 检测（FAIL + diff 报告）

**级别**：L2
**工具**：figma_layout_verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity
- `docs/benchmark/fixtures/mcp_test_wrong_spacing.json` 已就绪（含故意偏差的 spacing）

**输入（LLM 收到的指令）**：
> 使用 docs/benchmark/fixtures/mcp_test_wrong_spacing.json 验证当前页面布局，报告哪些节点不符合设计规范

**期望调用序列**：
1. `figma_layout_verify(figmaJsonPath="docs/benchmark/fixtures/mcp_test_wrong_spacing.json", androidJsonPath=<layout_dump_path>)`
2. 工具返回包含 spacing diff 的 FAIL 报告

**关键参数**：
- `figmaJsonPath` = `"docs/benchmark/fixtures/mcp_test_wrong_spacing.json"`

**期望输出行为**：
- LLM 正确识别并报告哪些节点存在 spacing/margin 偏差
- 不应报告 PASS

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | figmaJsonPath 正确 + 正确识别 FAIL + 报告具体偏差节点 |
| 4 | 识别到 FAIL 但未详细报告哪些节点 |
| 3 | 工具调用正确但结论说 PASS（未正确解读 diff） |
| 2 | 使用了错误工具但结论部分正确 |
| 1 | 使用了正确 fixture 路径（非 wrong_spacing）|
| 0 | 未调用工具 |

---

### TC-FLV03：验证单个节点的 spacing（tv_card_title margin）

**级别**：L2
**工具**：figma_layout_verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity
- `docs/benchmark/fixtures/mcp_test_main.json` 存在

**输入（LLM 收到的指令）**：
> 验证 Card 容器内 tv_card_title 节点的 spacing 是否符合 Figma 设计稿，特别关注其 marginBottom

**期望调用序列**：
1. `figma_layout_verify(figmaJsonPath="docs/benchmark/fixtures/mcp_test_main.json", androidJsonPath=<layout_dump_path>)`
2. 从返回结果中查找 `tv_card_title` 的 marginBottom 验证情况

**关键参数**：
- `figmaJsonPath` = `"docs/benchmark/fixtures/mcp_test_main.json"`

**期望输出行为**：
- LLM 找到 `tv_card_title` 节点的验证结果
- 报告 marginBottom=8dp 是否符合

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 调用正确 + 定位到 tv_card_title 的验证结果 + 结论正确 |
| 4 | 调用正确但定位节点时有误差 |
| 3 | 调用了工具但未针对指定节点给出结论 |
| 2 | 使用了 eval_view 代替 figma_layout_verify |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-FLV04：验证 Card 容器的 margin（card_mcp_container）

**级别**：L2
**工具**：figma_layout_verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 验证 id 为 card_mcp_container 的 CardView 四边 margin 是否为 16dp

**期望调用序列**：
1. `figma_layout_verify(figmaJsonPath="docs/benchmark/fixtures/mcp_test_main.json", ...)`
2. 查找 `card_mcp_container` 的 margin 验证结果

**关键参数**：
- `figmaJsonPath` = `"docs/benchmark/fixtures/mcp_test_main.json"`

**期望输出行为**：
- LLM 从 figma_layout_verify 结果中找到 `card_mcp_container`
- 确认四边 margin = 16dp

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 调用正确 + 找到节点 + margin 验证结论正确 |
| 4 | 找到节点但 margin 数值报告有偏差 |
| 3 | 工具调用正确但未针对 margin 给出结论 |
| 2 | 使用 eval_view 读取 padding 代替 figma 验证 |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-FLV05：节点名称不匹配时的行为

**级别**：L2
**工具**：figma_layout_verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 使用 docs/benchmark/fixtures/mcp_test_main.json 验证布局，如果有节点无法匹配，请报告哪些节点未能完成验证

**期望调用序列**：
1. `figma_layout_verify(figmaJsonPath="docs/benchmark/fixtures/mcp_test_main.json", ...)`
2. 从返回结果中提取未匹配节点列表

**关键参数**：
- `figmaJsonPath` = `"docs/benchmark/fixtures/mcp_test_main.json"`

**期望输出行为**：
- LLM 能识别并报告工具返回中标记为"未匹配"的节点（如有）
- 不应静默跳过未匹配节点

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 调用正确 + 正确报告匹配/未匹配状态 |
| 4 | 调用正确但对未匹配信息表述不清 |
| 3 | 调用工具但忽略了未匹配节点 |
| 2 | 使用了错误工具 |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-FLV06：对齐关系验证（ll_mcp_parent 内图标与文字）

**级别**：L2
**工具**：figma_layout_verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 验证 ll_mcp_parent 容器中 iv_mcp_icon 和 tv_mcp_label 的左右对齐关系是否符合设计

**期望调用序列**：
1. `figma_layout_verify(figmaJsonPath="docs/benchmark/fixtures/mcp_test_main.json", ...)`
2. 查找 `iv_mcp_icon` 与 `tv_mcp_label` 之间的 spacing 验证

**关键参数**：
- `figmaJsonPath` = `"docs/benchmark/fixtures/mcp_test_main.json"`

**期望输出行为**：
- LLM 找到两个节点的对齐验证结果
- 报告 icon(32dp) + paddingLeft(8dp) 对应的间距关系

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确找到两节点的对齐/间距验证结果 |
| 4 | 找到了验证结果但描述不够精确 |
| 3 | 只验证了其中一个节点 |
| 2 | 使用 eval_view 读取 padding 代替 figma 验证 |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-FLV07：验证通过后确认无额外偏差节点

**级别**：L2
**工具**：figma_layout_verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 使用正确的 Figma fixture 验证布局，验证通过后明确告诉我哪些节点都符合设计，没有偏差

**期望调用序列**：
1. `figma_layout_verify(figmaJsonPath="docs/benchmark/fixtures/mcp_test_main.json", ...)`
2. LLM 枚举验证通过的节点列表

**关键参数**：
- `figmaJsonPath` = `"docs/benchmark/fixtures/mcp_test_main.json"`

**期望输出行为**：
- LLM 列出通过验证的节点（不编造未验证的节点）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确调用 + 枚举了实际通过的节点 |
| 4 | 调用正确但节点列表不完整 |
| 3 | 调用正确但节点列表有编造 |
| 2 | 使用了错误工具 |
| 1 | 节点列表完全错误 |
| 0 | 未调用工具 |

---

### TC-FLV08：连续验证两次（正确 + 错误 fixture）

**级别**：L2
**工具**：figma_layout_verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 先用正确的 Figma fixture 验证，再用错误的 fixture 验证，对比两次结果的差异

**期望调用序列**：
1. `figma_layout_verify(figmaJsonPath="docs/benchmark/fixtures/mcp_test_main.json", ...)`
2. `figma_layout_verify(figmaJsonPath="docs/benchmark/fixtures/mcp_test_wrong_spacing.json", ...)`
3. 对比两次结果

**关键参数**：
- 两次调用使用不同的 `figmaJsonPath`

**期望输出行为**：
- LLM 清晰对比两次结果：第一次 PASS，第二次有 diff
- 指出差异所在节点

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 两次调用路径均正确 + 正确对比差异 |
| 4 | 两次调用正确但对比描述不清晰 |
| 3 | 只调用了一次 |
| 2 | 调用了两次但使用了相同路径 |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-FLV09：Style Inspect 节点的样式验证

**级别**：L2
**工具**：figma_layout_verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 验证 tv_mcp_style_title 节点的位置和尺寸是否符合 Figma 设计

**期望调用序列**：
1. `figma_layout_verify(figmaJsonPath="docs/benchmark/fixtures/mcp_test_main.json", ...)`
2. 从结果中提取 `tv_mcp_style_title` 的验证信息

**期望输出行为**：
- LLM 找到 `tv_mcp_style_title` 的验证结果（位置 x=16, y=615, w=87, h=27）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确调用 + 找到节点 + 报告位置正确 |
| 4 | 找到节点但位置数值有轻微误差 |
| 3 | 工具调用正确但未找到该节点 |
| 2 | 使用了 eval_view 代替 figma_layout_verify |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-FLV10：view_mcp_bg_block 位置与尺寸验证

**级别**：L2
**工具**：figma_layout_verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 验证蓝色色块（view_mcp_bg_block）的尺寸是否为 120×48dp

**期望调用序列**：
1. `figma_layout_verify(figmaJsonPath="docs/benchmark/fixtures/mcp_test_main.json", ...)`
2. 提取 `view_mcp_bg_block` 的宽高验证结果

**期望输出行为**：
- LLM 确认 `view_mcp_bg_block` 宽 = 120dp，高 = 48dp

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确调用 + 宽高数值正确 |
| 4 | 调用正确但数值有轻微偏差 |
| 3 | 工具调用正确但未专门报告宽高 |
| 2 | 使用 eval_view 的 getWidth/getHeight 代替 |
| 1 | 方向性错误 |
| 0 | 未调用工具 |
