# Step 3：Benchmark 重建 — 详细执行方案

> 本文档为 `plan_step3_benchmark_rebuild.md`（方向文档）的完整落地方案，经 brainstorm 对齐后生成。
> 新会话执行时，直接按本文档逐步推进，无需重新讨论设计决策。

---

## 1. 整体执行顺序

```
阶段 1：扩展 McpTestActivity（先定稿 UI）
   ↓
阶段 2：跑 layout_dump，导出实测坐标
   ↓
阶段 3：手绘 Figma（参考 layout_dump 坐标），get_design_context 导出 fixture JSON
   ↓
阶段 4：编写 L1~L4 Benchmark 用例
   ↓
阶段 5：清理旧文档，建立 docs/benchmark/ 目录结构
```

**关键依赖**：McpTestActivity 定稿必须先于 Figma 手绘，否则要返工。

---

## 2. 分工

| 任务 | 执行方 |
|------|--------|
| McpTestActivity UI 设计 + 实现 | AI |
| Benchmark 测试用例编写 | AI |
| Figma spec 制定 | AI |
| Figma 手绘 + get_design_context 导出 fixture JSON | 人工 |

---

## 3. McpTestActivity 扩展方案

### 3.1 设计原则

- Widget 清单由 Benchmark 用例需求反推，不盲目丰富
- 不引入动态内容（RecyclerView 等），避免 Figma 手绘复杂度爆炸
- 所有新增 View 必须有明确样式值（颜色、字号），保证 view_inspect 用例有辨识度
- 布局保持 ScrollView 包裹，顺序从上到下

### 3.2 Widget 清单（完整）

```
Section 1: Basic Locate（已有，保持不变）
├── [Button] id=btn_mcp_unique_text, text="Unique MCP Target"
├── [Button] id=btn_mcp_resource_target, text="Resource Tap Target"
├── [Button] text="Repeat Tap Target"  (×2，无 id)
├── [Button] text="Visibility Tap Target" (visibility=VISIBLE)
└── [Button] text="Visibility Tap Target" (visibility=GONE)

Section 2: Style Inspect（新增）
├── [TextView] id=tv_mcp_style_title
│   text="Style Title"
│   textColor=#FF0000
│   textSize=20sp
├── [View] id=view_mcp_bg_block
│   width=120dp, height=48dp
│   backgroundColor=#0000FF
└── [TextView] id=tv_mcp_body_text
    text="Body Text Sample"
    textColor=#333333
    textSize=14sp

Section 3: Card Layout（新增，用于 figma_layout_verify spacing 验证）
└── [CardView] id=card_mcp_container
    margin: 16dp（四边）
    padding: 12dp（内边距）
    ├── [TextView] id=tv_card_title
    │   text="Card Title"
    │   marginBottom=8dp
    └── [TextView] id=tv_card_desc
        text="Card Description"

Section 4: Nested Inspect（新增，用于 view_inspect 链式 getter）
└── [LinearLayout] id=ll_mcp_parent (orientation=horizontal)
    ├── [ImageView] id=iv_mcp_icon
    │   width=32dp, height=32dp
    └── [TextView] id=tv_mcp_label
        text="Nested Label"
        paddingLeft=8dp

Section 5: Swipe（已有，保持不变）
└── [ScrollView] id=sv_mcp_swipe_target
    ├── [TextView] text="Swipe Start Marker"
    └── [TextView] text="Swipe End Marker"
```

### 3.3 实现规范

- 所有硬编码颜色值写入 `colors.xml`，命名 `mcp_*`
- 所有尺寸值写入 `dimens.xml`，命名 `mcp_*`
- 布局文件：`activity_mcp_test.xml`（如已存在则直接修改）

---

## 4. Figma 手绘规范（Figma Spec）

### 4.1 画布设置

- **Frame 尺寸**：`411 × 813`（实测设备逻辑分辨率，dpr=1，1pt = 1dp；content area 不含 status bar 54dp + action bar 56dp）
- **Frame 命名**：`McpTestActivity`

### 4.2 节点坐标表

