# layout_verify 能力评估计划

> 日期：2026-03-07
> 目标：验证 `layout_verify` 是否能覆盖 95% 的 UI 验证场景，使 agent 无需手动解析 `layout_dump` JSON 或读取 `screenshot` 来完成校验。

---

## 一、评估方法论

### 1.1 核心指标

**自治率** = 仅通过 `layout_verify` 完成校验的用例数 / 总用例数

目标：自治率 ≥ 95%

### 1.2 判定标准

每条用例交给 agent 执行时，观察 agent 的行为模式：

| 行为 | 判定 |
|------|------|
| agent 仅调用 `layout_verify` 即完成判断，给出 PASS/FAIL 结论 | **自治** |
| agent 调用 `layout_verify` 后又读取 `layout_dump` 的 `data.content` 来二次分析 | **非自治**（verify 结果不够充分） |
| agent 调用 `screenshot` 来做视觉判断（非最终证据用途） | **非自治**（verify 无法覆盖该场景） |
| agent 调用 `layout_dump` 获取 `data.file` 再传给 `layout_verify(dumpFile=...)` | **自治**（这是标准工作流） |

### 1.3 执行方式

1. Agent 先执行 `restart_app` 导航到 MCP Test Page
2. 执行一次 `layout_dump` 获取 `data.file` 路径
3. 依次执行下列用例，每条用例给出自然语言指令
4. 观察 agent 是否仅通过 `layout_verify` 完成校验
5. 记录每条用例的结果：PASS/FAIL + 是否自治

### 1.4 前置条件

- 测试工程：`android_demo_project`
- 测试页面：`McpTestActivity`（从 MainActivity 点击 "MCP Test Page" 进入）
- 设备：已连接 AVD
- App 已部署且 ViewHierarchy Server 正常

---

## 二、用例分类与覆盖率目标

| 分类 | 用例数 | 覆盖的 UI 验证场景 | 预期自治率 |
|------|--------|-------------------|-----------|
| A. 元素存在性 | 4 | 元素是否存在、不存在、visibility 状态 | 100% |
| B. 文本属性 | 5 | 文本内容精确匹配、包含、正则 | 100% |
| C. 尺寸校验 | 4 | 宽高 dp/px | 100% |
| D. 位置校验 | 3 | bounds 坐标 | 100% |
| E. 间距校验 | 4 | 两元素垂直/水平间距 | 100% |
| F. 对齐校验 | 3 | 水平/垂直中心对齐 | 100% |
| G. 包含与顺序 | 3 | containment、order | 100% |
| H. 重叠检测 | 2 | overlap | 100% |
| I. 颜色校验 | 2 | textColor | 100% |
| J. 交互状态 | 3 | clickable、enabled | 100% |
| K. 透明度 | 2 | alpha | 100% |
| L. 内边距 | 2 | padding | 100% |
| M. Live query 专属 | 2 | textSizeSp（dump 不含的属性）| 100% |
| N. 否定断言 | 3 | 预期 FAIL 的校验 | 100% |
| O. 元素定位容错 | 3 | 多匹配、无匹配、className 过滤 | 100% |
| P. 复合验收场景 | 5 | 模拟真实 UI 验收多步骤 | ≥80% |
| Q. 颜色扩展断言 | 3 | ARGB 精确匹配、alpha 通道、否定颜色 | 100% |
| R. 状态切换属性对比 | 2 | 不同视觉状态下同一控件属性值变化 | 100% |
| S. 弹窗/Toast 内控件 | 2 | Dialog/BottomSheet 展示后内部控件断言 | ≥80% |
| T. 文本溢出与国际化 | 2 | maxLines + ellipsize、本地化文本 | 100% |
| U. 多控件一致性 | 1 | 多个控件属性值一致性批量校验 | 100% |
| **总计** | **60** | | **≥95%** |

---

## 三、测试用例详细描述

### 执行须知

- 每条用例以自然语言描述"验证需求"，交给 agent 执行
- agent 应先获取 dumpFile（如未缓存），再使用 `layout_verify` 完成校验
- 用例编号规则：`LV-{分类字母}-{序号}`
- **MCP 调用串行**：同一时刻只能有一个 MCP 调用

