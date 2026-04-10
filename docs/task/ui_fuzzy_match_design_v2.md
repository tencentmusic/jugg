# UI 模糊匹配工具设计方案 v2

> 创建时间: 2026-03-12
> 状态: 精简设计阶段
> 目标: 为 Jugg MCP 设计一个通用、精简、有效的 UI 验证工具

---

## 1. 任务背景

### 1.1 核心问题

当前 Jugg MCP 的 UI 验证工具链存在以下问题:

1. **layout_verify 使用门槛高**
   - Agent 需要手写精确的 selector (resourceId/text/contentDesc)
   - 需要手动指定 expected value
   - Selector 匹配失败率高,需要 5 级 fallback chain

2. **screenshot 依赖过重**
   - 速度慢 (~2-5s)
   - 成本高 (vision token)
   - 准确率不稳定 (70-80%)
   - Agent 容易"看图说话",缺乏数据层验证

3. **工具链割裂**
   - 需要组合 layout_dump + layout_verify + eval_view + screenshot
   - Agent 需要理解复杂的工具调用顺序
   - 缺少统一的验证入口

---

## 2. 设计原则

1. **极简**: 只做一件事,做好一件事
2. **通用**: 不依赖特定设计工具 (Figma/Sketch/手动)
3. **有效**: 解决核心痛点 (selector 查找 + 属性验证)
4. **不大而全**: 不做语义理解,交给 Agent

---

## 3. 关键设计决策

### 3.1 Figma 数据结构泛化

**问题**: 原设计依赖 Figma 特定字段,不通用

**决策**: 使用通用结构

```typescript
// ❌ 泛化前 (Figma 专用)
{
  "figmaNode": {
    "id": "34:12200",
    "fillColor": "#FF09DE6E"
  }
}

// ✅ 泛化后 (通用)
{
  "target": {
    "bounds": [16, 278, 83, 324],      // 位置 (必填或与 text 二选一)
    "text": "Avatar"                    // 文本 (必填或与 bounds 二选一)
  },
  "expected": {                         // 期望属性 (可选)
    "bounds.width": "71dp",
    "backgroundColor": "#FF09DE6E"
  }
}
```

**优势**: 支持任何来源的设计规范

---

### 3.2 自然语言查询实现

**问题**: MCP 工具内部无法调用 Agent/LLM

**决策**: 不在 MCP 内部实现 NLP,由 Agent 预处理

```
用户: "找到绿色的按钮"

Agent 流程:
1. 理解"绿色按钮" → 推断可能的属性
2. 调用 ui_match({
     target: { filter: { backgroundColor: "#*09DE6E" } }
   })
3. 或降级到 layout_dump + Agent 自己遍历
```

**MCP 工具职责**: 只支持精确/模糊匹配,不做语义理解

---

## 4. 接口设计

### 4.1 核心工具: `ui_match`

**唯一职责**: 根据位置/文本/属性查找元素,返回匹配结果和属性

```typescript
interface UiMatchParams {
  projectDir: string;

  // 查询目标 (必填)
  target: {
    // 方式 1: 位置查询
    bounds?: [number, number, number, number];

    // 方式 2: 文本查询 (支持通配符 *)
    text?: string;

    // 方式 3: 属性过滤
    filter?: {
      className?: string;
      resourceId?: string;
      clickable?: boolean;
      [key: string]: any;
    };
  };

  // 期望属性 (可选,用于验证)
  expected?: {
    "bounds.width"?: string;
    "bounds.height"?: string;
    "backgroundColor"?: string;
    [key: string]: any;
  };

  // 返回控制 (可选)
  properties?: string[];  // 指定返回哪些属性,不传则返回常用属性

  // 调试 (可选)
  debug?: boolean;
}
```

### 4.2 返回格式

