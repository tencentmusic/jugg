# UI 布局验证工具 - 开发方案

> 创建时间: 2026-03-12
> 基于: ui_fuzzy_match_design_v3.md
> 状态: 待开发

---

## 1. 核心设计

### 1.1 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    MCP Tool Layer                        │
│  ┌────────────��─────┐      ┌──────────────────────┐    │
│  │   ui_find        │      │ figma_layout_verify  │    │
│  │  (单点查询)       │      │   (批量验证)          │    │
│  └────────┬─────────┘      └──────────┬───────────┘    │
│           │                           │                 │
└───────────┼───────────────────────────┼─────────────────┘
            │                           │
┌───────────┼───────────────────────────┼─────────────────┐
│           │      Core Logic Layer     │                 │
│           ▼                           ▼                 │
│  ┌─────────────────┐      ┌──────────────────────┐    │
│  │  ElementFinder  │      │  RelationExtractor   │    │
│  │  (元素查找)      │      │  (关系提取)           │    │
│  └─────────────────┘      └──────────────────────┘    │
│           │                           │                 │
│           ▼                           ▼                 │
│  ┌─────────────────┐      ┌──────────────────────┐    │
│  │  ElementMatcher │      │  RelationVerifier    │    │
│  │  (模糊匹配)      │      │  (关系验证)           │    │
│  └─────────────────┘      └───────��──────────────┘    │
└─────────────────────────────────────────────────────────┘
            │                           │
┌───────────┼───────────────────────────┼─────────────────┐
│           │      Data Layer           │                 │
│           ▼                           ▼                 │
│  ┌─────────────────┐      ┌──────────────────────┐    │
│  │  layout_dump    │      │  Figma JSON Parser   │    │
│  │  (Android 数据)  │      │  (设计稿数据)         │    │
│  └─────────────────┘      └──────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

### 1.2 核心模块

#### 模块 1: RelationExtractor (关系提取器)
**职责**: 从 Figma JSON 自动提取相对关系
```kotlin
class RelationExtractor(private val dpr: Float) {
    fun extractRelations(figmaJson: FigmaNode): List<Relation>
    private fun extractSpacingRelations(nodes: List<FigmaNode>): List<SpacingRelation>
    private fun extractAlignmentRelations(nodes: List<FigmaNode>): List<AlignmentRelation>
}
```

#### 模块 2: ElementMatcher (元素匹配器)
**职责**: 模糊匹配 Figma 节点到 Android View
```kotlin
class ElementMatcher {
    fun match(figmaNode: FigmaNode, androidNodes: List<AndroidNode>): MatchResult
    private fun calculateIoU(bounds1: IntArray, bounds2: IntArray): Float
    private fun normalizeBounds(bounds: IntArray, screenSize: IntArray): IntArray
}
```

#### 模块 3: RelationVerifier (关系验证器)
**职责**: 验证相对关系是否符合预期
```kotlin
class RelationVerifier {
    fun verifySpacing(element1: AndroidNode, element2: AndroidNode, expected: Int, axis: String): VerifyResult
    fun verifyAlignment(elements: List<AndroidNode>, axis: String): VerifyResult
}
```

#### 模块 4: FigmaJsonParser (Figma 解析器)
**职责**: 解析和验证 Figma JSON
```kotlin
class FigmaJsonParser {
    fun parse(jsonPath: String): FigmaNode
    fun validate(json: JsonObject): ValidationResult
    fun flattenNodes(root: FigmaNode): List<FigmaNode>
}
```

---

## 2. 开发流程

### Phase 1: 基础设施 (Week 1)

#### 2.1.1 数据模型定义
```kotlin
// 文件: mcp/layout/model/FigmaNode.kt
data class FigmaNode(
    val id: String,
    val name: String?,
    val bounds: IntArray,
    val children: List<FigmaNode>?
)

// 文件: mcp/layout/model/AndroidNode.kt
data class AndroidNode(
    val className: String,
    val id: String?,
    val text: String?,
    val bounds: IntArray
)

// 文件: mcp/layout/model/Relation.kt
sealed class Relation {
    data class SpacingRelation(
        val element1: String,
        val element2: String,
        val axis: String,
        val expected: Int
    ) : Relation()

    data class AlignmentRelation(
        val elements: List<String>,
        val axis: String
    ) : Relation()
}
```

#### 2.1.2 Figma JSON 解析器
```kotlin
// 文件: mcp/layout/parser/FigmaJsonParser.kt
class FigmaJsonParser {
    fun parse(jsonPath: String): FigmaNode
    fun validate(json: JsonObject): ValidationResult
    fun flattenNodes(root: FigmaNode): List<FigmaNode>
}
```

**测试用例**:
- 解析标准 Figma JSON
- 验证必需字段
- 处理缺失字段
- 打平节点树

