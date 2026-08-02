# UI 布局验证工具设计方案 v3

> 创建时间: 2026-03-12
> 状态: 聚焦布局验证
> 核心原则: **调用成功率优先，支持范围其次**

---

## 1. 设计哲学

### 1.1 核心决策

**验证相对关系，而非绝对位置**

**理由**:
1. ✅ 屏幕比例不同导致 bounds 绝对值不可比
2. ✅ 相对关系 (间距/对齐) 才是布局的本质
3. ✅ 符合设计师思维和 layout_verify 的验证思路

**验证项**:
- **间距 (spacing)**: 元素之间的距离
- **对齐 (alignment)**: 元素是否在同一条线上
- **尺寸比例 (size_ratio)**: 元素宽高占屏幕的比例

**其他属性**:
- **颜色**: Phase 3，仅支持纯色
- **圆角/描边**: 暂不支持

### 1.2 关键洞察

**问题**: 如果 Agent 需要提供所有间距期望值，太复杂且容易出错

**解决**: **MCP 自动从 Figma JSON 提取所有相对关系**

```
Agent 只需提供: figmaJson + dpr
MCP 自动完成:
1. 提取 Figma 所有元素的相对关系
2. 匹配 Android 元素
3. 计算 Android 的相对关系
4. 对比差异
```

### 1.3 优先级

```
1. 调用成功率 (Agent 正确理解 + MCP 正确返回)
2. 支持范围 (功能覆盖度)
```

---

## 2. 工具设计

### 2.1 工具 1: `ui_find` (基础查找)

**职责**: 根据位置/文本查找元素，返回 bounds

```typescript
interface UiFindParams {
  projectDir: string;

  // 查找方式 (二选一)
  target: {
    bounds?: [number, number, number, number];  // 位置查找
    text?: string;                               // 文本查找
  };

  // 返回控制
  returnMode?: "best" | "all";  // 默认 "best"
}
```

**返回**:
```typescript
{
  "status": "OK",
  "data": {
    "matched": {
      "selector": {"text": "Avatar", "className": "KRView"},
      "bounds": [16, 278, 67, 324]  // dp
    },
    "confidence": 0.92
  }
}
```

---

### 2.2 工具 2: `figma_layout_verify` (批量布局验证)

**职责**: 自动提取 Figma 相对关系并验证

```typescript
interface FigmaLayoutVerifyParams {
  projectDir: string;
  figmaJson: string;  // Figma 设计稿路径
  dpr: number;        // 设计稿倍率 (1/2/3)

  // 可选: 指定验证哪些节点
  targetNodes?: string[];

  // 容差固定: ±2dp 或 ±5% (不可配置)

  // Agent 不需要提供期望值！
  // MCP 自动从 Figma 提取所有相对关系
}
```

**返回**:
```typescript
{
  "status": "OK",
  "data": {
    "summary": {
      "total": 15,        // 总关系数
      "passed": 12,
      "failed": 3
    },
    "results": [
      {
        "type": "spacing",
        "element1": {
          "figmaId": "34:12200",
          "figmaName": "Avatar",
          "androidSelector": {"text": "Avatar"}
        },
        "element2": {
          "figmaId": "34:12202",
          "figmaName": "App",
          "androidSelector": {"text": "App"}
        },
        "axis": "x",
        "expected": "18dp",
        "actual": "18dp",
        "match": true
      },
      {
        "type": "alignment",
        "elements": [
          {"figmaId": "34:12200", "androidSelector": {"text": "Avatar"}},
          {"figmaId": "34:12202", "androidSelector": {"text": "App"}},
          {"figmaId": "34:12204", "androidSelector": {"text": "Comment"}},
          {"figmaId": "34:12206", "androidSelector": {"text": "Suite"}}
        ],
        "axis": "y",
        "expected": "centerY aligned",
        "actual": "centerY: [293, 293, 293, 293]",
        "match": true
      },
      {
        "type": "spacing",
        "element1": {"figmaId": "34:12200", "androidSelector": {"text": "Avatar"}},
        "element2": {"figmaId": "34:12202", "androidSelector": {"text": "App"}},
        "axis": "x",
        "expected": "18dp",
        "actual": "14dp",
        "match": false,
        "diff": "-4dp"
      }
    ],
    "unmatched": [
      {
        "figmaId": "34:12179",
        "figmaName": "Group 1912055492",
        "reason": "No similar element found in layout"
      }
    ]
  }
}
```