```typescript
interface UiMatchResult {
  status: "OK" | "ERROR";
  message: string;

  data: {
    // 匹配结果
    matched: {
      selector: {
        text?: string;
        resourceId?: string;
        className?: string;
      };
      bounds: [number, number, number, number];
      properties: Record<string, any>;  // 实际属性值
    } | null;

    // 验证结果 (如果传了 expected)
    verification?: {
      [property: string]: {
        expected: any;
        actual: any;
        match: boolean;
        diff?: string;  // 数值类型的差异
      };
    };

    // 置信度
    confidence?: number;

    // 候选项 (匹配失败或低置信度时)
    suggestions?: Array<{
      selector: Record<string, string>;
      confidence: number;
      reason: string;
    }>;

    // 调试信息
    debugInfo?: {
      iou_score?: number;
      text_score?: number;
      type_score?: number;
      candidates?: any[];
    };
  };
}
```

---

## 5. 使用场景

### 场景 1: 有设计稿验证

```json
{
  "projectDir": "/path/to/project",
  "target": {
    "bounds": [16, 278, 83, 324],
    "text": "Avatar"
  },
  "expected": {
    "bounds.width": "71dp",
    "backgroundColor": "#FF09DE6E",
    "cornerRadius": "15dp"
  }
}
```

**返回**:
```json
{
  "status": "OK",
  "data": {
    "matched": {
      "selector": {"text": "Avatar", "className": "KRView"},
      "bounds": [16, 278, 67, 324],
      "properties": {
        "bounds.width": "67dp",
        "backgroundColor": "#FF09DE6E",
        "cornerRadius": "15dp"
      }
    },
    "verification": {
      "bounds.width": {
        "expected": "71dp",
        "actual": "67dp",
        "match": false,
        "diff": "-4dp"
      },
      "backgroundColor": {
        "expected": "#FF09DE6E",
        "actual": "#FF09DE6E",
        "match": true
      },
      "cornerRadius": {
        "expected": "15dp",
        "actual": "15dp",
        "match": true
      }
    },
    "confidence": 0.92
  }
}
```

---

### 场景 2: 文本查询

```json
{
  "projectDir": "/path/to/project",
  "target": {"text": "Avatar"},
  "properties": ["bounds.width", "bounds.height", "backgroundColor"]
}
```

**返回**:
```json
{
  "status": "OK",
  "data": {
    "matched": {
      "selector": {"text": "Avatar"},
      "bounds": [16, 278, 67, 324],
      "properties": {
        "bounds.width": "67dp",
        "bounds.height": "46dp",
        "backgroundColor": "#FF09DE6E"
      }
    }
  }
}
```

---

### 场景 3: 位置查询

```json
{
  "projectDir": "/path/to/project",
  "target": {"bounds": [0, 0, 100, 100]}
}
```

**返回**: 该区域内的元素列表

---

### 场景 4: 属性过滤

```json
{
  "projectDir": "/path/to/project",
  "target": {
    "filter": {
      "className": "Button",
      "clickable": true
    }
  }
}
```

**返回**: 所有可点击的 Button 元素

---

### 场景 5: 匹配失败时的建议

```json
{
  "projectDir": "/path/to/project",
  "target": {"text": "Avater"}  // 拼写错误
}
```

**返回**:
```json
{
  "status": "OK",
  "data": {
    "matched": null,
    "confidence": 0.65,
    "suggestions": [
      {
        "selector": {"text": "Avatar"},
        "confidence": 0.85,
        "reason": "Similar text (edit distance: 1)"
      }
    ]
  }
}
```

---

## 6. 实现要点

### 6.1 模糊匹配算法

```kotlin
fun calculateMatchScore(target: Target, candidate: Node): Float {
    var score = 0f
    var weight = 0f

    // IoU 得分 (如果提供了 bounds)
    if (target.bounds != null) {
        score += 0.5f * calculateIoU(target.bounds, candidate.bounds)
        weight += 0.5f
    }

    // 文本相似度 (如果提供了 text)
    if (target.text != null) {
        score += 0.3f * calculateTextSimilarity(target.text, candidate.text)
        weight += 0.3f
    }

    // 类型匹配 (如果提供了 filter.className)
    if (target.filter?.className != null) {
        score += 0.2f * if (candidate.className == target.filter.className) 1f else 0f
        weight += 0.2f
    }

    return if (weight > 0) score / weight else 0f
}
```