> 坐标为 Figma 内坐标（单位 pt = dp），以 content area 左上角为原点（即 layout_dump 绝对坐标 y - 110）。
> 已根据 layout_dump 实测更新（2026-04-06）。

| Figma layer name | x | y | w | h | 样式备注 |
|-----|---|---|---|---|------|
| `btn_mcp_unique_text` | 16 | 258 | 379 | 48 | — |
| `btn_mcp_resource_target` | 16 | 318 | 379 | 48 | — |
| `Repeat Tap Target` | 16 | 379 | 379 | 48 | 第一个（无 id，全宽） |
| `Repeat Tap Target` | 16 | 439 | 379 | 48 | 第二个（无 id，全宽） |
| `tv_mcp_style_title` | 16 | 615 | 87 | 27 | fill=#FF0000，fontSize=20 |
| `view_mcp_bg_block` | 16 | 650 | 120 | 48 | fill=#0000FF |
| `tv_mcp_body_text` | 16 | 706 | 379 | 20 | fill=#333333，fontSize=14 |
| `card_mcp_container` | 32 | 742 | 347 | 71 | margin=16dp，CardView |
| `tv_card_title` | 44 | 754 | 323 | 19 | card 内部节点，padding=12 |
| `tv_card_desc` | 44 | 781 | 323 | 20 | card 内部节点 |
| `ll_mcp_parent` | 16 | 837 | 379 | 32 | horizontal LinearLayout |
| `iv_mcp_icon` | 16 | 837 | 32 | 32 | — |
| `tv_mcp_label` | 48 | 843 | 90 | 19 | paddingLeft=8 |

### 4.3 命名约定（重要）

- Figma layer name 必须与 Android `android:id`（去掉 `@+id/` 前缀）完全一致
- 无 resourceId 的节点，layer name 使用 `android:text` 的完整文本
- `figma_layout_verify` 用 layer name 做节点匹配键，命名不一致会导致匹配失败

### 4.4 导出 Fixture

手绘完成后：
1. 获取节点的 Figma URL（含 `node-id` 参数）
2. 调用 `get_design_context` 工具，传入该 URL
3. 将输出 JSON 保存至：
   - `docs/benchmark/fixtures/mcp_test_main.json`（正确版本）
4. 再创建一个故意偏差版本（调整 2~3 个 spacing/margin 值），保存至：
   - `docs/benchmark/fixtures/mcp_test_wrong_spacing.json`（负例版本，用于 L2/L4 负例用例）

---

## 5. Benchmark 文件结构

```
docs/benchmark/
├── README.md                          # 执行说明 + 评分说明
├── fixtures/                          # 预制 Figma JSON（手绘导出后填入）
│   ├── mcp_test_main.json
│   └── mcp_test_wrong_spacing.json
├── l1_smoke.md
├── l2_view_locate.md
├── l2_figma_layout_verify.md
├── l2_view_inspect.md
├── l3_integration.md
└── l4_adversarial.md
```

旧文件处理：删除 `docs/ai_knowledge/08_mcp_test_case.md`。

---

## 6. 用例格式规范（Prescriptive）

每条用例必须包含以下所有字段：

```markdown
### TC-XXX：用例标题

**级别**：L1 / L2 / L3 / L4
**工具**：view_locate / figma_layout_verify / view_inspect（可多个）
**前置条件**：
- 设备已连接
- 当前页面：McpTestActivity（通过 activity_stack 确认）

**输入（LLM 收到的指令）**：
> 找到文本为 "Unique MCP Target" 的按钮

**期望调用序列**：
1. `view_locate(text="Unique MCP Target")`
2. 返回元素位置与大小

**关键参数**（必须精确匹配）：
- `text` = "Unique MCP Target"

**宽松参数**（允许偏差）：
- `className` 可缺省

**期望输出行为**：
- 工具返回成功，包含 bounds 信息
- LLM 正确报告元素位置

**评分 Rubric（满分 5 分）**：
| 分 | 判定标准 |
|----|---------|
| 5 | 调用序列完全正确 + 关键参数正确 + 结论正确 |
| 4 | 调用序列正确，参数轻微偏差（宽松参数缺失） + 结论正确 |
| 3 | 调用了正确工具但顺序/次数有偏差，结论基本正确 |
| 2 | 调用了非预期工具但最终结论凑对 |
| 1 | 工具调用方向性错误（如调用已废弃的 layout_verify） |
| 0 | 未调用任何工具，或完全跑偏 |
```

