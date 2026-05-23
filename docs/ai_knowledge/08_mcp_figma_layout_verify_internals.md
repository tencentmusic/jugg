# figma-layout-verify 内部算法

> 最后核对：2026-05-23
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只解释 `figma-layout-verify` 相关 Kotlin 实现里的 Figma JSON 解析、关系提取、IoU 匹配和容差验证算法。

当前边界：`FigmaLayoutVerifyMcpToolAction` 类存在，但没有注册到 `McpToolActionRegistry.defaultActions()`，因此它不是当前公开 MCP tool。公开工具列表请以 [`08_mcp_tools_list.md`](08_mcp_tools_list.md) 和运行时 `tools/list` 为准。

---

## 2. 核心源码索引

| 类 | 文件 | 作用 |
|----|------|------|
| `FigmaLayoutVerifyMcpToolAction` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/FigmaLayoutVerifyMcpToolAction.kt` | 实验性 action；读取 Figma JSON、内部 dump Android layout、调用 verifier 生成报告 |
| `LayoutDumpHelper` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/LayoutDumpHelper.kt` | 生成 Android layout 内部 JSON，供 verifier 匹配实际节点 |
| `FigmaLayoutVerifier` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/FigmaLayoutVerifier.kt` | 算法编排入口：parse -> extract -> match -> verify |
| `FigmaJsonParser` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/parser/FigmaJsonParser.kt` | 识别 Figma JSON 格式，解析 `FigmaNode` 树 |
| `RelationExtractor` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/extractor/RelationExtractor.kt` | 从 Figma 节点树提取 spacing / alignment 关系 |
| `ElementMatcher` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/matcher/ElementMatcher.kt` | 将 Figma 节点和 Android 节点归一化到 1000x1000 后用 IoU 匹配 |
| `RelationVerifier` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/verifier/RelationVerifier.kt` | 用固定容差验证间距和对齐 |
| `FigmaNode` / `AndroidNode` / `Relation` / `VerifyResult` | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/model/*` | 算法数据模型 |

---

## 3. 核心数据流

```text
FigmaLayoutVerifyMcpToolAction.execute()
  -> 校验 figmaJsonPath，读取 dpr（默认 1.0）
  -> LayoutDumpHelper.dumpInternal()
       产出 Android layout 内部 JSON；失败时直接返回 dump 的错误结果
  -> FigmaJsonParser.validate()
       只校验根节点格式，非法时返回 INVALID_FIGMA_FORMAT
  -> FigmaLayoutVerifier.verify()
       parse Figma JSON
       extract spacing/alignment relations
       为每条 relation 的端点做 IoU 匹配
       用 Android dp bounds 验证实际关系
  -> structuredContent.data.results
```

App 侧 `jvmti_agent/.../LayoutVerifier.java` 属于旧 `layout-verify` / ViewHierarchy server 方向；`figma-layout-verify` 的关系提取与验证在 IDE 侧 Kotlin 实现内完成。

---

## 4. Figma JSON 解析

`FigmaJsonParser.parse()` 支持三种输入格式：

| 格式 | 判断条件 | 根节点 |
|------|----------|--------|
| Direct node | `json.has("id") && (json.has("layout") || json.has("bounds"))` | JSON 本身 |
| Nodes wrapper | `json.has("nodes")` | `nodes.entrySet().first().value` |
| Document wrapper | `json.has("document")` | `document.children[0]` |

bounds 规则：

| 字段 | 输入含义 | 解析结果 |
|------|----------|----------|
| `layout` | `[x, y, width, height]` | `[x, y, x + width, y + height]` |
| `bounds` | `[left, top, right, bottom]` | 原样使用 |

`flattenNodes()` 用先序 DFS 展平节点树，保留容器节点和叶子节点。后续 spacing 只扫描展平列表中相邻下标对，因此 Figma 层级顺序会直接影响关系覆盖面。

---

## 5. 关系提取

`RelationExtractor` 在 Figma 像素空间里判断关系，输出的 spacing expected 值再除以 `dpr` 转成 dp。

### 5.1 spacing

只检查展平列表中的相邻节点 `(nodes[i], nodes[i + 1])`。

水平相邻：

```text
tolerance = (20 * dpr).toInt()
abs(node1.top - node2.top) < tolerance
AND node2.left >= node1.right
expected = ((node2.left - node1.right) / dpr).toInt()
axis = "x"
```

垂直相邻：

```text
tolerance = (20 * dpr).toInt()
abs(node1.left - node2.left) < tolerance
AND node2.top >= node1.bottom
expected = ((node2.top - node1.bottom) / dpr).toInt()
axis = "y"
```

实现使用 `toInt()` 截断小数；不是四舍五入。

### 5.2 alignment

按 top / left 坐标分桶，bucket 内至少 2 个节点时产生一条 alignment relation。