### 6.2 属性获取策略

```kotlin
fun getProperties(node: Node, properties: List<String>): Map<String, Any> {
    val result = mutableMapOf<String, Any>()

    for (prop in properties) {
        when {
            // layout_dump 直接支持的属性
            prop in DUMP_SUPPORTED_PROPS -> {
                result[prop] = node.getProperty(prop)
            }
            // 需要 eval_view 的属性
            prop in EVAL_REQUIRED_PROPS -> {
                result[prop] = evalView(node.selector, prop)
            }
        }
    }

    return result
}

val DUMP_SUPPORTED_PROPS = setOf(
    "bounds.width", "bounds.height", "bounds.left", "bounds.top",
    "text", "className", "clickable", "enabled", "visibility"
)

val EVAL_REQUIRED_PROPS = setOf(
    "cornerRadius", "backgroundColor", "textColor", "textSize"
)
```

---

## 7. 与现有工具的关系

| 工具 | 状态 | 说明 |
|------|------|------|
| `layout-dump` | **保留** | ui_match 内部调用 |
| `layout_verify` | **废弃** | 被 ui_match 替代 |
| `eval_view` | **保留** | ui_match 内部调用 (查询特殊属性) |
| `screenshot` | **保留** | 兜底方案 (ui_match 失败时) |
| `tap` | **保留** | 使用 ui_match 返回的 selector |

---

## 8. Agent 使用指南

### 8.1 有设计稿的验证流程

```
用户: "检查这个页面是否符合 Figma 设计稿"

Agent 流程:
1. 读取 Figma JSON
2. 对每个关键元素调用 ui_match(target={bounds, text}, expected={...})
3. 汇总 verification 结果
4. 输出差异报告
```

### 8.2 无设计稿的查询流程

```
用户: "Avatar 按钮的宽度是多少"

Agent 流程:
1. 调用 ui_match(target={text:"Avatar"}, properties=["bounds.width"])
2. 返回结果
```

### 8.3 自然语言查询流程

```
用户: "找到绿色的按钮"

Agent 流程:
1. 理解"绿色" → 可能是 backgroundColor
2. 调用 ui_match(target={filter:{backgroundColor:"#*"}})
3. 或降级到 layout_dump + 自己遍历
```

---

## 9. 待讨论问题

1. ❓ **关系查询**: spacing/alignment 是否需要支持?
   - 如需支持,是否单独工具还是扩展 ui_match?

2. ❓ **批量操作**: 是否支持一次返回多个匹配元素?
   - 当前设计返回单个最佳匹配 + suggestions

3. ❓ **网格验证**: 是否需要专门的 grid 验证逻辑?
   - 或由 Agent 多次调用 ui_match 实现?

4. ❓ **容差配置**: 数值验证的容差如何配置?
   - 当前设计: expected 传精确值,返回 diff,由 Agent 判断

---

## 10. 下一步

1. ✅ 确定接口设计 (已完成)
2. ⏳ 评估实现复杂度
3. ⏳ 确定 MVP 范围
4. ⏳ 实现原型
5. ⏳ 编写测试用例

---

## 附录: 参考文件

- 对话记录: `/Users/wormchen/Downloads/new_verify.txt`
- Figma 示例: `/Users/wormchen/IdeaProjects/joox/JOOX_Android/docs/request_forms/装扮二期/figma/头像页/design_structure.json`
- Layout Dump 示例: `/Users/wormchen/IdeaProjects/joox/JOOX_Android/build/jugg/mcp_fetch/layout_dump/layout_1773213617400.json`
- MCP 使用文档: `docs/ai_knowledge/08_mcp_usage.md`
- MCP 设计文档: `docs/ai_knowledge/08_mcp_design.md`
- UI 验证规范: `docs/ai_knowledge/08_mcp_ui_verify_checklist.md`
- 初版设计: `docs/task/ui_fuzzy_match_design.md`
