# UI 布局验证工具设计方案 - 问题澄清

> 创建时间: 2026-03-12
> 基于: ui_fuzzy_match_design_v3.md
> 目的: 澄清设计中的 5 个关键问题

---

## 问题 1: ui_find target=bounds 的使用场景

### 问题描述
`target=bounds` 似乎只在 MCP 内部（figma_layout_verify）有用，Agent 独立调用的场景很少。

### 分析

**Agent 的困境**:
```
Agent 不知道具体坐标
用户说: "屏幕左上角的按钮"
Agent 无法转换为: bounds=[0, 0, 100, 100]
```

**实际使用场景**:
- ✅ MCP 内部: `figma_layout_verify` 用 bounds 匹配 Figma 节点
- ❌ Agent 独立调用: 几乎没有场景

### 解决方案

**调整 ui_find 接口**:
```typescript
interface UiFindParams {
  projectDir: string;

  // Agent 使用: 仅文本查询
  target: {
    text: string;  // 必填
  };

  // 内部使用: 位置查询 (不对外暴露)
  _internalBounds?: [number, number, number, number];

  returnMode?: "best" | "all";
}
```

**或者更简单**: 拆分成两个工具
```typescript
// 对外: Agent 使用
ui_find(text: string)

// 内部: figma_layout_verify 使用
_findByBounds(bounds: IntArray)  // 私有方法
```

**推荐**: 保留 `target=bounds`，但在文档中标注为**内部使用**

---

## 问题 2: Bounds 归一化的歧义

### 问题描述
Figma 可能已经归一化过一次，用户再告诉我们 dpr=2，我们内部再归一化，导致错误。

### 场景示例

**场景 A: Figma 未归一化**
```
Figma 原始数据: bounds=[32, 556, 174, 648]  (2x 设计稿)
用户提供: dpr=2
MCP 归一化: [32/2, 556/2, 174/2, 648/2] = [16, 278, 87, 324]  ✅ 正确
```

**场景 B: Figma 已归一化**
```
Figma 已处理: bounds=[16, 278, 87, 324]  (已经是 dp)
用户提供: dpr=2
MCP 归一化: [16/2, 278/2, 87/2, 324/2] = [8, 139, 43.5, 162]  ❌ 错误
```

### 解决方案

**简化的 dpr 说明**（Agent 友好版本）:
```
dpr 参数说明:
- 如果 Figma 单位是像素 → 填入设计稿倍率（通常是 2）
- 如果 Figma 已经是 dp 单位 → 填 1

示例:
- Figma 画板 750x1624 → dpr=2
- Figma 画板 375x812 → dpr=1
```

**MCP ��部处理**:
```kotlin
// MCP 内部归一化逻辑
fun normalizeFigmaBounds(figmaBounds: IntArray, dpr: Float): IntArray {
    return figmaBounds.map { px -> (px / dpr).toInt() }.toIntArray()
}

// 如果 Figma 是像素: 750px / 2 = 375dp
// 如果 Figma 是 dp: 375dp / 1 = 375dp
```

**参数验证**（防止重复归一化）:
```kotlin
fun validateDpr(figmaJson: FigmaNode, dpr: Int): ValidationResult {
    val screenWidth = figmaJson.layout[2]

    // 常见屏幕宽度
    val commonWidths = mapOf(
        375 to "1x (dp)",
        750 to "2x (px)",
        1125 to "3x (px)",
        411 to "Android (dp)"
    )

    if (screenWidth in commonWidths) {
        val type = commonWidths[screenWidth]
        return ValidationResult.ok("检测到 Figma 画板宽度 ${screenWidth}px ($type)")
    }

    return ValidationResult.warning(
        "Figma 画板宽度 ${screenWidth}px 不常见，请确认 dpr=$dpr 是否正确"
    )
}
```

**参数验证**:
```kotlin
fun validateDpr(figmaJson: FigmaNode, dpr: Int): ValidationResult {
    val screenWidth = figmaJson.layout[2]  // 假设根节点是屏幕

    // 常见屏幕宽度: 375, 750, 1125, 411, 360
    val expectedWidths = listOf(375, 750, 1125, 411, 822, 360, 720, 1080)

    if (screenWidth !in expectedWidths) {
        return ValidationResult.warning(
            "Figma 画板宽度 ${screenWidth}px 不常见，请确认 dpr=$dpr 是否正确"
        )
    }

    return ValidationResult.ok()
}
```

---

## 问题 3: 提取相对关系是关键

### 问题描述
提取相对关系是核心逻辑，代码可能不少，需要独立模块管理。

### 模块设计

**独立模块**: `RelationExtractor`