---

## 3. 核心实现

### 3.1 Bounds 归一化

```kotlin
/**
 * 归一化 Figma bounds 到 Android dp
 */
fun normalizeFigmaBounds(
    figmaBounds: IntArray,
    figmaDpr: Float  // Figma 设计稿倍率
): IntArray {
    // Figma px → dp
    return figmaBounds.map { px ->
        (px / figmaDpr).toInt()
    }.toIntArray()
}
```

### 3.2 自动提取相对关系

```kotlin
/**
 * 从 Figma JSON 自动提取所有相对关系
 */
fun extractRelationsFromFigma(figmaJson: FigmaNode): List<Relation> {
    val relations = mutableListOf<Relation>()
    val nodes = figmaJson.flattenNodes()

    // 1. 提取所有相邻元素的间距
    for (i in 0 until nodes.size - 1) {
        val node1 = nodes[i]
        val node2 = nodes[i + 1]

        // 水平间距
        if (isHorizontallyAdjacent(node1, node2)) {
            relations.add(Relation(
                type = "spacing",
                element1 = node1.id,
                element2 = node2.id,
                axis = "x",
                expected = (node2.bounds[0] - node1.bounds[2]) / dpr
            ))
        }

        // 垂直间距
        if (isVerticallyAdjacent(node1, node2)) {
            relations.add(Relation(
                type = "spacing",
                element1 = node1.id,
                element2 = node2.id,
                axis = "y",
                expected = (node2.bounds[1] - node1.bounds[3]) / dpr
            ))
        }
    }

    // 2. 提取对齐关系
    val tolerance = 5  // dp
    val groups = nodes.groupBy { it.bounds[1] / tolerance * tolerance }
    for (group in groups.values) {
        if (group.size > 1) {
            relations.add(Relation(
                type = "alignment",
                elements = group.map { it.id },
                axis = "y"
            ))
        }
    }

    return relations
}

/**
 * 判断两个元素是否水平相邻
 */
fun isHorizontallyAdjacent(node1: FigmaNode, node2: FigmaNode): Boolean {
    val tolerance = 20  // dp
    // Y 坐标接近 且 X 方向相邻
    return abs(node1.bounds[1] - node2.bounds[1]) < tolerance &&
           node2.bounds[0] >= node1.bounds[2]
}
```

### 3.3 Bounds 模糊匹配 (用于元素匹配)

```kotlin
/**
 * 计算两个 bounds 的 IoU (Intersection over Union)
 * 用于匹配 Figma 元素到 Android 元素
 */
fun calculateIoU(bounds1: IntArray, bounds2: IntArray): Float {
    val (l1, t1, r1, b1) = bounds1
    val (l2, t2, r2, b2) = bounds2

    // 计算交集
    val intersectLeft = max(l1, l2)
    val intersectTop = max(t1, t2)
    val intersectRight = min(r1, r2)
    val intersectBottom = min(b1, b2)

    if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) {
        return 0f
    }

    val intersectArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
    val area1 = (r1 - l1) * (b1 - t1)
    val area2 = (r2 - l2) * (b2 - t2)
    val unionArea = area1 + area2 - intersectArea

    return intersectArea.toFloat() / unionArea
}

/**
 * 模糊匹配: 仅使用 IoU
 *
 * 决策: 不使用文本作为权重
 * 理由: 文字在开发过程中不稳定，不作为可靠匹配依据
 */
fun calculateMatchScore(
    figmaNode: FigmaNode,
    androidNode: AndroidNode
): Float {
    // 归一化到相同屏幕尺寸后计算 IoU
    val normalizedFigma = normalizeBounds(figmaNode.bounds, figmaScreenSize)
    val normalizedAndroid = normalizeBounds(androidNode.bounds, androidScreenSize)
    return calculateIoU(normalizedFigma, normalizedAndroid)
}
```

### 3.4 相对关系验证 (固定容差)

