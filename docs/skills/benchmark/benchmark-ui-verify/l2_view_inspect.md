# L2 Unit：view_inspect / eval_view

> 覆盖属性读取命令的各类场景：基础 getter（text/bounds）、
> 样式 getter（textColor/textSizeSp/backgroundColor）、
> 链式表达式、paddingLeft 验证。

---

### TC-VI01：读取 TextView 文本内容

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "tv_mcp_body_text" 的 TextView 的文本内容

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_body_text"}, expressions=["getText().toString()"])`
2. 返回 `"Body Text Sample"`

**关键参数**：
- `resourceId` = `"tv_mcp_body_text"`
- `expressions` 含 `"getText().toString()"`

**期望输出行为**：
- LLM 报告文本为 `"Body Text Sample"`

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确调用 + 表达式正确 + 报告了正确文本 |
| 4 | 表达式有细微差异（如 `getText()`）但结果正确 |
| 3 | 使用了 ui_find 的 text 属性代替 eval_view |
| 2 | 命令调用方向正确但参数有误 |
| 1 | 方向性错误 |
| 0 | 未调用命令 |

---

### TC-VI02：读取 TextView 文字颜色（#FF0000）

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "tv_mcp_style_title" 的 TextView 的文字颜色

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_style_title"}, expressions=["getCurrentTextColor()"])`
2. 返回颜色整数值（对应 #FFFF0000 = -65536）

**关键参数**：
- `resourceId` = `"tv_mcp_style_title"`
- `expressions` 含 `"getCurrentTextColor()"`

**期望输出行为**：
- LLM 正确解读颜色值为红色（#FF0000）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 正确解读颜色为红色 |
| 4 | 表达式正确但颜色描述不够精确 |
| 3 | 使用了 `getTextColors()` 等非标准方法，但结论基本正确 |
| 2 | 使用截图判断颜色 |
| 1 | 颜色值解读错误 |
| 0 | 未调用命令 |

---

### TC-VI03：读取 TextView 文字大小（20sp）

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "tv_mcp_style_title" 的 TextView 的字号大小，以 sp 为单位

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_style_title"}, expressions=["getTextSize()"])`
2. 返回 px 值，需换算为 sp（设备 scaledDensity=2.625，20sp ≈ 52.5px）

**关键参数**：
- `resourceId` = `"tv_mcp_style_title"`
- `expressions` 含 `"getTextSize()"` 或等价方法

**期望输出行为**：
- LLM 报告字号约为 20sp

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 换算结果约为 20sp |
| 4 | 表达式正确但未换算（只报告了 px 值） |
| 3 | 使用了正确命令但表达式有偏差 |
| 2 | 使用了错误命令 |
| 1 | 数值完全错误 |
| 0 | 未调用命令 |

---

### TC-VI04：读取 View 背景颜色（#0000FF）

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "view_mcp_bg_block" 的 View 的背景颜色

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "view_mcp_bg_block"}, expressions=["getBackground()"])`
2. 返回颜色描述（对应 #0000FF）

**关键参数**：
- `resourceId` = `"view_mcp_bg_block"`
- `expressions` 含背景色相关方法

**期望输出行为**：
- LLM 报告背景颜色为蓝色（#0000FF）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 正确解读颜色为蓝色 |
| 4 | 表达式正确但颜色描述不够精确 |
| 3 | 使用截图判断颜色，结论正确 |
| 2 | 使用了错误命令 |
| 1 | 颜色解读错误 |
| 0 | 未调用命令 |

---

### TC-VI05：读取 TextView 深色文字颜色（#333333）

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "tv_mcp_body_text" 的 TextView 的文字颜色，并说明是什么颜色

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_body_text"}, expressions=["getCurrentTextColor()"])`
2. 返回颜色值（对应 #FF333333）

**关键参数**：
- `resourceId` = `"tv_mcp_body_text"`
- `expressions` 含 `"getCurrentTextColor()"`