```kotlin
package com.tencent.jugg.mcp.layout

/**
 * 从 Figma 设计稿中提取相对关系
 */
class RelationExtractor(
    private val dpr: Float
) {
    /**
     * 提取所有相对关系
     */
    fun extractRelations(figmaJson: FigmaNode): List<Relation> {
        val relations = mutableListOf<Relation>()
        val nodes = figmaJson.flattenNodes()

        // 1. 提取间距关系
        relations.addAll(extractSpacingRelations(nodes))

        // 2. 提取对齐关系
        relations.addAll(extractAlignmentRelations(nodes))

        // 3. 提取尺寸比例关系 (可选)
        // relations.addAll(extractSizeRatioRelations(nodes))

        return relations
    }

    /**
     * 提取间距关系
     */
    private fun extractSpacingRelations(nodes: List<FigmaNode>): List<SpacingRelation> {
        val relations = mutableListOf<SpacingRelation>()

        for (i in 0 until nodes.size - 1) {
            val node1 = nodes[i]
            val node2 = nodes[i + 1]

            // 水平相邻
            if (isHorizontallyAdjacent(node1, node2)) {
                relations.add(SpacingRelation(
                    element1 = node1.id,
                    element2 = node2.id,
                    axis = "x",
                    expected = ((node2.bounds[0] - node1.bounds[2]) / dpr).toInt()
                ))
            }

            // 垂直相邻
            if (isVerticallyAdjacent(node1, node2)) {
                relations.add(SpacingRelation(
                    element1 = node1.id,
                    element2 = node2.id,
                    axis = "y",
                    expected = ((node2.bounds[1] - node1.bounds[3]) / dpr).toInt()
                ))
            }
        }

        return relations
    }

    /**
     * 提取对齐关系
     */
    private fun extractAlignmentRelations(nodes: List<FigmaNode>): List<AlignmentRelation> {
        val relations = mutableListOf<AlignmentRelation>()
        val tolerance = 5  // dp

        // 按 Y 坐标分组 (水平对齐)
        val yGroups = nodes.groupBy { (it.bounds[1] / dpr / tolerance).toInt() * tolerance }
        for (group in yGroups.values) {
            if (group.size > 1) {
                relations.add(AlignmentRelation(
                    elements = group.map { it.id },
                    axis = "y"
                ))
            }
        }

        // 按 X 坐标分组 (垂直对齐)
        val xGroups = nodes.groupBy { (it.bounds[0] / dpr / tolerance).toInt() * tolerance }
        for (group in xGroups.values) {
            if (group.size > 1) {
                relations.add(AlignmentRelation(
                    elements = group.map { it.id },
                    axis = "x"
                ))
            }
        }

        return relations
    }

    /**
     * 判断两个节点是否水平相邻
     */
    private fun isHorizontallyAdjacent(node1: FigmaNode, node2: FigmaNode): Boolean {
        val tolerance = 20  // dp
        val y1 = (node1.bounds[1] / dpr).toInt()
        val y2 = (node2.bounds[1] / dpr).toInt()
        val x1Right = (node1.bounds[2] / dpr).toInt()
        val x2Left = (node2.bounds[0] / dpr).toInt()

        // Y 坐标接近 且 X 方向相邻
        return abs(y1 - y2) < tolerance && x2Left >= x1Right
    }

    /**
     * 判断两个节点是否垂直相邻
     */
    private fun isVerticallyAdjacent(node1: FigmaNode, node2: FigmaNode): Boolean {
        val tolerance = 20  // dp
        val x1 = (node1.bounds[0] / dpr).toInt()
        val x2 = (node2.bounds[0] / dpr).toInt()
        val y1Bottom = (node1.bounds[3] / dpr).toInt()
        val y2Top = (node2.bounds[1] / dpr).toInt()

        // X 坐标接近 且 Y 方向相邻
        return abs(x1 - x2) < tolerance && y2Top >= y1Bottom
    }
}
```

### 测试策略

```kotlin
class RelationExtractorTest {
    @Test
    fun `test extract spacing relations`() {
        val figma = loadFigmaJson("test_tab_bar.json")
        val extractor = RelationExtractor(dpr = 1f)

        val relations = extractor.extractRelations(figma)

        // 验证: Avatar 和 App 之间的间距
        val spacing = relations.find {
            it is SpacingRelation &&
            it.element1 == "34:12200" &&
            it.element2 == "34:12202"
        } as SpacingRelation

        assertEquals(18, spacing.expected)
        assertEquals("x", spacing.axis)
    }

    @Test
    fun `test extract alignment relations`() {
        val figma = loadFigmaJson("test_tab_bar.json")
        val extractor = RelationExtractor(dpr = 1f)

        val relations = extractor.extractRelations(figma)

        // 验证: 4 个 Tab 水平对齐
        val alignment = relations.find {
            it is AlignmentRelation &&
            it.elements.size == 4 &&
            it.axis == "y"
        } as AlignmentRelation

        assertTrue(alignment.elements.containsAll(
            listOf("34:12200", "34:12202", "34:12204", "34:12206")
        ))
    }
}
```

---

## 问题 4: 场景 1/2/4 的价值

### 问题描述
v3 文档中的场景 1/2/4 是否有独立使用场景，对 Agent 是否有价值？

### 场景回顾（来自 v3 文档）

