# L3 集成用例

> 覆盖多命令组合的完整流程：页面状态确认 Gate + 定位 + 属性读取 + 布局验证 + 结果判断。
> L3 用例要求 LLM 在没有逐步指引的情况下，自主拆解任务并调用正确的命令序列。
>
> **Gate 规则**：每条用例开头的 `activity-stack` / `restart` 调用仅作门禁判断，不计入评分。
> Gate 失败（不在 McpTestActivity）则跳过整条用例，不评分。

---

### TC-I01：验证页面布局并读取关键元素属性（综合流程）

**级别**：L3
**命令**：activity-stack → figma-layout-verify → eval_view

**前置条件**：
- 设备已连接
- App 已启动

**输入（LLM 收到的指令）**：
> 确认当前在 McpTestActivity，然后验证布局是否符合设计，并读取 tv_mcp_style_title 的文字颜色

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `activity-stack` — Gate：确认在 McpTestActivity
2. 执行 `figma-layout-verify(figmaJsonPath="docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json", ...)`
3. 执行 `eval_view(target={resourceId: "tv_mcp_style_title"}, expressions=["getCurrentTextColor()"])`

**关键参数**：
- Step 2 `figmaJsonPath` = `"docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json"`
- Step 3 `resourceId` = `"tv_mcp_style_title"`, `expressions` = `["getCurrentTextColor()"]`

**期望输出行为**：
- 确认在正确页面
- 布局验证通过
- 颜色报告为红色（#FF0000）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 三步全部正确 + 三个结论均正确 |
| 4 | 序列正确但某步参数轻微偏差，结论正确 |
| 3 | 完成了 2/3 步，主要结论正确 |
| 2 | 调用了命令但顺序混乱，结论基本正确 |
| 1 | 只完成了 Gate 步骤 |
| 0 | 未调用命令或完全跑偏 |

---

### TC-I02：定位元素 + 读取属性 + 报告综合信息

**级别**：L3
**命令**：ui_find → eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到蓝色色块，读取它的宽高和背景颜色，综合报告这个 View 的信息

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `ui_find(target={resourceId: "view_mcp_bg_block"})` 或 通过文本/位置推断
2. 执行 `eval_view(target={resourceId: "view_mcp_bg_block"}, expressions=["getWidth()", "getHeight()", "getBackground()"])`

**关键参数**：
- 最终确认 `resourceId` = `"view_mcp_bg_block"`
- `expressions` 包含宽高和背景方法

**期望输出行为**：
- LLM 综合报告：蓝色背景（#0000FF），尺寸 120×48dp

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 两步均正确 + 综合报告完整（颜色+尺寸） |
| 4 | 两步正确但综合报告缺少某个属性 |
| 3 | 只完成了其中一步，另一步结论正确但方式不标准 |
| 2 | 只使用 ui_find 未调用 eval_view，仅报告位置 |
| 1 | 找到了错误的元素 |
| 0 | 未调用命令 |

---

### TC-I03：嵌套容器内多元素属性读取