---

### Phase 2: 核心算法 (Week 2)

#### 2.2.1 关系提取器
```kotlin
// 文件: mcp/layout/extractor/RelationExtractor.kt
class RelationExtractor(private val dpr: Float) {
    fun extractRelations(figmaJson: FigmaNode): List<Relation>
    private fun extractSpacingRelations(nodes: List<FigmaNode>): List<SpacingRelation>
    private fun extractAlignmentRelations(nodes: List<FigmaNode>): List<AlignmentRelation>
    private fun isHorizontallyAdjacent(node1: FigmaNode, node2: FigmaNode): Boolean
    private fun isVerticallyAdjacent(node1: FigmaNode, node2: FigmaNode): Boolean
}
```

**测试用例**:
- 提取水平间距
- 提取垂直间距
- 提取水平对齐
- 提取垂直对齐
- 边界情况: 重叠元素、嵌套元素

#### 2.2.2 元素匹配器
```kotlin
// 文件: mcp/layout/matcher/ElementMatcher.kt
class ElementMatcher {
    fun match(figmaNode: FigmaNode, androidNodes: List<AndroidNode>): MatchResult
    private fun calculateIoU(bounds1: IntArray, bounds2: IntArray): Float
    private fun normalizeBounds(bounds: IntArray, screenSize: IntArray): IntArray
}
```

**测试用例**:
- IoU 计算正确性
- 归一化坐标
- 不同屏幕比例匹配
- 低置信度处理

#### 2.2.3 关系验证器
```kotlin
// 文件: mcp/layout/verifier/RelationVerifier.kt
class RelationVerifier {
    fun verifySpacing(element1: AndroidNode, element2: AndroidNode, expected: Int, axis: String): VerifyResult
    fun verifyAlignment(elements: List<AndroidNode>, axis: String): VerifyResult
}
```

**测试用例**:
- 间距验证 (±2dp 容差)
- 对齐验证 (±2dp 容差)
- 百分比容差 (±5%)

---

### Phase 3: MCP 工具实现 (Week 3)

#### 2.3.1 ui_find 工具
```kotlin
// 文件: mcp/actions/UiFindMcpToolAction.kt
class UiFindMcpToolAction : McpToolAction {
    override fun execute(params: UiFindParams): McpToolResult {
        // 1. 调用 layout_dump
        // 2. 根据 target.text 查找元素
        // 3. 返回 bounds + position + size
    }
}
```

**测试用例**:
- 文本查找成功
- 文本查找失败 (返回候选)
- returnMode="all" 返回多个结果

#### 2.3.2 figma_layout_verify 工具
```kotlin
// 文件: mcp/actions/FigmaLayoutVerifyMcpToolAction.kt
class FigmaLayoutVerifyMcpToolAction : McpToolAction {
    override fun execute(params: FigmaLayoutVerifyParams): McpToolResult {
        // 1. 解析 Figma JSON
        // 2. 提取相对关系
        // 3. 调用 layout_dump
        // 4. 匹配元素
        // 5. 验证关系
        // 6. 生成报告
    }
}
```

**测试用例**:
- 全量验证
- 部分验证 (targetNodes)
- dpr 参数验证
- 匹配失败处理

---

### Phase 4: 集成测试 (Week 4)

#### 2.4.1 端到端测试
- 使用真实 Figma JSON + Layout Dump
- 验证完整工作流
- 性能测试 (100+ 节点)

#### 2.4.2 Agent 集成测试
- Agent 调用 ui_find
- Agent 调用 figma_layout_verify
- Agent 理解返回结果

---

## 3. 预期产物列表

### 3.1 代码产物

#### 核心模块 (mcp/layout/)
```
mcp/layout/
├── model/
│   ├── FigmaNode.kt                    # Figma 数据模型
│   ├── AndroidNode.kt                  # Android 数据模型
│   ├── Relation.kt                     # 关系数据模型
│   └── VerifyResult.kt                 # 验证结果模型
├── parser/
│   └── FigmaJsonParser.kt              # Figma JSON 解析器
├── extractor/
│   └── RelationExtractor.kt            # 关系提取器 (300-500 行)
├── matcher/
│   └── ElementMatcher.kt               # 元素匹配器 (200-300 行)
└── verifier/
    └── RelationVerifier.kt             # 关系验证器 (200-300 行)
```

#### MCP 工具 (mcp/actions/)
```
mcp/actions/
├── UiFindMcpToolAction.kt              # ui_find 工具 (150-200 行)
└── FigmaLayoutVerifyMcpToolAction.kt   # figma_layout_verify 工具 (200-300 行)
```