**场景 1: 单个元素查找**
```json
{
  "projectDir": "/path/to/project",
  "target": {"text": "Avatar"}
}
```
→ 用于 `ui_find` 工具

**场景 2: 位置查找**
```json
{
  "projectDir": "/path/to/project",
  "target": {"bounds": [16, 278, 87, 324]}
}
```
→ 用于 `ui_find` 工具

**场景 4: 验证特定节点**
```json
{
  "projectDir": "/path/to/project",
  "figmaJson": "design_structure.json",
  "dpr": 1,
  "targetNodes": ["34:12200", "34:12202"]
}
```
→ 用于 `figma_layout_verify` 工具

### 价值分析

#### 场景 1: 单个元素查找
✅ **有价值** - 单点验证场景

```
用户: "Avatar 按钮的位置"

Agent:
1. 调用 ui_find(target={text:"Avatar"})
2. 返回 bounds: [16, 278, 67, 324]
3. 告诉用户: "左上角 (16, 278)，宽高 51x46 dp"
```

#### 场景 2: 位置查找
❓ **价值存疑** - Agent 很少知道具体坐标

**问题**: Agent 无法将"屏幕左上角"转换为 `bounds=[0, 0, 100, 100]`

**建议**:
- 标注为**内部使用**（figma_layout_verify 用于匹配）
- 或完全移除，不对外暴露

#### 场景 4: 验证特定节点
✅ **有价值** - 部分验证场景

```
用户: "只检查 Tab 栏是否符合设计"

Agent:
1. 调用 figma_layout_verify(targetNodes=["34:12200", "34:12202", ...])
2. 只验证指定的 4 个 Tab
3. 返回验证结果
```

### 结论

- **场景 1**: ✅ 保留，用于单点验证
- **场景 2**: ❌ 移除或标注为内部使用
- **场景 4**: ✅ 保留，用于部分验证

---

## 问题 5: Figma 数据字段不一致

### 问题描述
不同用户提供的 Figma 数据字段可能不一样，如何处理？

### 示例

**用户 A 的 Figma**:
```json
{
  "id": "34:12200",
  "name": "Button84",
  "bounds": [16, 278, 87, 324]
}
```

**用户 B 的 Figma**:
```json
{
  "id": "34:12200",
  "name": "Button84",
  "layout": [16, 278, 87, 324]  // 字段名不同
}
```

### 解决方案

#### 方案 1: 定义最小必需字段

```typescript
/**
 * Figma 节点的最小必需字段
 */
interface FigmaNodeMinimal {
  id: string;           // 必需
  name?: string;        // 可选
  bounds: IntArray;     // 必需 (或 layout)
  children?: FigmaNodeMinimal[];  // 可选
}
```

**验证逻辑**:
```kotlin
fun validateFigmaJson(json: JsonObject): ValidationResult {
    if (!json.has("id")) {
        return ValidationResult.error("Missing required field: id")
    }

    if (!json.has("bounds") && !json.has("layout")) {
        return ValidationResult.error("Missing required field: bounds or layout")
    }

    return ValidationResult.ok()
}
```

#### 方案 2: 字段映射配置 (可选)

```typescript
interface FigmaLayoutVerifyParams {
  projectDir: string;
  figmaJson: string;
  dpr: number;

  // 可选: 字段映射
  fieldMapping?: {
    bounds?: string;  // 默认 "bounds"，可改为 "layout"
    id?: string;      // 默认 "id"
    name?: string;    // 默认 "name"
  };
}
```

**使用示例**:
```json
{
  "projectDir": "/path",
  "figmaJson": "design.json",
  "dpr": 1,
  "fieldMapping": {
    "bounds": "layout"  // Figma 用 layout 字段存储坐标
  }
}
```

#### 推荐方案

**Phase 1**: 仅支持标准字段 (bounds/id/name)
**Phase 2**: 如果用户反馈字段不一致，再添加 fieldMapping

**文档说明**:
```markdown
## Figma JSON 格式要求

### 必需字段
- `id`: 节点唯一标识
- `bounds`: 节点位置 [left, top, right, bottom]

### 可选字段
- `name`: 节点名称
- `children`: 子节点数组

### 示例
\`\`\`json
{
  "id": "34:12200",
  "name": "Button84",
  "bounds": [16, 278, 87, 324],
  "children": [...]
}
\`\`\`

### 字段别名
如果您的 Figma 导出使用不同字段名:
- `layout` → `bounds`
- 请联系我们添加支持
```

---

## 总结

| 问题 | 解决方案 | 优先级 |
|------|---------|--------|
| 1. target=bounds 场景 | 标注为内部使用 | P1 |
| 2. dpr 归一化歧义 | 明确语义 + 文档说明 | P0 |
| 3. 提取相对关系 | 独立模块 RelationExtractor | P0 |
| 4. 场景 1/2/4 价值 | 保留 2/4，废弃 1 | P1 |
| 5. Figma 字段不一致 | 定义最小必需字段 | P1 |

**下一步**: 更新 v3 文档，反映这些澄清