**级别**：L3
**命令**：ui_find → eval_view（多次）

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 进入 ll_mcp_parent 容器，分别读取图标的尺寸和旁边文字标签的 paddingLeft，验证它们是否符合设计（图标 32dp，paddingLeft 8dp）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "iv_mcp_icon"}, expressions=["getWidth()", "getHeight()"])`
2. 执行 `eval_view(target={resourceId: "tv_mcp_label"}, expressions=["getPaddingLeft()"])`
3. LLM 对比设计预期，给出验证结论

**关键参数**：
- 两次 eval_view 的 resourceId 均正确
- 表达式均包含正确方法

**期望输出行为**：
- 图标：32×32dp ✅
- paddingLeft：8dp ✅
- LLM 给出符合设计的结论

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 两次调用均正确 + 结论完整（图标+padding 均符合） |
| 4 | 两次调用正确但验证结论缺少一项 |
| 3 | 只完成了一次 eval_view |
| 2 | 使用了 figma-layout-verify 代替 eval_view |
| 1 | resourceId 错误 |
| 0 | 未调用命令 |

---

### TC-I04：Card 容器布局完整验证

**级别**：L3
**命令**：figma-layout-verify → eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 全面验证 Card 容器（card_mcp_container）的布局：先用 Figma fixture 验证整体 spacing，再分别读取 tv_card_title 和 tv_card_desc 的实际文本内容

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `figma-layout-verify(figmaJsonPath="docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json", ...)`
2. 执行 `eval_view(target={resourceId: "tv_card_title"}, expressions=["getText().toString()"])`
3. 执行 `eval_view(target={resourceId: "tv_card_desc"}, expressions=["getText().toString()"])`

**期望输出行为**：
- Card 布局验证通过
- tv_card_title = "Card Title"
- tv_card_desc = "Card Description"

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 三步全部正确 + 结论完整 |
| 4 | 三步正确但某个文本报告有误 |
| 3 | 只完成了 2/3 步 |
| 2 | 跳过了 figma-layout-verify，只用 eval_view |
| 1 | 步骤顺序混乱且结论不正确 |
| 0 | 未调用命令 |

---

### TC-I05：页面重启后状态确认 + 布局验证

**级别**：L3
**命令**：restart → activity-stack → figma-layout-verify

**前置条件**：
- 设备已连接

**输入（LLM 收到的指令）**：
> 重启 App，等它重新停留在 McpTestActivity，然后验证布局是否仍然符合设计

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `restart` — Gate：重启 App
2. 执行 `activity-stack` — 确认回到 McpTestActivity
3. 执行 `figma-layout-verify(figmaJsonPath="docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json", ...)`

**关键参数**：
- Step 3 `figmaJsonPath` = `"docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json"`

**期望输出行为**：
- 重启成功
- 确认在 McpTestActivity
- 布局验证通过

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 三步全部正确 + 结论正确 |
| 4 | 序列正确但 activity-stack 结果确认步骤缺失 |
| 3 | 跳过了 restart_app，直接验证布局 |
| 2 | 重启后未确认页面状态直接验证 |
| 1 | 只调用了 restart_app |
| 0 | 未调用命令 |

---

### TC-I06：多属性综合对比验证（Style Inspect 节）

**级别**：L3
**命令**：eval_view（多次）

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 完整检查 Style Inspect 区域（Section 2）的三个元素：
> 1. tv_mcp_style_title：颜色应为红色，字号应为 20sp
> 2. view_mcp_bg_block：背景应为蓝色，尺寸应为 120×48dp  
> 3. tv_mcp_body_text：颜色应为深灰色（#333333），字号应为 14sp
> 逐一验证并给出符合/不符合结论

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_style_title"}, expressions=["getCurrentTextColor()", "getTextSize()"])`
2. 执行 `eval_view(target={resourceId: "view_mcp_bg_block"}, expressions=["getBackground()", "getWidth()", "getHeight()"])`
3. 执行 `eval_view(target={resourceId: "tv_mcp_body_text"}, expressions=["getCurrentTextColor()", "getTextSize()"])`

**期望输出行为**：
- 三个元素均逐一验证
- 每个元素给出符合/不符合结论

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 三次调用均正确 + 所有属性结论正确 |
| 4 | 三次调用均正确但某个属性结论有偏差 |
| 3 | 完成了 2/3 个元素的验证 |
| 2 | 使用 figma-layout-verify 代替 eval_view |
| 1 | 只验证了一个元素 |
| 0 | 未调用命令 |

---

### TC-I07：定位元素 + 点击 + 确认状态变化

**级别**：L3
**命令**：ui_find → tap → eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 点击 "Unique MCP Target" 按钮，然后读取 tv_mcp_action_state 的文字内容，确认状态是否更新了

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `ui_find(target={text: "Unique MCP Target"})` — 定位按钮
2. 执行 `tap` — 点击按钮
3. 执行 `eval_view(target={resourceId: "tv_mcp_action_state"}, expressions=["getText().toString()"])` — 读取状态

**期望输出行为**：
- 找到按钮并点击
- 读取状态文字
- 报告状态变化（若状态未变化也应如实报告）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 三步全部正确 + 如实报告状态 |
| 4 | 序列正确但对状态变化的描述不准确 |
| 3 | 完成点击但未读取状态 |
| 2 | 未定位元素直接按坐标点击 |
| 1 | 点击了错误的元素 |
| 0 | 未调用命令 |

---

### TC-I08：错误布局检测 + 读取关键属性（混合验证）