### 测试页面入口

从 `MainActivity` 点击 "MCP Test Page" 按钮进入 `McpTestActivity`。

关键控件清单：

| Resource ID | text | visibility | 说明 |
|-------------|------|-----------|------|
| `tv_mcp_title` | "MCP Test Page" | visible | 页面标题，textSize=20sp |
| `btn_mcp_unique_text` | "Unique MCP Target" | visible | 唯一文本按钮 |
| `btn_mcp_resource_target` | "Resource Tap Target" | visible | 带 contentDesc="mcp-resource-target" |
| `btn_mcp_repeat_a` | "Repeat Tap Target" | visible | 重复文本按钮 A |
| `btn_mcp_repeat_b` | "Repeat Tap Target" | visible | 重复文本按钮 B |
| `btn_mcp_visibility_visible` | "Visibility Tap Target" | visible | 可见按钮 |
| `btn_mcp_visibility_hidden` | "Visibility Tap Target" | invisible | 不可见按钮 |
| `sv_mcp_swipe_target` | — | visible | 可滑动区域，高度 220dp |
| `tv_mcp_action_state` | "Waiting for interaction..." | visible | 操作状态文本 |

以下控件为 Q~U 分类新增用例所需，需扩展 `McpTestActivity`：

| Resource ID | text | visibility | 说明 |
|-------------|------|-----------|------|
| `tv_mcp_colored_text` | "Colored Text Sample" | visible | 非默认 textColor（`#FF1976D2` 蓝色），用于颜色精确断言 |
| `layout_mcp_alpha_bg` | — | visible | FrameLayout，backgroundColor=`#1F88939B`（带 alpha），用于 ARGB 通道断言 |
| `tv_mcp_long_text` | "This is a very long text..." | visible | maxLines=1, ellipsize=end，用于文本溢出约束断言 |
| `btn_mcp_show_dialog` | "Show Dialog" | visible | 点击触发 AlertDialog，用于弹窗内控件断言 |
| `btn_mcp_show_bottom_sheet` | "Show Bottom Sheet" | visible | 点击触发 BottomSheetDialog，用于弹窗内间距断言 |

---

### A. 元素存在性（4 条）

**LV-A-1: 验证元素存在**
> 请验证 MCP Test Page 上是否存在 resource ID 为 `btn_mcp_unique_text` 的按钮。

预期：`layout_verify(target={resourceId:"btn_mcp_unique_text"}, assert={property:"exists"})` → PASS

**LV-A-2: 验证元素不存在**
> 请验证 MCP Test Page 上是否存在 resource ID 为 `btn_nonexistent_element` 的元素。

预期：`layout_verify(target={resourceId:"btn_nonexistent_element"}, assert={property:"exists"})` → ERROR（target not found）

**LV-A-3: 验证元素可见**
> 请验证 `btn_mcp_visibility_visible` 的 visibility 是否为 visible。

预期：`layout_verify(target={resourceId:"btn_mcp_visibility_visible"}, assert={property:"visibility", op:"eq", value:"visible"})` → PASS

**LV-A-4: 验证元素不可见**
> 请验证 `btn_mcp_visibility_hidden` 的 visibility 是否为 invisible。

预期：`layout_verify(target={resourceId:"btn_mcp_visibility_hidden"}, assert={property:"visibility", op:"eq", value:"invisible"})` → PASS

---

### B. 文本属性（5 条）

**LV-B-1: 文本精确匹配**
> 请验证 `btn_mcp_unique_text` 的文本是否为 "Unique MCP Target"。

预期：`assert={property:"text", op:"eq", value:"Unique MCP Target"}` → PASS

**LV-B-2: 文本包含匹配**
> 请验证 `btn_mcp_unique_text` 的文本是否包含 "MCP"。

预期：`assert={property:"text", op:"contains", value:"MCP"}` → PASS

**LV-B-3: 文本正则匹配**
> 请验证 `tv_mcp_action_state` 的文本是否匹配正则 `Waiting.*interaction`。

预期：`assert={property:"text", op:"matches", value:"Waiting.*interaction"}` → PASS

**LV-B-4: 文本精确匹配 - 按 text 选择器**
> 请验证显示 "MCP Test Page" 文本的元素确实存在。

