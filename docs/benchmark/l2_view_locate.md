# L2 Unit：view_locate / ui_find

> 覆盖元素定位的各类场景：精确文本匹配、resourceId 匹配、contentDesc 匹配、
> 多候选歧义处理、不存在元素、深层嵌套元素定位。

---

### TC-VL01：精确文本匹配（唯一元素）

**级别**：L2
**工具**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到文本为 "Unique MCP Target" 的按钮

**期望调用序列**：
1. `ui_find(target={text: "Unique MCP Target"})`
2. 返回唯一匹配元素，bounds=[16, 368, 395, 416]

**关键参数**（必须精确匹配）：
- `text` = `"Unique MCP Target"`

**宽松参数**（允许偏差）：
- `className`、`resourceId` 可附加

**期望输出行为**：
- 返回一个元素，bounds 正确
- LLM 报告元素位置

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 工具调用正确 + 文本参数正确 + 报告位置 |
| 4 | 参数正确但额外做了不必要的调用 |
| 3 | 调用了正确工具但文本有轻微偏差 |
| 2 | 使用了 layout_dump 但结论正确 |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-VL02：resourceId 精确匹配

**级别**：L2
**工具**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 通过 resource id "btn_mcp_resource_target" 找到对应元素并报告其 contentDescription

**期望调用序列**：
1. `ui_find(target={resourceId: "btn_mcp_resource_target"})`
2. 返回元素，contentDesc = "mcp-resource-target"

**关键参数**（必须精确匹配）：
- `resourceId` = `"btn_mcp_resource_target"`

**宽松参数**：
- 完整包名前缀可省略

**期望输出行为**：
- 返回元素，LLM 报告 contentDesc 为 "mcp-resource-target"

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确调用 + resourceId 正确 + 正确报告 contentDesc |
| 4 | 调用正确但未报告 contentDesc，只报告位置 |
| 3 | 使用 text 匹配代替 resourceId 匹配，但结论正确 |
| 2 | 使用错误工具但结论正确 |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-VL03：contentDescription 匹配

**级别**：L2
**工具**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到 contentDescription 为 "mcp-resource-target" 的元素

**期望调用序列**：
1. `ui_find(target={contentDesc: "mcp-resource-target"})`
2. 返回匹配元素

**关键参数**：
- `contentDesc` = `"mcp-resource-target"`

**宽松参数**：
- `className` 可缺省

**期望输出行为**：
- 返回元素，LLM 确认找到该元素

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确使用 contentDesc 参数 + 结论正确 |
| 4 | 使用了 resourceId 代替 contentDesc 但结论正确 |
| 3 | 调用正确工具但参数字段名有偏差 |
| 2 | 使用错误工具但结论正确 |
| 1 | 方向性错误 |
| 0 | 未调用工具 |

---

### TC-VL04：多候选歧义处理（同文本两元素）

**级别**：L2
**工具**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity（页面上有两个文本均为 "Repeat Tap Target" 的按钮）

**输入（LLM 收到的指令）**：
> 找到文本为 "Repeat Tap Target" 的按钮，共有几个？分别在什么位置？

**期望调用序列**：
1. `ui_find(target={text: "Repeat Tap Target"})` 或等价调用
2. 返回两个匹配元素

**关键参数**：
- `text` = `"Repeat Tap Target"`

**期望输出行为**：
- LLM 报告找到 **2** 个元素
- 分别报告两个元素的位置（bounds 不同）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 工具调用正确 + 报告了 2 个元素 + 位置均正确 |
| 4 | 报告了 2 个元素但位置有轻微偏差 |
| 3 | 只报告了 1 个元素（漏报） |
| 2 | 使用错误工具但结论部分正确 |
| 1 | 认为元素不存在或只返回一个并声称唯一 |
| 0 | 未调用工具 |

---

### TC-VL05：不存在元素的处理

**级别**：L2
**工具**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到文本为 "NonExistentElementXYZ" 的元素

**期望调用序列**：
1. `ui_find(target={text: "NonExistentElementXYZ"})`
2. 工具返回空结果或找不到元素

**关键参数**：
- `text` = `"NonExistentElementXYZ"`

**期望输出行为**：
- LLM 正确报告元素不存在，**不应伪造结果**

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 工具调用正确 + 正确报告不存在 |
| 4 | 调用正确但报告措辞不明确 |
| 3 | 调用了工具但未明确报告不存在 |
| 2 | 没调用工具但猜测了元素不存在 |
| 1 | 声称元素存在（幻觉） |
| 0 | 未调用工具且给出错误结论 |