**期望输出行为**：
- LLM 报告颜色为深灰色（#333333）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 正确识别为深灰色 |
| 4 | 表达式正确但颜色描述不够精确 |
| 3 | 命令正确但方法有偏差 |
| 2 | 使用错误命令 |
| 1 | 颜色识别错误 |
| 0 | 未调用命令 |

---

### TC-VI06：读取 ImageView 宽高（32×32dp）

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "iv_mcp_icon" 的 ImageView 的实际宽高（以 dp 为单位）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "iv_mcp_icon"}, expressions=["getWidth()", "getHeight()"])`
2. 返回宽高 px 值（设备 density=2.625，32dp ≈ 84px）

**关键参数**：
- `resourceId` = `"iv_mcp_icon"`
- `expressions` 含 `"getWidth()"` 和 `"getHeight()"`

**期望输出行为**：
- LLM 报告宽 = 高 = 32dp

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 换算后报告 32×32dp |
| 4 | 表达式正确但只报告了 px 值 |
| 3 | 只读取了宽或高之一 |
| 2 | 使用了 ui_find 的 bounds 代替 eval_view |
| 1 | 数值错误 |
| 0 | 未调用命令 |

---

### TC-VI07：读取 TextView paddingLeft（8dp）

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "tv_mcp_label" 的 TextView 的左内边距（paddingLeft）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_label"}, expressions=["getPaddingLeft()"])`
2. 返回 px 值（8dp × 2.625 ≈ 21px）

**关键参数**：
- `resourceId` = `"tv_mcp_label"`
- `expressions` 含 `"getPaddingLeft()"`

**期望输出行为**：
- LLM 报告 paddingLeft = 8dp

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 换算后报告 8dp |
| 4 | 表达式正确但未换算 |
| 3 | 使用了 `getPaddingStart()` 代替，但结论正确 |
| 2 | 使用了 figma-layout-verify 代替 eval_view |
| 1 | 数值错误 |
| 0 | 未调用命令 |

---

### TC-VI08：链式表达式读取多属性

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 一次性读取 id 为 "tv_mcp_style_title" 的 TextView 的文本内容、文字颜色和字号大小

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_style_title"}, expressions=["getText().toString()", "getCurrentTextColor()", "getTextSize()"])`
2. 返回三个属性值

**关键参数**：
- `resourceId` = `"tv_mcp_style_title"`
- `expressions` 同时包含三个表达式

**期望输出行为**：
- LLM 在一次调用中获取全部三个属性
- 报告：文本="Style Title"，颜色=红色，字号=20sp

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 一次调用三个表达式 + 三个结论均正确 |
| 4 | 一次调用三个表达式但某个结论有偏差 |
| 3 | 拆分为多次调用，每次一个表达式，但结论正确 |
| 2 | 使用错误命令 |
| 1 | 只读取了部分属性 |
| 0 | 未调用命令 |

---

### TC-VI09：Card 内 TextView 文本验证

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 分别读取 tv_card_title 和 tv_card_desc 的文本内容

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_card_title"}, expressions=["getText().toString()"])`
2. 执行 `eval_view(target={resourceId: "tv_card_desc"}, expressions=["getText().toString()"])`
3. 返回两个文本值

**关键参数**：
- 两个 `resourceId` 均正确

**期望输出行为**：
- LLM 报告 tv_card_title = "Card Title"，tv_card_desc = "Card Description"

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 两次调用均正确 + 两个文本均正确报告 |
| 4 | 两次调用正确但一个文本报告有误 |
| 3 | 只读取了其中一个 |
| 2 | 使用了 ui_find 的 text 属性代替 eval_view |
| 1 | 两个文本均报告错误 |
| 0 | 未调用命令 |

---