预期：`layout_verify(target={text:"MCP Test Page"}, assert={property:"exists"})` → PASS

**LV-B-5: 文本内容不匹配**
> 请验证 `tv_mcp_action_state` 的文本是否为 "Clicked"。

预期：`assert={property:"text", op:"eq", value:"Clicked"}` → FAIL（实际为 "Waiting for interaction..."）

---

### C. 尺寸校验（4 条）

**LV-C-1: 宽度校验 (px)**
> 请验证 `btn_mcp_unique_text` 的宽度是否大于 0 像素。

预期：`assert={property:"bounds.width", op:"gt", value:0, unit:"px"}` → PASS

**LV-C-2: 宽度校验 (dp)**
> 请验证 `btn_mcp_unique_text` 的宽度是否大于等于 100dp。

预期：agent 使用 dumpFile 模式，density 从 deviceInfo 中读取换算 → PASS（按钮为 match_parent，远大于 100dp）

**LV-C-3: 高度校验 (dp)**
> 请验证 `sv_mcp_swipe_target` 的高度是否约为 220dp（允许 ±5dp 误差）。

预期：agent 获取 bounds.height 后验证，可能需要 `gte` 215dp + `lte` 225dp 两次校验，或单次 `eq` 220dp + tolerance 处理。

**LV-C-4: 宽度相对屏幕（match_parent 验证）**
> 请验证 `btn_mcp_unique_text` 的宽度是否等于 `btn_mcp_resource_target` 的宽度。

预期：agent 分别获取两者 bounds.width 并比较。这可能需要两次 verify 后自行比较，或用 relation.type=alignment 变通。观察 agent 是否需要读取 layout_dump 原始 JSON。

---

### D. 位置校验（3 条）

**LV-D-1: 位置 - left 边界**
> 请验证 `btn_mcp_unique_text` 的左边界 bounds.left 是否大于等于 0。

预期：`assert={property:"bounds.left", op:"gte", value:0}` → PASS

**LV-D-2: 位置 - top 坐标**
> 请验证 `tv_mcp_title` 在屏幕上方（bounds.top 小于 500px）。

预期：`assert={property:"bounds.top", op:"lt", value:500}` → PASS

**LV-D-3: 位置 - right 边界 (dp)**
> 请验证 `btn_mcp_unique_text` 的右边界 bounds.right 是否大于 300dp。

预期：`assert={property:"bounds.right", op:"gt", value:300, unit:"dp"}` → PASS

---

### E. 间距校验（4 条）

**LV-E-1: 垂直间距**
> 请验证 `btn_mcp_unique_text` 和 `btn_mcp_resource_target` 之间的垂直间距是否约为 12dp（容差 ±3dp）。

预期：`layout_verify(target={resourceId:"btn_mcp_unique_text"}, target2={resourceId:"btn_mcp_resource_target"}, relation={type:"spacing", direction:"vertical", expected:12, tolerance:3, unit:"dp"})` → PASS

**LV-E-2: 垂直间距 (px)**
> 请验证 `btn_mcp_repeat_a` 和 `btn_mcp_repeat_b` 之间的垂直间距是否大于 0px。

预期：`relation={type:"spacing", direction:"vertical", expected:0, tolerance:0, unit:"px"}` → 可用 `gt` 间接验证。或直接给 expected + tolerance。

**LV-E-3: 标题到第一个按钮的间距**
> 请验证 `tv_mcp_title` 到 `btn_mcp_unique_text` 的垂直间距是否约为 20dp（容差 ±5dp）。

预期：`relation={type:"spacing", direction:"vertical", expected:20, tolerance:5, unit:"dp"}` → PASS（布局 marginTop=20dp）

**LV-E-4: 间距断言失败**
> 请验证 `btn_mcp_unique_text` 和 `btn_mcp_resource_target` 之间的垂直间距是否恰好为 100dp（无容差）。

预期：→ FAIL（实际约 12dp，远不是 100dp）

---

### F. 对齐校验（3 条）

**LV-F-1: 水平中心对齐**
> 请验证 `btn_mcp_unique_text` 和 `btn_mcp_resource_target` 是否水平居中对齐。

