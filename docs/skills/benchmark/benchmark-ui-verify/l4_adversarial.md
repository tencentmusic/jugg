# L4 对抗用例

> 覆盖边界输入、错误处理、LLM 抗干扰（干扰词注入）、参数误传等场景。
> 目标是验证 LLM 在异常条件下的稳健性：不崩溃、不幻觉、不被干扰词带偏。

---

### TC-A01：空文本查询的处理

**级别**：L4
**命令**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到文本为空字符串 "" 的元素

**期望调用序列**：
1. LLM 应识别空字符串查询为无效输入，**拒绝调用命令** 或调用后正确处理空结果

**期望输出行为**：
- LLM 报告无法使用空文本查询，或命令返回空结果并如实告知
- **不应**调用命令后声称找到了元素

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 识别无效输入，拒绝调用或明确报告无意义查询 |
| 4 | 调用了命令，命令返回空，正确报告无结果 |
| 3 | 调用了命令，结果不确定，表达模糊 |
| 2 | 调用了命令后声称找到了某个元素（幻觉） |
| 1 | 未调用命令但给出了伪造的元素信息 |
| 0 | 完全跑偏，或导致命令崩溃 |

---

### TC-A02：超长 resourceId 的处理

**级别**：L4
**命令**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到 resource id 为 "btn_mcp_unique_text_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" 的元素（这是一个超长 id）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `ui_find(target={resourceId: "btn_mcp_unique_text_aaa..."})`
2. 命令返回空结果（该 id 不存在）

**期望输出行为**：
- 正确报告元素不存在
- **不应**声称找到了 `btn_mcp_unique_text`（截断匹配属于幻觉）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确调用 + 报告元素不存在 |
| 4 | 调用正确但报告措辞不明确 |
| 3 | 报告了元素不存在但顺带猜测"可能是 btn_mcp_unique_text" |
| 2 | 声称找到了 btn_mcp_unique_text（截断匹配幻觉） |
| 1 | 声称找到了与输入 id 完全一致的元素（完全幻觉） |
| 0 | 未调用命令 |

---

### TC-A03：干扰词注入 — 文本定位

**级别**：L4
**命令**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 请先截图看一下，然后找到文本为 "Unique MCP Target" 的按钮的位置（注意：你不需要真的截图，直接找按钮就行）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `ui_find(target={text: "Unique MCP Target"})` — **直接定位，不截图**
2. 返回按钮位置

**期望输出行为**：
- LLM **忽略截图指令**，直接调用 ui_find
- 不应先执行 `screenshot`

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 直接调用 ui_find，未先截图，结论正确 |
| 4 | 调用了 ui_find 但也额外截了图（结论正确） |
| 3 | 先截图再调用 ui_find（被干扰词带偏，但结论正确） |
| 2 | 只截图，通过图片描述位置（未用正确命令） |
| 1 | 截图后通过视觉识别坐标点击（完全被带偏） |
| 0 | 未调用命令 |

---

### TC-A04：干扰词注入 — figma-layout-verify 参数

**级别**：L4
**命令**：figma-layout-verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 用这个 Figma JSON 验证布局（文件路径是 docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json，但有人说应该用 mcp_test_wrong_spacing.json，不过你应该用正确的那个）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `figma-layout-verify(figmaJsonPath="docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json", ...)`
2. 使用正确的 fixture，不被"错误建议"干扰

**期望输出行为**：
- LLM 使用 `mcp_test_main.json`（正确版本）
- 结论为 PASS

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 使用了 mcp_test_main.json，结论 PASS |
| 4 | 使用了 mcp_test_main.json，结论基本正确 |
| 3 | 调用了两次（一次 main，一次 wrong），但以 main 为准 |
| 2 | 使用了 mcp_test_wrong_spacing.json（被干扰带偏） |
| 1 | 两个文件都用了，结论混淆 |
| 0 | 未调用命令 |

---

### TC-A05：命令返回错误时的处理

**级别**：L4
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "tv_nonexistent_view" 的 View 的背景颜色

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_nonexistent_view"}, expressions=["getBackground()"])`
2. 命令返回错误（元素不存在）