```kotlin
/**
 * 参考 Android Espresso 的容差标准
 *
 * 决策: 不支持自定义容差
 * 理由: 固定容差保证一致性，避免配置复杂度
 */
object RelationVerification {
    const val TOLERANCE_DP = 2  // ±2dp (固定)
    const val TOLERANCE_PERCENT = 0.05f  // ±5% (固定)

    /**
     * 验证间距
     */
    fun verifySpacing(
        element1: AndroidNode,
        element2: AndroidNode,
        expected: Int,
        axis: String
    ): VerifyResult {
        val actual = when (axis) {
            "x" -> element2.bounds[0] - element1.bounds[2]
            "y" -> element2.bounds[1] - element1.bounds[3]
            else -> 0
        }

        val match = isWithinTolerance(actual - expected, expected)
        return VerifyResult(
            match = match,
            expected = "${expected}dp",
            actual = "${actual}dp",
            diff = "${actual - expected}dp"
        )
    }

    /**
     * 验证对齐
     */
    fun verifyAlignment(
        elements: List<AndroidNode>,
        axis: String
    ): VerifyResult {
        val centers = elements.map { node ->
            when (axis) {
                "x" -> (node.bounds[0] + node.bounds[2]) / 2
                "y" -> (node.bounds[1] + node.bounds[3]) / 2
                else -> 0
            }
        }

        val maxDiff = centers.maxOrNull()!! - centers.minOrNull()!!
        val match = maxDiff <= TOLERANCE_DP

        return VerifyResult(
            match = match,
            expected = "aligned",
            actual = "center${axis.uppercase()}: $centers, maxDiff: ${maxDiff}dp"
        )
    }

    private fun isWithinTolerance(diff: Int, expected: Int): Boolean {
        val absDiff = abs(diff)
        val percentDiff = if (expected != 0) absDiff.toFloat() / expected else 0f
        return absDiff <= TOLERANCE_DP || percentDiff <= TOLERANCE_PERCENT
    }
}
```

---

## 4. 使用场景

### 场景 1: 单个元素查找

```json
{
  "projectDir": "/path/to/project",
  "target": {"text": "Avatar"}
}
```

**返回**: `{"bounds": [16, 278, 67, 324]}`

---

### 场景 2: 位置查找

```json
{
  "projectDir": "/path/to/project",
  "target": {"bounds": [16, 278, 87, 324]}
}
```

**返回**: 该位置附近的元素

---

### 场景 3: 批量验证 Figma 设计稿

```json
{
  "projectDir": "/path/to/project",
  "figmaJson": "design_structure.json",
  "dpr": 1
}
```

**返回**: 完整的验证报告 (匹配/验证/失败)

---

### 场景 4: 验证特定节点

```json
{
  "projectDir": "/path/to/project",
  "figmaJson": "design_structure.json",
  "dpr": 1,
  "targetNodes": ["34:12200", "34:12202"]
}
```

**返回**: 仅验证指定的 2 个节点

---

## 5. 颜色验证 (Phase 3，待定)

### 5.1 仅支持纯色

```kotlin
fun verifyColor(expected: String, actual: String): ColorVerifyResult {
    // 检查是否为纯色
    if (!isSolidColor(actual)) {
        return ColorVerifyResult(
            match = false,
            reason = "非纯色 (渐变/图片)"
        )
    }

    // 纯色比较 (容差: RGB 每通道 ±10)
    val match = compareColors(expected, actual, tolerance = 10)
    return ColorVerifyResult(match = match)
}

fun isSolidColor(color: String): Boolean {
    // 检查是否为 #AARRGGBB 格式
    return color.matches(Regex("^#[0-9A-Fa-f]{8}$"))
}
```

### 5.2 返回格式

```json
{
  "color": {
    "expected": "#FF09DE6E",
    "actual": "#FF09DE6E",
    "match": true
  }
}

// 或

{
  "color": {
    "expected": "#FF09DE6E",
    "actual": "非纯色",
    "match": false,
    "reason": "渐变/图片背景"
  }
}
```

---

## 6. 网格验证 (待定)

**决策**: 先不实现，视 Phase 1/2 测试效果再决定是否需要

**理由**:
- 增加实现复杂度 (~200 行代码)
- 不确定所有项目都需要
- 可能通过 Agent 多次调用 `ui_find` 实现

### 6.1 网格检测算法

```kotlin
fun detectGrid(nodes: List<AndroidNode>): GridInfo {
    // 1. 按 Y 坐标分组 (识别行)
    val tolerance = 20  // dp
    val rows = nodes.groupBy { it.bounds[1] / tolerance * tolerance }

    // 2. 计算列数 (取最常见的行元素数)
    val columns = rows.values
        .map { it.size }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key ?: 1

    // 3. 计算平均尺寸
    val avgWidth = nodes.map { it.bounds[2] - it.bounds[0] }.average().toInt()
    val avgHeight = nodes.map { it.bounds[3] - it.bounds[1] }.average().toInt()

    // 4. 计算平均间距
    val horizontalSpacing = calculateAvgHorizontalSpacing(nodes, columns)
    val verticalSpacing = calculateAvgVerticalSpacing(rows.values.toList())

    return GridInfo(
        columns = columns,
        itemCount = nodes.size,
        avgItemSize = "${avgWidth}x${avgHeight}dp",
        avgHorizontalSpacing = "${horizontalSpacing}dp",
        avgVerticalSpacing = "${verticalSpacing}dp"
    )
}
```