---

## 7. 评分 Rubric 通用标准

所有用例共用以下评分模型（在各用例中可按场景微调）：

| 分数 | 判定标准 |
|------|---------|
| 5 | 调用序列完全正确 + 关键参数正确 + 结论正确 |
| 4 | 调用序列正确，宽松参数有偏差（多余/缺失可选参数）+ 结论正确 |
| 3 | 调用了正确工具，但顺序/次数有偏差，结论基本正确 |
| 2 | 调用了非预期工具（如 layout_dump 代替 view_locate），但结论凑对 |
| 1 | 工具调用方向性错误（调用已废弃工具，或关键参数完全错误） |
| 0 | 未调用任何工具，或崩溃，或完全跑偏 |

---

## 8. 覆盖维度与用例数规划

| 级别 | 文件 | 用例数 | 覆盖点 |
|------|------|--------|--------|
| L1 Smoke | `l1_smoke.md` | ~5 | 三工具各通一次，基本返回正确 |
| L2 Unit | `l2_view_locate.md` | ~10 | 文本匹配、resourceId 匹配、多候选歧义、不存在元素、深层嵌套 |
| L2 Unit | `l2_figma_layout_verify.md` | ~10 | 正常验证(PASS)、错误 fixture 检测(FAIL+diff)、dpr 不匹配告警、部分节点无法匹配 |
| L2 Unit | `l2_view_inspect.md` | ~10 | 基础 getter(text/bounds)、样式 getter(textColor/textSizeSp/backgroundColor)、链式表达式、paddingLeft |
| L3 Integration | `l3_integration.md` | ~15 | 页面导航 Gate + 验证 + 结果判定完整流程；多工具组合 |
| L4 Adversarial | `l4_adversarial.md` | ~10 | 边界输入（空文本、超长 id）、错误处理（工具返回 error）、LLM 抗干扰（干扰词注入）、dpr 误传 |

总计：~60 条

---

## 9. figma_layout_verify 负例策略

**不使用破坏脚本**，改用「错误 Figma fixture」实现负例。

原因：
- 破坏脚本会造成测试状态污染，L3 集成测试互相干扰
- Benchmark 目标是「量化 LLM 执行成功率」，不是测工具检测能力
- LLM 需要正确解读工具返回的 diff 信息，与 diff 如何触发无关

负例用例期望行为：
- LLM 调用 `figma_layout_verify(figmaJsonPath="fixtures/mcp_test_wrong_spacing.json")`
- 工具返回包含 spacing diff 的失败报告
- LLM 正确识别并报告哪些节点不符合设计

---

## 10. 阶段 2：layout_dump 坐标确认步骤

McpTestActivity 实现完成后，执行以下操作更新 Figma Spec：

1. 确保设备已连接，App 已启动并停留在 McpTestActivity
2. 调用 `layout_dump`（内部工具，agent 可直接调用）
3. 将实测 bounds 与 §4.2 的初始坐标表对比
4. 若偏差 > 2dp，更新 §4.2 坐标表，并通知人工按更新后的 spec 手绘

---

## 11. 注意事项

- `layout_dump` 是内部工具，Benchmark 用例中不得出现 LLM 直接调用 `layout_dump` 的期望序列
- 所有用例中的 `figmaJsonPath` 使用相对路径，基准为项目根目录
- L3 集成用例中，`restart_app` 仅作 Gate（不计分），Gate 失败则跳过整条用例
- `view_inspect` 的 `expressions` 字段测试时使用真实 Android SDK getter 方法名（如 `getText().toString()`，`getCurrentTextColor()`）