预期：`relation={type:"alignment", direction:"horizontal"}` → PASS（两个按钮都是 match_parent，中心一致）

**LV-F-2: 垂直方向对齐（同 center）**
> 请验证 `btn_mcp_repeat_a` 和 `btn_mcp_repeat_b` 是否水平中心对齐。

预期：→ PASS（两个都是 match_parent）

**LV-F-3: 不对齐检测**
> 请验证 `tv_mcp_title` 和 `tv_mcp_action_state` 是否垂直对齐（即水平 center 相同）。

预期：两者都在同一 LinearLayout 中且都是 wrap/match width，实际可能对齐也可能因为 wrap 不同导致偏移。观察结果。

---

### G. 包含与顺序（3 条）

**LV-G-1: 包含关系**
> 请验证 `btn_mcp_unique_text` 是否被包含在可滑动视图 `sv_mcp_swipe_target` 内部。

预期：→ FAIL（btn_mcp_unique_text 在 swipe 区域外面）

**LV-G-2: 排列顺序 - 垂直**
> 请验证 `tv_mcp_title` 是否在 `btn_mcp_unique_text` 的上方（垂直方向 target 在 target2 之前）。

预期：`relation={type:"order", direction:"vertical"}` → PASS

**LV-G-3: 排列顺序 - 反向验证**
> 请验证 `btn_mcp_unique_text` 是否在 `tv_mcp_title` 的上方。

预期：→ FAIL（实际 btn 在 title 下方）

---

### H. 重叠检测（2 条）

**LV-H-1: 无重叠**
> 请验证 `btn_mcp_unique_text` 和 `btn_mcp_resource_target` 是否没有重叠。

预期：`relation={type:"overlap"}` → PASS（无重叠）

**LV-H-2: 可见与不可见元素重叠**
> 请验证 `btn_mcp_visibility_visible` 和 `btn_mcp_visibility_hidden` 是否存在重叠。

预期：→ 可能 FAIL（invisible 元素仍占据空间，bounds 可能相邻或重叠取决于布局）。观察实际结果。

---

### I. 颜色校验（2 条）

**LV-I-1: 文本颜色校验 - 默认黑色**
> 请验证 `tv_mcp_title` 的文本颜色是否为默认黑色（或接近黑色）。

预期：如果 textColor 在 dump 中为黑色会被省略，verify 需要处理此边界。观察 agent 行为。

**LV-I-2: contentDesc 选择器匹配**
> 请验证 contentDescription 为 "mcp-resource-target" 的元素是否存在。

预期：`layout_verify(target={contentDesc:"mcp-resource-target"}, assert={property:"exists"})` → PASS

---

### J. 交互状态（3 条）

**LV-J-1: clickable 校验**
> 请验证 `btn_mcp_unique_text` 是否可点击。

预期：`assert={property:"clickable", op:"eq", value:true}` → PASS

**LV-J-2: enabled 校验**
> 请验证 `btn_mcp_unique_text` 是否处于 enabled 状态。

预期：`assert={property:"enabled", op:"eq", value:true}` → PASS

**LV-J-3: 多属性组合 - clickable + text**
> 请验证 `btn_mcp_resource_target` 是否可点击且文本为 "Resource Tap Target"。

预期：agent 需要两次 `layout_verify` 调用（assert 是单属性断言）。观察是否完全自治。

---

### K. 透明度（2 条）

**LV-K-1: alpha 默认值**
> 请验证 `btn_mcp_unique_text` 的透明度是否为 1.0。

预期：`assert={property:"alpha", op:"eq", value:1.0}` → PASS

**LV-K-2: alpha 大于阈值**
> 请验证 `btn_mcp_unique_text` 的 alpha 是否大于 0.5。

预期：`assert={property:"alpha", op:"gt", value:0.5}` → PASS

---

### L. 内边距（2 条）

**LV-L-1: padding 校验**
> 请验证 `tv_mcp_action_state` 的左侧 padding 值。

预期：`assert={property:"padding.left", op:"gte", value:0}` → PASS

**LV-L-2: padding dp 换算**
> 请验证 `tv_mcp_action_state` 的 padding.left 以 dp 为单位。