```text
tolerance = (5 * dpr).toInt()
yBucket = (top / tolerance) * tolerance
xBucket = (left / tolerance) * tolerance
```

| 分桶依据 | axis | 验证含义 |
|----------|------|----------|
| `top` | `y` | 多个节点的 centerY 是否对齐 |
| `left` | `x` | 多个节点的 centerX 是否对齐 |

注意：分桶按 top/left 归组，但验证按中心点判断。这可以减少简单尺寸差异影响，也可能让“顶部接近但中心差异大”的节点在验证阶段失败。

---

## 6. 元素匹配

`ElementMatcher` 不看 name、text、resourceId，只看 bounds 的相对位置和尺寸。

```text
normalized.left   = bounds.left   / screenWidth  * 1000
normalized.top    = bounds.top    / screenHeight * 1000
normalized.right  = bounds.right  / screenWidth  * 1000
normalized.bottom = bounds.bottom / screenHeight * 1000
```

screen size 来源：

| 侧 | 来源 | 单位 |
|----|------|------|
| Figma | 根节点 `layout[2], layout[3]` 或 `bounds[2], bounds[3]` | Figma px |
| Android | `layout-dump` 内部 JSON 的 `deviceInfo.screenWidth/screenHeight` | dp |

IoU 匹配规则：

```text
iou = intersectArea / (area1 + area2 - intersectArea)
match if iou > 0.7
```

取 IoU 最高的 Android 节点作为 matched，最多保留 3 个 alternatives。

---

## 7. 关系验证

`AndroidNode.bounds` 已经是 dp，来自 `layout-dump` 在 IDE 侧的 px -> dp 转换。

### 7.1 spacing

实际值：

```text
axis=x: actual = element2.left - element1.right
axis=y: actual = element2.top  - element1.bottom
diff = actual - expected
```

通过条件：

```text
abs(diff) <= 2
OR abs(diff) / expected <= 0.05
```

与直觉不同的实现细节：

- `expected == 0` 时百分比差异为 0，只要不触发绝对容差也可能被百分比条件放过；这是当前代码行为。
- `expected < 0` 时百分比差异为负数，也会满足 `<= 0.05`；重叠关系因此可能被过度放宽。

### 7.2 alignment

```text
axis=x: centerX = (left + right) / 2
axis=y: centerY = (top + bottom) / 2
maxDiff = max(center) - min(center)
pass if maxDiff <= 2
```

---

## 8. 单位流转

| 阶段 | Figma 侧 | Android 侧 |
|------|----------|------------|
| JSON 解析后 | Figma px | dp |
| spacing expected | Figma px / dpr -> dp | - |
| IoU 匹配 | Figma px / 画布尺寸 -> 1000 空间 | dp / 屏幕 dp -> 1000 空间 |
| 关系验证 | expected dp | actual dp |

---

## 9. 隐形约束与局限

| 约束 / 局限 | 影响 |
|-------------|------|
| action 未注册到 `defaultActions()` | 不应在公开 MCP/CLI 文档中承诺可直接调用 `figma-layout-verify` |
| spacing 只看 DFS 展平后的相邻节点 | 会遗漏非相邻但视觉上相关的间距 |
| alignment 先按 top/left 分桶，再按中心点验证 | 可能提取出最终会失败的 alignment |
| IoU 阈值固定为 `> 0.7` | 重叠容器、FrameLayout、相似尺寸节点可能误匹配 |
| 匹配不看语义信息 | 元素命名、文本、resourceId 不参与匹配 |
| 不验证颜色、字号、圆角 | 这些属性应使用公开的 `view-inspect`；位置和尺寸用 `view-locate` |
| spacing 百分比容差使用 `expected` 原值作分母 | `expected <= 0` 时结果不符合通常的百分比容差直觉 |

---

## 10. 排查入口

| 现象 | 优先入口 |
|------|----------|
| 工具无法通过 MCP 调用 | `McpToolActionRegistry.defaultActions()`，确认是否注册 |
| Figma JSON 被判非法 | `FigmaJsonParser.validate()` |
| 间距关系缺失 | `RelationExtractor.extractSpacingRelations()` 与 Figma 展平顺序 |
| 对齐关系过多 | `RelationExtractor.extractAlignmentRelations()` 的 `5 * dpr` bucket |
| 元素匹配到错误 Android View | `ElementMatcher.match()` 的 normalized bounds 与 IoU 分数 |
| spacing diff 看起来不合理 | `RelationVerifier.verifySpacing()` 的 dp actual/expected 与 `dpr` |

---

## 11. 关联文档

- MCP 设计说明：`08_mcp_design.md`
- MCP 工具参数清单：`08_mcp_tools_list.md`
- UI 布局验证设计：`08_mcp_layout_verify_design.md`
- UI 验证检查清单：`08_mcp_ui_verify_checklist.md`
- 代码路径速查：`98_code_map.md`