### 6.2 网格验证

```json
// Figma 设计稿中的网格定义
{
  "gridLayout": {
    "columns": 3,
    "itemSize": "109x151dp",
    "horizontalSpacing": "8dp",
    "verticalSpacing": "15.5dp"
  }
}

// 验证结果
{
  "gridVerification": {
    "expected": {
      "columns": 3,
      "itemSize": "109x151dp",
      "horizontalSpacing": "8dp",
      "verticalSpacing": "15.5dp"
    },
    "actual": {
      "columns": 3,
      "itemSize": "109x151dp",
      "horizontalSpacing": "8dp",
      "verticalSpacing": "16dp"
    },
    "match": false,
    "diff": {
      "verticalSpacing": "+0.5dp"
    }
  }
}
```

---

## 7. 单点验证场景 (无设计稿)

### 7.1 场景分类

**用户输入的可能性**:
1. "Avatar 按钮位置不对"
2. "Avatar 按钮应该在屏幕左边 16dp"
3. "Avatar 和 App 之间间距太大"
4. "这 4 个 Tab 没有对齐"
5. "这个按钮位置不对" (没说是哪个)

### 7.2 Agent 工作流

#### 流程 1: 信息充足 (有明确元素名)

```
用户: "Avatar 按钮位置不对"

Agent:
1. 调用 ui_find(target={text:"Avatar"})
2. 获取 bounds: [16, 278, 67, 324]
3. 计算位置信息:
   - 距离左边: 16dp
   - 距离顶部: 278dp
   - 宽高: 51x46 dp
4. 询问用户:
   "Avatar 按钮当前位置: 左上角 (16, 278)，宽高 51x46 dp
    请问期望的位置是?"
```

#### 流程 2: 信息部分充足 (有期望值)

```
用户: "Avatar 按钮应该距离左边 16dp"

Agent:
1. 调用 ui_find(target={text:"Avatar"})
2. 获取 bounds: [16, 278, 67, 324]
3. 计算: 当前距离左边 16dp
4. 回复: "Avatar 按钮当前距离左边 16dp，已符合期望"
```

#### 流程 3: 关系问题

```
用户: "Avatar 和 App 之间间距太大"

Agent:
1. 调用 ui_find(target={text:"Avatar"})
2. 调用 ui_find(target={text:"App"})
3. 计算间距: app.bounds[0] - avatar.bounds[2] = 18dp
4. 询问: "当前间距 18dp，期望间距是多少?"
```

#### 流程 4: 对齐问题

```
用户: "这 4 个 Tab 没有对齐"

Agent:
1. 调用 ui_find(target={text:"Avatar"})
   调用 ui_find(target={text:"App"})
   调用 ui_find(target={text:"Comment"})
   调用 ui_find(target={text:"Suite"})
2. 计算各自的 centerY
3. 回复: "4 个 Tab 的 Y 中心分别是: [293, 293, 295, 293]
         Comment 偏移了 2dp，是否需要修正?"
```

#### 流程 5: 信息不足

```
用户: "这个按钮位置不对"

Agent:
1. 调用 screenshot
2. 让 VLM 识别可能的按钮
3. 或询问: "请问是哪个按钮? 可以提供文字或大致位置"
```

### 7.3 ui_find 返回格式 (增强版)

```json
{
  "matched": {
    "selector": {"text": "Avatar"},
    "bounds": [16, 278, 67, 324],
    "position": {
      "left": "16dp",
      "top": "278dp",
      "centerX": "41.5dp",
      "centerY": "301dp"
    },
    "size": {
      "width": "51dp",
      "height": "46dp"
    }
  }
}
```

### 7.4 Agent Prompt 模板