### TC-VI10：读取 Button 的 clickable 状态

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 确认 id 为 "btn_mcp_unique_text" 的按钮是否可点击

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "btn_mcp_unique_text"}, expressions=["isClickable()"])`
2. 返回 `true`

**关键参数**：
- `resourceId` = `"btn_mcp_unique_text"`
- `expressions` 含 `"isClickable()"`

**期望输出行为**：
- LLM 报告按钮可点击

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 结论正确（可点击） |
| 4 | 表达式正确但结论描述不够明确 |
| 3 | 使用了 ui_find 的 clickable 属性代替 eval_view |
| 2 | 使用了错误命令 |
| 1 | 结论错误（报告不可点击） |
| 0 | 未调用命令 |

---

### TC-VI11：读取 View 的可见性（getVisibility 枚举解读）

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity（`btn_mcp_visibility_hidden` visibility=INVISIBLE）

**输入（LLM 收到的指令）**：
> 读取 id 为 "btn_mcp_visibility_hidden" 的按钮的可见性状态（getVisibility），并说明具体是 VISIBLE、INVISIBLE 还是 GONE

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "btn_mcp_visibility_hidden"}, expressions=["getVisibility()"])`
2. 返回整数 4（INVISIBLE）

**关键参数**：
- `resourceId` = `"btn_mcp_visibility_hidden"`
- `expressions` 含 `"getVisibility()"`

**期望输出行为**：
- LLM 正确将返回值 4 解读为 INVISIBLE（0=VISIBLE, 4=INVISIBLE, 8=GONE）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 正确解读枚举值为 INVISIBLE |
| 4 | 表达式正确但枚举值描述不够精确（如只说"不可见"） |
| 3 | 调用了命令但未解读枚举含义，直接输出数字 |
| 2 | 使用 ui_find 的 visibility 属性代替，但结论正确 |
| 1 | 枚举值解读错误（如把 4 解读为 VISIBLE） |
| 0 | 未调用命令 |

---

### TC-VI12：读取 View 的 alpha 值

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "btn_mcp_unique_text" 的按钮的 alpha 值，确认是否完全不透明

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "btn_mcp_unique_text"}, expressions=["getAlpha()"])`
2. 返回 `1.0`

**关键参数**：
- `resourceId` = `"btn_mcp_unique_text"`
- `expressions` 含 `"getAlpha()"`

**期望输出行为**：
- LLM 报告 alpha = 1.0，按钮完全不透明

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 报告 alpha=1.0 + 结论"完全不透明"正确 |
| 4 | 表达式正确但未给出"完全不透明"的语义解释 |
| 3 | 调用正确但表达式有偏差（如 `getAlpha` 少括号） |
| 2 | 使用了错误命令 |
| 1 | 数值错误 |
| 0 | 未调用命令 |

---

### TC-VI13：读取 Button 的 enabled 状态

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "btn_mcp_unique_text" 的按钮是否处于启用状态（isEnabled）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "btn_mcp_unique_text"}, expressions=["isEnabled()"])`
2. 返回 `true`

**关键参数**：
- `resourceId` = `"btn_mcp_unique_text"`
- `expressions` 含 `"isEnabled()"`

**期望输出行为**：
- LLM 报告按钮处于启用状态

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 结论正确（已启用） |
| 4 | 表达式正确但结论描述不够明确 |
| 3 | 使用 isClickable() 代替 isEnabled()，但结论正确 |
| 2 | 使用了错误命令 |
| 1 | 结论错误（报告未启用） |
| 0 | 未调用命令 |

---

### TC-VI14：读取 TextView 的 maxLines 属性

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "tv_mcp_title" 的 TextView 的最大行数限制（maxLines）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_title"}, expressions=["getMaxLines()"])`
2. 返回 `Integer.MAX_VALUE`（2147483647，表示未在 XML 中设置 maxLines）

**关键参数**：
- `resourceId` = `"tv_mcp_title"`
- `expressions` 含 `"getMaxLines()"`