**级别**：L3
**命令**：figma-layout-verify → eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 先用 mcp_test_wrong_spacing.json 做布局验证，报告发现的偏差；然后读取 card_mcp_container 内 tv_card_title 的实际 marginBottom（以 px 单位）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `figma-layout-verify(figmaJsonPath="docs/skills/benchmark-ui-verify/fixtures/mcp_test_wrong_spacing.json", ...)`
2. 报告偏差
3. 执行 `eval_view(target={resourceId: "tv_card_title"}, expressions=["getTop()"])` 或类似方法

**关键参数**：
- Step 1 使用 `wrong_spacing` fixture
- Step 3 resourceId = `"tv_card_title"`

**期望输出行为**：
- 正确识别布局偏差
- 读取实际位置数据

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 两步均正确 + 偏差报告准确 + 属性读取正确 |
| 4 | 两步正确但偏差报告不够详细 |
| 3 | 只完成了 figma-layout-verify，未读取属性 |
| 2 | 使用了正确 fixture（非 wrong_spacing） |
| 1 | 方向性错误 |
| 0 | 未调用命令 |

---

### TC-I09：完整 Section 覆盖扫描（多 eval_view）

**级别**：L3
**命令**：eval_view（链式多次）

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 对 Section 4 中的 ll_mcp_parent、iv_mcp_icon、tv_mcp_label 三个元素，分别读取它们的可见状态（isShown()），并综合报告 Section 4 的显示状态是否正常

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "ll_mcp_parent"}, expressions=["isShown()"])`
2. 执行 `eval_view(target={resourceId: "iv_mcp_icon"}, expressions=["isShown()"])`
3. 执行 `eval_view(target={resourceId: "tv_mcp_label"}, expressions=["isShown()"])`

**期望输出行为**：
- 三个元素均为可见状态（true）
- LLM 报告 Section 4 显示正常

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 三次调用均正确 + 综合报告正确 |
| 4 | 三次调用均正确但综合报告不清晰 |
| 3 | 只调用了 1~2 次 |
| 2 | 使用了 ui_find 的 visibility 字段代替 eval_view |
| 1 | 只调用了一次 |
| 0 | 未调用命令 |

---

### TC-I10：页面结构探索 + Figma 验证 + 汇总报告

**级别**：L3
**命令**：ui_find → figma-layout-verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 先确认页面上有多少个 Button 类型的元素，然后用 Figma fixture 验证整体布局，最后汇总报告页面状态

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `ui_find` 或等价调用来枚举 Button 元素
2. 执行 `figma-layout-verify(figmaJsonPath="docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json", ...)`
3. 汇总报告

**期望输出行为**：
- 找到 5 个 Button（btn_mcp_unique_text, btn_mcp_resource_target, btn_mcp_repeat_a, btn_mcp_repeat_b, btn_mcp_visibility_visible；hidden 按钮为 invisible）
- 布局验证通过
- 综合汇总

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | Button 数量正确（5 个）+ 布局验证通过 + 汇总正确 |
| 4 | Button 数量有轻微偏差（如报 6 个，含 hidden）+ 布局正确 |
| 3 | Button 探索不完整，但布局验证正确 |
| 2 | 只完成了其中一步 |
| 1 | Button 数量完全错误 |
| 0 | 未调用命令 |

---

### TC-I11：跨区域属性对比验证

**级别**：L3
**命令**：eval_view（多次）

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 对比 tv_mcp_style_title（20sp, 红色）和 tv_mcp_body_text（14sp, 深灰色）的字号和颜色差异，用数据说明两者的区别

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_style_title"}, expressions=["getTextSize()", "getCurrentTextColor()"])`
2. 执行 `eval_view(target={resourceId: "tv_mcp_body_text"}, expressions=["getTextSize()", "getCurrentTextColor()"])`
3. 对比报告

**期望输出行为**：
- tv_mcp_style_title：约 20sp，红色（#FF0000）
- tv_mcp_body_text：约 14sp，深灰色（#333333）
- 清晰描述两者差异

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 两次调用均正确 + 对比报告精确 |
| 4 | 两次调用正确但对比描述不清 |
| 3 | 只完成了一个元素的读取 |
| 2 | 使用截图描述颜色差异 |
| 1 | 数据错误 |
| 0 | 未调用命令 |

---

### TC-I12：发现布局问题并给出修复建议