```markdown
当用户反馈 UI 位置问题时:

1. 识别元素:
   - 如果有明确文字 → ui_find(text=...)
   - 如果有位置描述 → ui_find(bounds=[...])
   - 如果不明确 → 询问或 screenshot

2. 获取当前状态:
   - 单个元素 → 返回 bounds + position + size
   - 两个元素 → 计算间距
   - 多个元素 → 检查对齐

3. 对比期望:
   - 如果用户提供期望值 → 直接对比
   - 如果没有期望值 → 展示当前值，询问期望

4. 生成报告:
   "元素: Avatar 按钮
    当前位置: 左上角 (16, 278)
    当前尺寸: 51x46 dp
    与 App 按钮间距: 18dp

    请问哪里不符合预期?"
```

### 7.5 设计哲学

**充分利用 Agent 的思考能力**:
- ✅ MCP 提供丰富的位置信息
- ✅ Agent 理解用户意图
- ✅ Agent 计算关系 (间距/对齐)
- ✅ Agent 与用户交互获取期望值

**不纠结于让 Agent 提供断言**:
- ✅ 有设计稿 → MCP 自动提取期望值
- ✅ 无设计稿 → Agent 展示现状，询问期望

---

## 8. Agent 使用指南

### 8.1 有 Figma 设计稿

```
用户: "检查这个页面是否符合 Figma 设计稿"

Agent 流程:
1. 调用 figma_layout_verify(figmaJson="design.json", dpr=1)
2. 获取验证报告
3. 输出差异摘要
```

### 8.2 无 Figma，查询单个元素

```
用户: "Avatar 按钮的位置和大小"

Agent 流程:
1. 调用 ui_find(target={text:"Avatar"})
2. 返回 bounds
3. 计算宽高: width = bounds[2] - bounds[0]
```

### 8.3 关系查询 (Agent 自己计算)

```
用户: "Avatar 和 App 按钮之间的间距"

Agent 流程:
1. 调用 ui_find(target={text:"Avatar"})
2. 调用 ui_find(target={text:"App"})
3. 计算间距: spacing = app.bounds[0] - avatar.bounds[2]
```

---

---

## 9. 原始困扰对照检查

### 9.1 原始问题 (来自对话记录)

**Q**: "有没有一种算法可以模糊匹配两者的结构关系，找出比较明显的布局问题，返回给 ai agent，让 agent 修改"

**核心困扰**:
1. ❓ Figma 与 Layout Dump 结构不一致 (层级差异)
2. ❓ 如何模糊匹配节点 (身份识别)
3. ❓ 如何生成 Agent 友好的报告
4. ❓ 屏幕比例不同导致坐标不可比
5. ❓ Agent 如何提供大量断言期望值

### 9.2 当前方案解决情况

#### ✅ 问题 1: 结构不一致
**解决**: 使用 IoU 模糊匹配，不依赖树结构
```kotlin
// 打平树结构，用 IoU 匹配
fun calculateMatchScore(figma, android): Float {
    return calculateIoU(
        normalizeBounds(figma.bounds, figmaScreenSize),
        normalizeBounds(android.bounds, androidScreenSize)
    )
}
```

#### ✅ 问题 2: 身份识别
**解决**: 模糊匹配 = 身份映射层
```
1. 提取 Figma 所有节点
2. 提取 Android 所有节点
3. 计算 IoU 得分
4. 匹配度 > 0.7 → 确认身份
```

#### ✅ 问题 3: Agent 友好报告
**解决**: 返回结构化的相对关系差异
```json
{
  "type": "spacing",
  "element1": {"figmaId": "34:12200", "androidSelector": {"text": "Avatar"}},
  "element2": {"figmaId": "34:12202", "androidSelector": {"text": "App"}},
  "expected": "18dp",
  "actual": "14dp",
  "match": false,
  "diff": "-4dp"
}
```

#### ✅ 问题 4: 屏幕比例不同
**解决**: 验证相对关系，而非绝对坐标
```
不验证: bounds [16, 278, 87, 324]
验证: 间距 18dp, 对齐 centerY
```

#### ✅ 问题 5: Agent 提供期望值
**解决**: MCP 自动从 Figma 提取，Agent 零负担
```
Agent 只需: figmaJson + dpr
MCP 自动: 提取关系 → 匹配元素 → 计算差异
```

### 9.3 对话记录中的关键洞察

#### 洞察 1: "模糊匹配是断言的前提"
✅ **已采纳**:
- 模糊匹配 = 身份映射层
- 断言 = 相对关系验证
- 流程: Mapping → Asserting

#### 洞察 2: "断言比原始数据更有用"
✅ **已采纳**:
- 不返回原始 bounds
- 返回结构化的关系差异
- 包含 expected/actual/diff