#### 测试代码 (test/)
```
test/mcp/layout/
├── extractor/
│   └── RelationExtractorTest.kt        # 关系提取测试 (10+ 用例)
├── matcher/
│   └── ElementMatcherTest.kt           # 元素匹配测试 (10+ 用例)
├── verifier/
│   └── RelationVerifierTest.kt         # 关系验证测试 (10+ 用例)
└── integration/
    └── FigmaLayoutVerifyE2ETest.kt     # 端到端测试 (5+ 用例)
```

**代码量估算**:
- 核心模块: ~1500 行
- MCP 工具: ~500 行
- 测试代码: ~1000 行
- **总计: ~3000 行**

---

### 3.2 测试数据

```
test/assets/figma/
├── tab_bar.json                        # Tab 栏设计稿
├── list_grid.json                      # 网格列表设计稿
└── complex_page.json                   # 复杂页面设计稿

test/assets/layout_dump/
├── tab_bar_dump.json                   # Tab 栏 Layout Dump
├── list_grid_dump.json                 # 网格列表 Layout Dump
└── complex_page_dump.json              # 复杂页面 Layout Dump
```

---

### 3.3 文档产物

```
docs/
├── ai_knowledge/
│   └── 08_mcp_ui_fuzzy_match.md        # MCP 工具使用文档
└── task/
    ├── ui_fuzzy_match_design_v3.md     # 设计方案 (已完成)
    ├── ui_fuzzy_match_solution_validation.md  # 验证报告 (已完成)
    ├── ui_fuzzy_match_design_clarifications.md  # 问题澄清 (已完成)
    └── ui_fuzzy_match_implementation_plan.md  # 开发方案 (本文档)
```

---

## 4. 关键技术决策

### 4.1 dpr 参数处理
```
规则:
- 如果 Figma 单位是像素 → 填入设计稿倍率 (通常是 2)
- 如果 Figma 已经是 dp 单位 → 填 1

验证:
- 检查 Figma 画板宽度是否为常见值 (375/750/1125/411)
- 不常见时返回 warning
```

### 4.2 容差标准
```
固定容差 (不可配置):
- 绝对容差: ±2dp
- 百分比容差: ±5%

判断逻辑:
absDiff <= 2dp OR percentDiff <= 5%
```

### 4.3 匹配置信度阈值
```
- confidence >= 0.7: 匹配成功
- confidence < 0.7: 匹配失败，返回候选
```

### 4.4 相邻判断容差
```
水平相邻: abs(y1 - y2) < 20dp
垂直相邻: abs(x1 - x2) < 20dp
```

---

## 5. 风险与缓解

### 5.1 性能风险
**风险**: 大页面 (100+ 节点) 匹配耗时过长

**缓解**:
- 使用空间索引 (R-Tree) 加速邻近查找
- 提前过滤不可见节点
- 限制最大节点数 (如 200)

### 5.2 匹配准确率风险
**风险**: IoU 匹配失败率高

**缓解**:
- 调整置信度阈值 (0.7 → 0.6)
- 返回多个候选供 Agent 选择
- 提供 debug 模式查看匹配详情

### 5.3 Figma 数据多样性风险
**风险**: 不同用户的 Figma 导出字段不一致

**缓解**:
- Phase 1 只支持标准字段
- 收集用户反馈
- Phase 2 添加字段映射配置

---

## 6. 验收标准

### 6.1 功能验收
- ✅ ui_find 可以根据文本查找元素
- ✅ figma_layout_verify 可以批量验证设计稿
- ✅ 间距验证准确率 > 90%
- ✅ 对齐验证准确率 > 90%
- ✅ 元素匹配准确率 > 85%

### 6.2 性能验收
- ✅ 单个元素查找 < 200ms
- ✅ 50 节点批量验证 < 2s
- ✅ 100 节点批量验证 < 5s

### 6.3 Agent 友好性验收
- ✅ Agent 可以理解返回结果
- ✅ Agent 可以根据 diff 修复代码
- ✅ 错误提示清晰明确

---

## 7. 后续优化 (Phase 2+)

### 7.1 网格验证
- 自动检测网格布局
- 验证列数、间距、尺寸

### 7.2 颜色验证
- 支持纯色验证
- 渐变返回"非纯色"

### 7.3 字段映射
- 支持自定义字段映射
- 兼容不同 Figma 导出格式

---

## 8. 参考文档

- 设计方案: `docs/task/2026-03/ui_fuzzy_match_design_v3.md`
- 验证报告: `docs/task/2026-03/ui_fuzzy_match_solution_validation.md`
- 问题澄清: `docs/task/2026-03/ui_fuzzy_match_design_clarifications.md`
- MCP 使用文档: `docs/ai_knowledge/08_mcp_usage.md`
- MCP 设计文档: `docs/ai_knowledge/08_mcp_design.md`