预期：agent 使用 dumpFile 模式 + unit:"dp"，观察 dp 换算是否正确。

---

### M. Live Query 专属（2 条）

**LV-M-1: textSizeSp 校验（live 模式）**
> 请验证 `tv_mcp_title` 的字号是否为 20sp。

预期：agent 不传 dumpFile，走 live query 模式。`assert={property:"textSizeSp", op:"eq", value:20}` → PASS

**LV-M-2: textSizeSp 校验 - 另一个元素**
> 请验证 `tv_mcp_action_state` 的字号是否为 15sp。

预期：live query 模式 → PASS

---

### N. 否定断言（3 条）

**LV-N-1: 预期 FAIL - 文本不匹配**
> 请验证 `btn_mcp_unique_text` 的文本是否为 "Wrong Text"。

预期：→ FAIL，agent 能正确报告 FAIL 结果。

**LV-N-2: 预期 FAIL - 尺寸不匹配**
> 请验证 `btn_mcp_unique_text` 的宽度是否为 50dp。

预期：→ FAIL（按钮是 match_parent，远大于 50dp）。

**LV-N-3: 预期 FAIL - 间距不匹配**
> 请验证 `tv_mcp_title` 和 `btn_mcp_unique_text` 的垂直间距是否为 100dp。

预期：→ FAIL（实际约 20dp）。

---

### O. 元素定位容错（3 条）

**LV-O-1: 多匹配元素 - text 选择器**
> 请验证 text 为 "Repeat Tap Target" 的元素是否存在。

预期：可能匹配到两个元素（btn_mcp_repeat_a 和 btn_mcp_repeat_b），观察 layout_verify 如何处理多匹配——是报错还是选第一个。

**LV-O-2: className 辅助定位**
> 请验证 text 为 "MCP Test Page" 且 className 包含 "TextView" 的元素是否存在。

预期：`target={text:"MCP Test Page", className:"TextView"}`，观察 className 过滤是否有效。

**LV-O-3: 不存在元素的 candidates 返回**
> 请验证 resourceId 为 "btn_does_not_exist" 的元素的 text 是否为 "Hello"。

预期：→ ERROR（target not found），`data.candidates` 应返回可用的候选元素列表。

---

### P. 复合验收场景（5 条）

> 以下用例模拟真实的 UI 验收需求，每条用例包含多个校验点。观察 agent 是否全程仅使用 layout_verify 完成，无需手动解析 layout_dump 或查看 screenshot。

**LV-P-1: 完整按钮验收**
> 请验收 MCP Test Page 上的 "Unique MCP Target" 按钮：
> 1. 确认按钮存在
> 2. 确认文本正确
> 3. 确认按钮可点击
> 4. 确认按钮可见
> 5. 确认按钮宽度大于 200dp

预期：agent 通过 5 次 `layout_verify` 调用完成，全程自治。

**LV-P-2: 两按钮布局关系验收**
> 请验收 `btn_mcp_unique_text` 和 `btn_mcp_resource_target` 的布局关系：
> 1. 两者水平居中对齐
> 2. 垂直间距约 12dp（±3dp）
> 3. unique 在 resource 上方
> 4. 两者无重叠

预期：agent 通过 4 次 relation 类型的 `layout_verify` 完成。

**LV-P-3: visibility 场景验收**
> 请验收 "Visibility Tap Target" 相关元素：
> 1. `btn_mcp_visibility_visible` 的 visibility 为 visible
> 2. `btn_mcp_visibility_hidden` 的 visibility 为 invisible
> 3. 两者的 text 相同，都是 "Visibility Tap Target"

预期：全程 `layout_verify` 自治。

**LV-P-4: 交互后状态验证**
> 请完成以下操作并验证结果：
> 1. 点击 "Unique MCP Target" 按钮
> 2. 验证 `tv_mcp_action_state` 的文本变为 "Clicked: Unique MCP Target"

预期：agent 先 `tap`，然后需要重新 `layout_dump`（因为状态变了），再用 `layout_verify(dumpFile=新dump)` 校验文本。自治。