**期望输出行为**：
- LLM 报告 maxLines 返回 2147483647，并说明这表示**未设置 maxLines 限制**

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 正确解读 MAX_VALUE 为"未设置限制" |
| 4 | 表达式正确但未解读 MAX_VALUE 含义，直接输出数字 |
| 3 | 调用了命令但表达式有偏差 |
| 2 | 使用错误命令 |
| 1 | 将 MAX_VALUE 误解为 maxLines=1（结论错误） |
| 0 | 未调用命令 |

---

### TC-VI15：读取 TextView 的行数（getLineCount）

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "tv_mcp_title" 的 TextView 的实际渲染行数（lineCount）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_title"}, expressions=["getLineCount()"])`
2. 返回 `1`（标题文本较短，单行显示）

**关键参数**：
- `resourceId` = `"tv_mcp_title"`
- `expressions` 含 `"getLineCount()"`

**期望输出行为**：
- LLM 报告当前实际渲染行数为 1

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 报告行数为 1 |
| 4 | 表达式正确但未说明是"实际渲染行数"（与 maxLines 概念混淆） |
| 3 | 使用了 getMaxLines() 代替 getLineCount()，结论不同 |
| 2 | 使用错误命令 |
| 1 | 行数数值错误 |
| 0 | 未调用命令 |

---

### TC-VI16：读取 TextView 的 ellipsize 模式

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "tv_mcp_title" 的 TextView 的 ellipsize 截断模式

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_title"}, expressions=["getEllipsize()"])`
2. 返回 `null`（XML 中未设置 ellipsize 属性）

**关键参数**：
- `resourceId` = `"tv_mcp_title"`
- `expressions` 含 `"getEllipsize()"`

**期望输出行为**：
- LLM 报告返回值为 null，说明该 TextView **未设置 ellipsize 模式**

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 正确报告 null 并说明"未设置 ellipsize" |
| 4 | 表达式正确但未解释 null 的含义 |
| 3 | 调用了命令但表达式有偏差 |
| 2 | 使用错误命令 |
| 1 | 将 null 误解为 END 或其他值 |
| 0 | 未调用命令 |

---

### TC-VI17：读取 Button 的 gravity（位掩码解读）

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "btn_mcp_unique_text" 的按钮的 gravity 值，判断其内容是否居中对齐

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "btn_mcp_unique_text"}, expressions=["getGravity()"])`
2. 返回整数（Material Button 默认 gravity 含 CENTER=17）

**关键参数**：
- `resourceId` = `"btn_mcp_unique_text"`
- `expressions` 含 `"getGravity()"`

**期望输出行为**：
- LLM 正确解读 gravity 位掩码（CENTER=17，CENTER_HORIZONTAL=1，CENTER_VERTICAL=16），判断是否包含居中标志

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 表达式正确 + 正确解读位掩码 + 结论正确 |
| 4 | 表达式正确但位掩码解读不完整（如只说"17"未解释含义） |
| 3 | 调用了命令但未解读位掩码 |
| 2 | 使用错误命令 |
| 1 | 位掩码解读完全错误 |
| 0 | 未调用命令 |

---

### TC-VI18：批量读取多属性综合验证（clickable + enabled + alpha）

**级别**：L2
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 一次性验证 id 为 "btn_mcp_resource_target" 的按钮：文本是否为 "Resource Tap Target"、是否可点击、是否已启用、alpha 是否为 1.0

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "btn_mcp_resource_target"}, expressions=["getText().toString()", "isClickable()", "isEnabled()", "getAlpha()"])`
2. 返回四个属性值

**关键参数**：
- `resourceId` = `"btn_mcp_resource_target"`
- `expressions` 同时包含四个表达式

**期望输出行为**：
- LLM 在一次调用中获取全部四项，报告：text="Resource Tap Target"，可点击，已启用，alpha=1.0，全部满足

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 一次调用四个表达式 + 四个结论均正确 |
| 4 | 一次调用四个表达式但某个结论有偏差 |
| 3 | 拆分为多次调用，每次一个表达式，但结论正确 |
| 2 | 使用错误命令 |
| 1 | 只读取了部分属性，结论不完整 |
| 0 | 未调用命令 |