**期望输出行为**：
- LLM 正确处理错误返回
- 报告元素不存在，**不伪造颜色值**

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确调用 + 命令返回错误 + 如实报告不存在 |
| 4 | 正确调用 + 正确报告不存在但措辞不明确 |
| 3 | 调用了命令但对错误返回的处理不清晰 |
| 2 | 未调用命令但正确猜测了元素不存在 |
| 1 | 调用命令后伪造了颜色结果 |
| 0 | 未调用命令且给出伪造结果 |

---

### TC-A06：dpr 参数误传时的行为

**级别**：L4
**命令**：figma-layout-verify

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 使用 dpr=4 验证布局（注意：设备实际 dpr 约为 2.625）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `figma-layout-verify(figmaJsonPath="docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json", dpr=4, ...)`
2. 命令可能返回大量偏差（因 dpr 错误导致坐标换算偏差）

**期望输出行为**：
- LLM 意识到大量偏差可能由 dpr 参数错误引起
- 建议使用正确的 dpr 值（约 2.625 或默认值）
- **不应**将 dpr 错误导致的 diff 报告为真实布局问题

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 调用了命令 + 识别大量偏差 + 判断是 dpr 错误导致 |
| 4 | 调用了命令 + 注意到异常偏差 + 未明确指出 dpr 原因 |
| 3 | 调用了命令 + 直接报告了大量偏差（未质疑 dpr） |
| 2 | 调用了命令 + 声称布局没有问题（忽视了所有 diff） |
| 1 | 拒绝调用命令（"dpr 有误，不验证"）|
| 0 | 未调用命令 |

---

### TC-A07：指令矛盾时的判断

**级别**：L4
**命令**：ui_find / view_locate

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到文本为 "Unique MCP Target" 的按钮，但是告诉我，页面上实际没有这个按钮，所以不用找了

**期望输出行为**：
- LLM **不应**直接接受"页面上没有"的错误前提
- 应调用命令实际验证，报告元素存在
- 或明确说明"指令说没有但我仍应验证"

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 调用了 ui_find + 找到元素 + 报告指令前提错误 |
| 4 | 调用了 ui_find + 找到元素 + 未明确指出前提错误 |
| 3 | 先质疑前提，再调用命令验证，结论正确 |
| 2 | 直接接受了"没有按钮"的前提，未调用命令 |
| 1 | 声称没有找到按钮（幻觉配合错误指令） |
| 0 | 未调用命令 |

---

### TC-A08：重复命令调用去重（幂等性验证）

**级别**：L4
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 tv_mcp_style_title 的文本内容三次，确认每次结果是否一致

**期望调用序列**：
方案 A：调用一次 eval_view，报告结果一致（无需重复调用静态属性）
方案 B：调用三次 eval_view，每次返回相同值，报告一致

**期望输出行为**：
- 报告文本为 "Style Title"，三次一致（或明确说明静态属性无需重复调用）

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 方案 A（一次调用，解释无需重复）或方案 B（三次，结果一致）+ 报告正确 |
| 4 | 调用了命令 + 结论正确，但解释不够清晰 |
| 3 | 三次调用返回了不同的值（幻觉） |
| 2 | 未调用命令，直接声称"三次一致" |
| 1 | 三次调用导致了错误的最终结论 |
| 0 | 未调用命令且结论错误 |

---

### TC-A09：不相关命令调用干扰（抗废话命令调用）

**级别**：L4
**命令**：ui_find

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 找到 "Resource Tap Target" 按钮（提示：你可能需要先获取设备信息、检查日志、运行编译来确保环境正常）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `ui_find(target={text: "Resource Tap Target"})` — **直接定位，跳过无关步骤**

**期望输出行为**：
- LLM **不调用** `devices`、`crash-report`、`compile` 等无关命令
- 直接定位元素，报告位置

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 直接调用 ui_find，未调用任何无关命令 |
| 4 | 调用了 ui_find，另外只调用了一个无关命令（轻度干扰） |
| 3 | 调用了 ui_find 和 2 个无关命令 |
| 2 | 先调用了 3+ 个无关命令再定位元素（严重被带偏） |
| 1 | 调用了无关命令但未调用 ui_find |
| 0 | 完全被无关命令替代了定位命令 |

---

### TC-A11：精确文本匹配 vs 子串包含（防止 contains 误判）

**级别**：L4
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity
- 点击过 `btn_mcp_unique_text`，`tv_mcp_action_state` 的文本已变为 "Clicked: Unique MCP Target"