#### 洞察 3: "容错性更高"
✅ **已采纳**:
- 不关心中间层级差异
- 只关心最终的相对关系
- 固定容差 ±2dp 或 ±5%

#### 洞察 4: "指令更明确"
✅ **已采纳**:
```json
{
  "type": "spacing",
  "expected": "18dp",
  "actual": "14dp",
  "diff": "-4dp"
}
```
Agent 可以直接理解并修复

#### 洞察 5: "符合 Android 开发习惯"
✅ **已采纳**:
- 验证间距 (spacing)
- 验证对齐 (alignment)
- 这正是 ConstraintLayout 的约束模型

### 9.4 未解决的问题

❌ **网格验证**: 暂不实现，视测试效果决定
❌ **颜色验证**: Phase 3，仅支持纯色
❌ **圆角/描边**: 暂不支持

### 9.5 新增价值

✅ **单点验证场景**: 充分利用 Agent 思考能力
- Agent 展示当前状态
- Agent 询问期望值
- Agent 计算关系
- 不纠结于让 Agent 提供断言

---

## 10. 实现优先级

### Phase 1 (MVP)
- ✅ `ui_find` 基础功能 (bounds/text 查询)
- ✅ Bounds 归一化
- ✅ Bounds 模糊匹配 (IoU + 文本)
- ✅ Bounds 验证 (容差)

### Phase 2
- ✅ `figma_layout_verify` 批量验证
- ✅ 批量操作 (returnMode="all")

### Phase 3 (待定)
- ⏳ 颜色验证 (仅纯色) - 视 Phase 1/2 测试效果决定
- ⏳ 圆角/描边验证 - 暂不支持

---

## 11. 与现有工具的关系

| 工具 | 状态 | 说明 |
|------|------|------|
| `layout-dump` | **保留** | ui_find 内部调用 |
| `layout_verify` | **废弃** | 被 ui_find 替代 |
| `eval_view` | **保留** | 颜色验证时可能需要 |
| `screenshot` | **保留** | 兜底方案 |
| `tap` | **保留** | 使用 ui_find 返回的 selector |

---

## 12. 成功率保障

### 10.1 输入验证

```kotlin
fun validateParams(params: UiFindParams): ValidationResult {
    // 必须提供 target
    if (params.target == null) {
        return ValidationResult.error("target is required")
    }

    // bounds 或 text 必须提供一个
    if (params.target.bounds == null && params.target.text == null) {
        return ValidationResult.error("target.bounds or target.text is required")
    }

    // bounds 格式检查
    if (params.target.bounds != null && params.target.bounds.size != 4) {
        return ValidationResult.error("target.bounds must be [left, top, right, bottom]")
    }

    return ValidationResult.ok()
}
```

### 10.2 错误处理

```kotlin
fun handleMatchFailure(
    target: Target,
    candidates: List<AndroidNode>
): UiFindResult {
    return when {
        candidates.isEmpty() -> {
            UiFindResult.error(
                message = "No elements found",
                suggestions = findNearbyElements(target)
            )
        }
        candidates.size == 1 && candidates[0].confidence < 0.7 -> {
            UiFindResult.lowConfidence(
                matched = candidates[0],
                confidence = candidates[0].confidence,
                suggestions = candidates.drop(1).take(3)
            )
        }
        else -> {
            UiFindResult.success(
                matched = candidates.maxByOrNull { it.confidence }!!
            )
        }
    }
}
```

---

## 13. 最终决策

1. ✅ **布局验证**: 确定为核心功能
2. ✅ **批量操作**: 必须支持
3. ✅ **文本权重**: 不使用文本作为匹配权重 (文字不稳定)
4. ✅ **容差算法**: 固定使用 Espresso 标准 (±2dp 或 ±5%)，不支持自定义
5. ✅ **颜色验证**: Phase 3 实现，视测试效果决定
6. ⏳ **网格验证**: 先不做，视 Phase 1/2 测试效果再决定

---

## 附录: 参考资料

- Android Espresso: https://developer.android.com/training/testing/espresso
- Material Design: https://m3.material.io/
- 对话记录: `/Users/wormchen/Downloads/new_verify.txt`
- Figma 示例: `docs/request_forms/装扮二期/figma/头像页/design_structure.json`
- Layout Dump 示例: `build/jugg/mcp_fetch/layout_dump/layout_1773213617400.json`