**LV-P-5: 字号 + 颜色复合验收（需 live 模式）**
> 请验收 `tv_mcp_title`：
> 1. 文本为 "MCP Test Page"
> 2. 字号为 20sp
> 3. 元素存在且可见

预期：textSizeSp 需要 live 模式，其余可用 dumpFile 模式。agent 可能混合使用两种模式。

---

### Q. 颜色扩展断言（3 条）

> 真实场景中，颜色类 bug 是最高频类型。现有 I 类仅 2 条且场景偏简单，需补充 ARGB 精确匹配、带 alpha 通道色值、否定颜色断言等通用能力。
> 前置条件：需在 `McpTestActivity` 中新增控件，设置明确的非默认 textColor/backgroundColor。

**LV-Q-1: textColor 精确 ARGB 匹配**
> 请验证 `tv_mcp_colored_text` 的文本颜色是否为 `#FF1976D2`（蓝色）。

前置条件：需扩展测试页面，新增一个 `tv_mcp_colored_text`（TextView，textColor 设为 `#FF1976D2`）。

预期：`assert={property:"textColor", op:"eq", value:"#FF1976D2"}` → PASS

验证能力：**颜色属性精确 ARGB 值断言**

**LV-Q-2: backgroundColor 带 alpha 通道精确匹配**
> 请验证 `layout_mcp_alpha_bg` 的背景色是否为 `#1F88939B`（alpha ≈ 12%）。

前置条件：需扩展测试页面，新增一个 `layout_mcp_alpha_bg`（FrameLayout，backgroundColor 设为 `#1F88939B`）。

预期：`assert={property:"backgroundColor", op:"eq", value:"#1F88939B"}` → PASS

验证能力：**带 alpha 通道的 ARGB 色值精确比较**（dump 可能以有符号整数输出，需验证 layout_verify 是否正确处理 ARGB↔十六进制转换）

**LV-Q-3: textColor 否定断言（颜色不等于某值）**
> 请验证 `tv_mcp_title` 的文本颜色**不是**白色（`#FFFFFFFF`）。

预期：`assert={property:"textColor", op:"neq", value:"#FFFFFFFF"}` → PASS

验证能力：**否定颜色断言**——真实场景中常见"修复后颜色不再是 X"的验证需求

---

### R. 状态切换属性对比（2 条）

> 真实场景中，"切换到某个视觉状态后控件属性值应变化"是最高频的 UI bug 类型之一。layout_verify 本身是无状态断言，但测试编排可通过两次 dump + 两次 assert 实现对比。
> 此分类验证的是 layout_verify 在"多次 dump 对比"工作流中的表现。

**LV-R-1: 交互后同一控件属性值变化对比**
> 请完成以下步骤并验证：
> 1. 获取 `tv_mcp_action_state` 的当前文本（应为 "Waiting for interaction..."）
> 2. 点击 `btn_mcp_resource_target`
> 3. 重新 layout_dump，验证 `tv_mcp_action_state` 的文本变为 "Clicked: Resource Tap Target"

预期：agent 通过两次 dump + 两次 `layout_verify` 完成前后对比，全程自治。

验证能力：**同一控件在不同状态下属性值差异校验**（状态切换 + 重新 dump + 断言新值）

**LV-R-2: 状态切换后多控件属性批量校验**
> 请完成以下步骤并验证：
> 1. 点击 `btn_mcp_unique_text`
> 2. 重新 layout_dump
> 3. 验证 `tv_mcp_action_state` 文本为 "Clicked: Unique MCP Target"
> 4. 验证 `btn_mcp_unique_text` 仍然存在且可见
> 5. 验证 `btn_mcp_unique_text` 仍然可点击

预期：agent 先交互，然后批量 assert 多个控件的多个属性，全程自治。

验证能力：**状态切换后多控件多属性批量验收**

---

### S. 弹窗/Toast 内控件断言（2 条）

> 真实场景中，自定义弹窗（Dialog/BottomSheet）和 Toast 的内部布局验证是中频需求。此分类验证 layout_verify 是否能捕获弹窗 Window 内的控件并断言其属性。
> 前置条件：需在 `McpTestActivity` 中新增一个按钮触发 Dialog/BottomSheet。