**级别**：L3
**命令**：figma-layout-verify → eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 用错误的 Figma fixture（mcp_test_wrong_spacing.json）验证布局，找到不符合的节点，然后通过 eval_view 读取实际值，并说明需要如何修改代码才能修复

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `figma-layout-verify(figmaJsonPath="docs/skills/benchmark-ui-verify/fixtures/mcp_test_wrong_spacing.json", ...)`
2. 识别偏差节点
3. 执行 `eval_view` 读取对应节点实际属性
4. 给出修复建议

**期望输出行为**：
- 识别出偏差节点
- 提供具体的 dp 数值差异
- 给出有意义的修复建议（如调整 margin/padding）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 四步全部完成 + 偏差识别准确 + 修复建议合理 |
| 4 | 偏差识别准确但修复建议不够具体 |
| 3 | 完成了验证和识别但未读取实际值 |
| 2 | 只完成了 figma-layout-verify 步骤 |
| 1 | 使用了正确 fixture（未触发偏差） |
| 0 | 未调用命令 |

---

### TC-I13：按条件定位 + 验证可点击性 + 状态报告

**级别**：L3
**命令**：ui_find → eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到页面上所有可点击的 Button 元素（可能有多个），验证每个按钮是否真的处于可点击状态，汇总报告

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `ui_find` — 查找所有 Button
2. 对每个找到的 Button 执行 `eval_view(expressions=["isClickable()"])`

**期望输出行为**：
- 找到 5 个按钮（含 invisible 的也算可点击）
- 每个按钮的 isClickable() = true
- 汇总报告均可点击

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 找到所有按钮 + 每个都验证了 isClickable + 汇总正确 |
| 4 | 找到大部分按钮 + 验证了 isClickable + 结论正确 |
| 3 | 只找到部分按钮，但逻辑正确 |
| 2 | 只用 ui_find 读取 clickable 属性，未用 eval_view |
| 1 | 按钮数量或可点击状态判断有根本错误 |
| 0 | 未调用命令 |

---

### TC-I14：完整页面健康检查（综合多命令）

**级别**：L3
**命令**：activity-stack → figma-layout-verify → eval_view（多次）

**前置条件**：
- 设备已连接
- App 已启动

**输入（LLM 收到的指令）**：
> 执行 McpTestActivity 的完整健康检查：
> 1. 确认当前在正确页面
> 2. 验证整体布局符合设计
> 3. 抽查三个关键元素的属性（tv_mcp_style_title 的颜色、view_mcp_bg_block 的尺寸、tv_card_title 的文本）
> 给出最终健康检查报告

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `activity-stack`
2. 执行 `figma-layout-verify(figmaJsonPath="docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json", ...)`
3. 执行 `eval_view(target={resourceId: "tv_mcp_style_title"}, expressions=["getCurrentTextColor()"])`
4. 执行 `eval_view(target={resourceId: "view_mcp_bg_block"}, expressions=["getWidth()", "getHeight()"])`
5. 执行 `eval_view(target={resourceId: "tv_card_title"}, expressions=["getText().toString()"])`

**期望输出行为**：
- 完整的 5 步调用序列
- 汇总健康检查报告

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 五步全部完成 + 报告完整准确 |
| 4 | 完成 4/5 步 + 报告基本完整 |
| 3 | 完成 3/5 步 + 主要结论正确 |
| 2 | 只完成 2 步 |
| 1 | 只完成 1 步 |
| 0 | 未调用命令 |

---

### TC-I15：异步流程：编译后验证布局未变化

**级别**：L3
**命令**：compile_and_deploy → activity-stack → figma-layout-verify

**前置条件**：
- 设备已连接
- 项目代码未修改

**输入（LLM 收到的指令）**：
> 执行一次编译部署，部署完成后确认仍在 McpTestActivity，然后验证布局未因编译而发生变化

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `deploy` — 编译并部署
2. 执行 `activity-stack` — 确认页面状态
3. 执行 `figma-layout-verify(figmaJsonPath="docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json", ...)`

**关键参数**：
- Step 3 `figmaJsonPath` = `"docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json"`

**期望输出行为**：
- 编译部署成功
- 确认在 McpTestActivity
- 布局验证通过，无变化

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 三步全部正确 + 结论正确（无变化） |
| 4 | 三步正确但对"无变化"的表述不够明确 |
| 3 | 跳过了 activity-stack 确认步骤 |
| 2 | 只完成了编译部署，未验证布局 |
| 1 | 编译失败后仍然尝试验证布局 |
| 0 | 未调用命令 |
