# UI 布局验证设计

> 最后核对：2026-04-15  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 设计哲学

**核心原则**：调用成功率优先，支持范围其次

**关键决策**：
- **验证相对关系而非绝对位置**：屏幕比例不同导致 bounds 绝对值不可比，相对关系（间距/对齐）才是布局本质
- **MCP 自动提取期望值**：Agent 只需提供 `figmaJson + dpr`，MCP 自动从 Figma 提取所有相对关系并验证
- **IoU 模糊匹配**：解决 Figma 与 Android 结构不一致问题，打平树结构用 IoU 匹配

---

## 2. 工具体系

| 工具 | 状态 | 职责 |
|------|------|------|
| `view-locate` | **新增** | 根据文本/位置查找元素，返回 bounds + position + size |
| `figma-layout-verify` | **新增** | 自动提取 Figma 相对关系并批量验证 |
| `layout-dump` | 保留 | `view-locate` 内部调用 |
| `layout_verify` | **废弃** | 被 `view-locate` + `figma-layout-verify` 替代 |
| `view-inspect` | 保留 | 颜色验证时可能需要 |

---

## 3. 核心模块架构

```
┌─────────────────────────────────────────────────────────┐
│                    MCP Tool Layer                        │
│  ┌─────────────────┐      ┌──────────────────────┐    │
│  │   view-locate    │      │ figma-layout-verify  │    │
│  │  (单点查询)       │      │   (批量验证)          │    │
│  └────────┬─────────┘      └──────────┬───────────┘    │
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
│  │  (IoU 模糊匹配)  │      │  (关系验证)           │    │
│  └─────────────────┘      └──────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

---

## 4. 关键算法

### 4.1 IoU 模糊匹配

用于匹配 Figma 节点到 Android View：

```kotlin
fun calculateIoU(bounds1: IntArray, bounds2: IntArray): Float {
    // Intersection over Union
    // 归一化到相同屏幕尺寸后计算
    // 置信度 >= 0.7 判定为匹配成功
}
```

### 4.2 自动提取相对关系

从 Figma JSON 自动提取：

- **间距关系 (spacing)**：水平/垂直相邻元素的距离
- **对齐关系 (alignment)**：按坐标分组检测对齐

```kotlin
class RelationExtractor(private val dpr: Float) {
    fun extractRelations(figmaJson: FigmaNode): List<Relation>
    // 相邻判断容差: 20dp
}
```

### 4.3 固定容差标准

参考 Android Espresso 标准，**不支持自定义**：

- **绝对容差**：±2dp
- **百分比容差**：±5%
- **判断逻辑**：`absDiff <= 2dp OR percentDiff <= 5%`

---

## 5. dpr 参数处理

| Figma 画板宽度 | dpr 值 | 说明 |
|---------------|--------|------|
| 750px | 2 | 2x 像素设计稿 |
| 375px | 1 | dp 单位设计稿 |
| 1125px | 3 | 3x 像素设计稿 |
| 411px | 1 | Android dp 设计稿 |

**验证逻辑**：检查画板宽度是否为常见值，不常见时返回 warning。

---

## 6. 返回格式

### view-locate 返回

```json
{
  "status": "OK",
  "data": {
    "matched": {
      "selector": {"text": "Avatar", "className": "KRView"},
      "bounds": [16, 278, 67, 324],
      "position": {"left": "16dp", "top": "278dp", "centerX": "41.5dp", "centerY": "301dp"},
      "size": {"width": "51dp", "height": "46dp"}
    },
    "confidence": 0.92
  }
}
```

### figma-layout-verify 返回

```json
{
  "status": "OK",
  "data": {
    "summary": {"total": 15, "passed": 12, "failed": 3},
    "results": [
      {
        "type": "spacing",
        "element1": {"figmaId": "34:12200", "androidSelector": {"text": "Avatar"}},
        "element2": {"figmaId": "34:12202", "androidSelector": {"text": "App"}},
        "axis": "x",
        "expected": "18dp",
        "actual": "14dp",
        "match": false,
        "diff": "-4dp"
      },
      {
        "type": "alignment",
        "elements": [...],
        "axis": "y",
        "expected": "centerY aligned",
        "actual": "centerY: [293, 293, 293, 293]",
        "match": true
      }
    ],
    "unmatched": [
      {"figmaId": "34:12179", "figmaName": "Group 1912055492", "reason": "No similar element found"}
    ]
  }
}
```

---

## 7. 使用场景

### 场景 1: 有 Figma 设计稿 - 批量验证

```
用户: "检查这个页面是否符合 Figma 设计稿"

Agent 流程:
1. 调用 figma-layout-verify(figmaJson="design.json", dpr=1)
2. 获取验证报告
3. 输出差异摘要，根据 diff 修复代码
```

### 场景 2: 无 Figma - 单点验证

```
用户: "Avatar 和 App 按钮之间的间距"

Agent 流程:
1. 调用 view_locate(target={text:"Avatar"})
2. 调用 view_locate(target={text:"App"})
3. 计算间距: spacing = app.bounds[0] - avatar.bounds[2]
4. 询问: "当前间距 18dp，期望间距是多少?"
```

### 场景 3: 部分验证

```json
{
  "projectDir": "/path/to/project",
  "figmaJson": "design_structure.json",
  "dpr": 1,
  "targetNodes": ["34:12200", "34:12202"]
}
```

---

## 8. Kuikly 框架支持

`KuiklyViewResolver` 支持从 Kuikly 特有 View 提取属性：

- **KRRichTextView / KRGradientRichTextView**：文本提取
  - 主路径：`view.richTextShadow.textProps.text`
  - 回退路径：`view.textLayout.getText()`

---

## 9. 实现优先级

| Phase | 内容 | 状态 |
|-------|------|------|
| Phase 1 | `view-locate` 基础功能、Bounds 归一化/匹配/验证 | MVP |
| Phase 2 | `figma-layout-verify` 批量验证、returnMode="all" | 进行中 |
| Phase 3 | 颜色验证（仅纯色）| 待定 |

---

## 10. 关联设计文档

- 详细设计：`docs/task/ui_fuzzy_match_design_v3.md`
- 问题澄清：`docs/task/ui_fuzzy_match_design_clarifications.md`
- 开发方案：`docs/task/ui_fuzzy_match_implementation_plan.md`