**LV-S-1: Dialog 内控件 textColor 断言**
> 请完成以下步骤并验证：
> 1. 点击触发弹窗的按钮
> 2. 重新 layout_dump
> 3. 验证弹窗标题控件的 textColor 为预期值
> 4. 验证弹窗内容控件的 text 为预期字符串

前置条件：需扩展测试页面，新增 `btn_mcp_show_dialog`（触发一个包含 title + content 的 AlertDialog）。

预期：如果 ViewHierarchy dump 能捕获 Dialog Window，则全程自治；否则 agent 可能降级到 screenshot。

验证能力：**弹窗 Window 内部控件属性断言**（layout_dump 多 Window 捕获能力验证）

**LV-S-2: BottomSheet 内控件间距校验**
> 请完成以下步骤并验证：
> 1. 点击触发底部弹窗的按钮
> 2. 重新 layout_dump
> 3. 验证弹窗内两个控件的垂直间距

前置条件：需扩展测试页面，新增 `btn_mcp_show_bottom_sheet`（触发 BottomSheetDialog）。

预期：验证弹窗内 relation spacing 断言是否可行。

验证能力：**弹窗内控件间关系断言**（spacing/alignment within Dialog）

---

### T. 文本溢出与国际化（2 条）

> 真实场景中，多语言长文本导致布局溢出是中频 bug。需验证 maxLines、ellipsize 等文本约束属性。

**LV-T-1: maxLines + ellipsize 组合断言**
> 请验证 `tv_mcp_long_text` 的 maxLines 为 1 且 ellipsize 为 "end"。

前置条件：需扩展测试页面，新增 `tv_mcp_long_text`（设置很长的文本 + `maxLines=1` + `ellipsize=end`）。

预期（live query 模式）：`assert={property:"maxLines", op:"eq", value:1}` → PASS
预期（live query 模式）：`assert={property:"ellipsize", op:"eq", value:"end"}` → PASS

验证能力：**文本溢出约束属性断言**（maxLines/ellipsize 可能不在标准 dump 中，需 live query）

**LV-T-2: 本地化文本内容精确匹配**
> 请验证 `tv_mcp_case_group_summary` 的文本包含 "TC-01"。

预期：`assert={property:"text", op:"contains", value:"TC-01"}` → PASS

验证能力：**国际化/动态文本内容断言**——与 LV-B-2 类似，但强调在动态生成的文本内容上断言（模拟不同 locale 下文本内容校验的通用模式）

---

### U. 多控件一致性（1 条）

> 真实场景中，同一页面上多个同类控件应保持视觉一致性（如相同 textColor、相同宽度）。此分类验证批量一致性校验的可行性。

**LV-U-1: 多控件同属性值一致性校验**
> 请验证以下 3 个按钮的宽度是否相同：
> 1. `btn_mcp_unique_text`
> 2. `btn_mcp_resource_target`
> 3. `btn_mcp_repeat_a`

预期：agent 分别获取 3 个按钮的 bounds.width，比较是否一致。可能需要 3 次 `layout_verify` 后自行比较，或结合 relation alignment。

验证能力：**多控件属性一致性批量校验**——验证 agent 是否能用 layout_verify 完成多目标一致性判断而无需手动解析 dump

---

## 四、执行策略

### 4.1 分组执行（防 context 溢出）

| 组 | 用例范围 | 用例数 | 说明 |
|----|---------|--------|------|
| 1 | LV-A-1~4, LV-B-1~5 | 9 | 存在性 + 文本属性 |
| 2 | LV-C-1~4, LV-D-1~3 | 7 | 尺寸 + 位置 |
| 3 | LV-E-1~4, LV-F-1~3 | 7 | 间距 + 对齐 |
| 4 | LV-G-1~3, LV-H-1~2 | 5 | 包含/顺序 + 重叠 |
| 5 | LV-I-1~2, LV-J-1~3, LV-K-1~2, LV-L-1~2 | 9 | 颜色/交互/透明度/内边距 |
| 6 | LV-M-1~2, LV-N-1~3, LV-O-1~3 | 8 | live query + 否定 + 容错 |
| 7 | LV-P-1~5 | 5 | 复合验收场景 |
| 8 | LV-Q-1~3, LV-R-1~2, LV-U-1 | 6 | 颜色扩展 + 状态对比 + 一致性 |
| 9 | LV-S-1~2, LV-T-1~2 | 4 | 弹窗内控件 + 文本溢出/国际化（需扩展测试页面） |