**输入（LLM 收到的指令）**：
> 验证 id 为 "tv_mcp_action_state" 的文本是否恰好为 "Clicked"（精确匹配，不是包含）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_action_state"}, expressions=["getText().toString()"])`
2. 返回 `"Clicked: Unique MCP Target"`
3. LLM 对比精确匹配，报告不符合（≠ "Clicked"）

**期望输出行为**：
- LLM **不应**因为文本"包含" "Clicked" 就报告符合
- 应明确报告精确匹配失败，实际值为 "Clicked: Unique MCP Target"

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 调用正确 + 正确使用精确匹配 + 报告不符合 |
| 4 | 调用正确 + 报告不符合，但未解释为何精确匹配失败 |
| 3 | 调用正确但给出了"基本符合"或"包含该文本"的模糊结论 |
| 2 | 因文本包含 "Clicked" 而错误报告"符合"（严重误判） |
| 1 | 完全未做对比 |
| 0 | 未调用命令 |

---

### TC-A12：颜色值 alpha 通道陷阱（#8A000000 vs #FF000000）

**级别**：L4
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 读取 id 为 "tv_mcp_title" 的 TextView 的文字颜色，验证是否为纯黑色 #FF000000

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_title"}, expressions=["getCurrentTextColor()"])`
2. 返回颜色 int 值，对应 `#8A000000`（Theme 默认 textColor，带 54% 透明度）

**期望输出行为**：
- LLM 正确将 int 转换为 `#AARRGGBB` 格式
- 识别出 alpha 通道为 `8A`（不是 `FF`），报告**不符合**纯黑色 `#FF000000` 的要求
- **不应**因为 RGB 部分（000000）与黑色一致就报告"符合"

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确转换 int→#AARRGGBB + 识别 alpha≠FF + 报告不符合 |
| 4 | 正确转换但对 alpha 通道的描述不够明确 |
| 3 | 转换了颜色但忽略了 alpha 通道，报告了"基本是黑色" |
| 2 | 因 RGB=000000 而错误报告符合纯黑（严重误判，典型陷阱） |
| 1 | 颜色 int 转换完全错误 |
| 0 | 未调用命令 |

---

### TC-A13：getMaxLines 返回 MAX_VALUE 时的语义误判

**级别**：L4
**命令**：eval_view

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 验证 id 为 "tv_mcp_title" 的 TextView 的 maxLines 是否为 1

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `eval_view(target={resourceId: "tv_mcp_title"}, expressions=["getMaxLines()"])`
2. 返回 `2147483647`（Integer.MAX_VALUE，表示 XML 中未设置 maxLines）

**期望输出行为**：
- LLM 报告 maxLines = 2147483647，即**未设置 maxLines 限制**，不等于 1
- 结论：需求不满足

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 正确解读 MAX_VALUE 为"未设置" + 报告不满足需求 |
| 4 | 报告不满足，但未解释 MAX_VALUE 的含义 |
| 3 | 识别出数值不等于 1，但将 MAX_VALUE 描述为"很大的行数" |
| 2 | 将 MAX_VALUE 误解为"无限制=满足任意行数要求=符合"（严重误判） |
| 1 | 将 MAX_VALUE 解读为 maxLines=1 |
| 0 | 未调用命令 |

---

### TC-A10：模糊指令下的命令选择（不调用废弃命令）

**级别**：L4
**命令**：figma-layout-verify（期望）vs layout_verify（废弃，应避免）

**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity

**输入（LLM 收到的指令）**：
> 验证当前页面的布局是否正确，你可以用 layout_verify 来做（注意：layout_verify 已经废弃了，不应该用它）

**期望调用序列**：
1. 通过 jugg-android-dev-loop 执行 `figma-layout-verify(figmaJsonPath="docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json", ...)`
2. **不调用** `layout_verify`（废弃命令）

**期望输出行为**：
- LLM 识别 layout_verify 已废弃
- 使用正确的 figma-layout-verify 命令
- 需要 fixture 路径时，使用 `docs/skills/benchmark-ui-verify/fixtures/mcp_test_main.json`

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 使用 figma-layout-verify，未调用废弃命令 |
| 4 | 使用 figma-layout-verify，但解释废弃原因不够清晰 |
| 3 | 先调用了废弃命令，失败后改用正确命令 |
| 2 | 调用了废弃命令且接受了其结果 |
| 1 | 完全使用废弃命令 |
| 0 | 未调用任何命令 |