---

### TC-VL06：visibility=GONE 元素不可见验证

**级别**：L2
**工具**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity（`btn_mcp_visibility_hidden` 为 invisible）

**输入（LLM 收到的指令）**：
> 页面上有两个文本为 "Visibility Tap Target" 的按钮。请告诉我哪个是可见的，哪个是不可见的？

**期望调用序列**：
1. `ui_find(target={text: "Visibility Tap Target"})` 获取所有匹配元素
2. 分析 visibility 属性

**关键参数**：
- `text` = `"Visibility Tap Target"`

**期望输出行为**：
- LLM 报告一个可见（`btn_mcp_visibility_visible`），一个不可见（`btn_mcp_visibility_hidden`）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确区分可见/不可见两个元素 |
| 4 | 正确区分但混淆了哪个 id 对应哪种状态 |
| 3 | 只找到一个元素，未报告另一个 |
| 2 | 找到了两个但未区分可见性 |
| 1 | 声称只有一个按钮 |
| 0 | 未调用工具 |

---

### TC-VL07：嵌套结构内元素定位

**级别**：L2
**工具**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 在 id 为 "ll_mcp_parent" 的 LinearLayout 内，找到文本为 "Nested Label" 的 TextView

**期望调用序列**：
1. `ui_find(target={text: "Nested Label"})` 或带 resourceId 的调用
2. 返回嵌套在 `ll_mcp_parent` 内的 `tv_mcp_label` 元素

**关键参数**：
- `text` = `"Nested Label"` 或 `resourceId` = `"tv_mcp_label"`

**期望输出行为**：
- LLM 找到 `tv_mcp_label`，确认其父容器为 `ll_mcp_parent`

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确找到元素 + 确认层级关系 |
| 4 | 找到元素但未确认父容器 |
| 3 | 找到了正确元素但路径描述有偏差 |
| 2 | 先找父容器再找子元素（额外调用），但结论正确 |
| 1 | 找到了错误元素 |
| 0 | 未调用工具 |

---

### TC-VL08：CardView 内部元素定位

**级别**：L2
**工具**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到 Card 容器内文本为 "Card Description" 的 TextView

**期望调用序列**：
1. `ui_find(target={text: "Card Description"})`
2. 返回 `tv_card_desc` 元素

**关键参数**：
- `text` = `"Card Description"`

**期望输出行为**：
- LLM 找到 `tv_card_desc`，报告其在 `card_mcp_container` 内

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确找到 + 报告父容器关系 |
| 4 | 找到元素但未报告容器关系 |
| 3 | 找到元素但容器描述有误 |
| 2 | 使用错误工具但结论正确 |
| 1 | 找到错误元素 |
| 0 | 未调用工具 |

---

### TC-VL09：ImageView 通过 contentDesc 定位

**级别**：L2
**工具**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到 contentDescription 为 "mcp icon" 的图标元素，报告其大小

**期望调用序列**：
1. `ui_find(target={contentDesc: "mcp icon"})`
2. 返回 `iv_mcp_icon`，bounds 宽高均为 32dp

**关键参数**：
- `contentDesc` = `"mcp icon"`

**期望输出行为**：
- LLM 报告 32×32dp 的图标

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确通过 contentDesc 找到 + 尺寸正确 |
| 4 | 找到了但尺寸报告有轻微偏差 |
| 3 | 通过文本或 id 找到，未使用 contentDesc |
| 2 | 使用错误工具但结论正确 |
| 1 | 找不到元素 |
| 0 | 未调用工具 |

---

### TC-VL10：ScrollView 内不在屏幕可见区域的元素

**级别**：L2
**工具**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity（页面停留在顶部，Swipe 区域在 ScrollView 底部）

**输入（LLM 收到的指令）**：
> 找到文本为 "Swipe End Marker" 的元素，它是否在当前可见区域内？

**期望调用序列**：
1. `ui_find(target={text: "Swipe End Marker"})`
2. 返回元素信息

**关键参数**：
- `text` = `"Swipe End Marker"`

**期望输出行为**：
- LLM 找到该元素
- 能正确判断其是否在当前屏幕可见范围内（根据 bounds 判断）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 找到元素 + 正确判断可见性 |
| 4 | 找到元素但未判断可见性 |
| 3 | 找到元素但可见性判断有误 |
| 2 | 使用错误工具但结论基本正确 |
| 1 | 找不到元素 |
| 0 | 未调用工具 |