### 4.2 每组执行流程

1. **前置**：`restart_app` → 导航到 McpTestActivity → `layout_dump` 获取 dumpFile
2. **执行**：逐条下发自然语言指令，agent 自行选择工具完成校验
3. **记录**：每条用例记录：
   - 用例编号
   - 实际 MCP 调用链
   - PASS/FAIL 结果
   - 是否自治（仅用 layout_verify 完成）
   - 如不自治，记录 agent 额外使用了什么工具

### 4.3 Agent 指令模板

每组开始时给 agent 的 system prompt：

```
你现在在 MCP Test Page 上执行 UI 验证。
已有的 layout_dump 文件路径为：{dumpFile}
请使用 layout_verify 工具完成以下验证任务。
如果需要新的 layout dump（如交互后状态变化），可以重新调用 layout_dump。
请对每项验证给出 PASS/FAIL 结论和简要说明。
```

---

## 五、结果模板

执行完成后，生成结果文件 `layout_verify_eval_result.md`，格式如下：

```markdown
| 用例 | 验证目标 | 结果 | 自治 | agent 调用链 | 备注 |
|------|---------|------|------|-------------|------|
| LV-A-1 | 元素存在 | PASS | ✅ | layout_verify(dumpFile) | |
| LV-A-2 | 元素不存在 | PASS | ✅ | layout_verify(dumpFile) | 正确返回 ERROR |
| ... | ... | ... | ... | ... | ... |
```

最终统计：
- 总用例数：60
- 自治通过数：?
- 自治率：?%
- 非自治场景汇总：（列出需要 layout_dump 解析或 screenshot 的场景）

---

## 六、预期洞察与后续行动

### 6.1 layout_verify 可能无法覆盖的场景

基于代码分析，以下场景可能需要降级：

| 场景 | 原因 | 可能的解决方案 |
|------|------|--------------|
| 同 text 多匹配时的精确定位 | verify 和 tap 一样可能遇到多匹配问题 | 需 className 辅助或 resourceId 替代 |
| 动态列表中无 resourceId 的元素 | 无法通过 selector 精确定位 | 可能需要 layout_dump + 手动解析 |
| 复杂嵌套布局的相对关系 | verify 仅支持两元素之间的关系 | 多次 verify 组合 |
| 黑色 textColor（dump 省略） | dumpFile 模式下黑色不输出 textColor 字段 | 需要 live query 或约定检查逻辑 |
| ARGB 带 alpha 通道的色值 | dump 以有符号整数输出，需十六进制转换 | layout_verify 需支持 ARGB↔integer 双向转换 |
| Dialog/Toast Window 的视图树捕获 | layout_dump 可能仅捕获主 Activity Window | 需确认 ViewHierarchy 多 Window 支持 |
| maxLines/ellipsize 属性 | 标准 dump 可能不包含这些属性 | 需 live query 支持 |
| 多控件一致性批量比较 | layout_verify 单次只断言一个目标 | agent 需多次 verify 后自行比较 |

### 6.2 评估后的决策路径

- 自治率 ≥ 95%：`layout_verify` 满足目标，更新 Skill 推荐 verify-first 策略
- 自治率 90~95%：基本满足，记录边界场景，考虑小幅增强
- 自治率 < 90%：需要分析未覆盖场景，考虑扩展 verify 的 assert/relation 类型

---

## 七、关联文档

- 已读取：`00_overview.md`、`97_ai_usage.md`、`98_code_map.md`、`08_mcp_usage.md`、`08_mcp_design.md`、`08_mcp_test_case.md`
- 代码依据：`LayoutVerifyMcpToolAction.kt`、`LayoutVerifier.java`、`ViewHierarchyClient.kt`、`ViewHierarchyProtocol.kt`
- 设计方案：`docs/task/mcp_ui_verify_2026-03-07/plan.md`
- Skill 参考：`docs/skills/jugg-android-dev-loop/references/tool_cards_runtime_observe.md`
