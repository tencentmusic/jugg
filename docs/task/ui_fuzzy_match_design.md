# UI 模糊匹配工具设计方案

> 创建时间: 2026-03-12
> 状态: 设计阶段
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

### 1.2 典型场景

**场景 A: 有 Figma 设计稿**
```
用户: "检查这个页面是否符合 Figma 设计稿"

当前 Agent 流程:
1. 读取 Figma JSON (手动解析结构)
2. 对每个元素猜测 selector
3. 调用 layout_verify (经常失败)
4. 失败后降级到 screenshot
5. 看图判断 (不准确)

问题: 耗时长、准确率低、依赖 VLM
```

**场景 B: 无 Figma,自然语言查询**
```
用户: "检查绿色按钮的宽度"

当前 Agent 流程:
1. 调用 layout_dump
2. 手动遍历 JSON 查找"绿色"相关元素 (困难)
3. 或直接调用 screenshot 让 VLM 识别

问题: 缺少语义查询能力
```

### 1.3 对话记录要点

从 `/Users/wormchen/Downloads/new_verify.txt` 的讨论中得出:

1. **模糊匹配是必要的**
   - 解决"身份识别危机" (谁是谁的问题)
   - 是断言验证的前置基础
   - 提供速度优势 (~200ms vs 2-5s)

2. **不应该做传统 CV 算法**
   - 担心随着 VLM 发展很快过时
   - 但模糊匹配的角色是"索引",不是"理解"
   - 类似搜索引擎的倒排索引

3. **核心价值定位**
   - 模糊匹配 = 身份映射层
   - 连接 Figma 设计稿与 Android Layout Dump
   - 输出结构化数据,让 Agent 直接使用

---

## 2. 设计要点

### 2.1 设计原则

1. **极简**: 只做一件事,做好一件事
2. **通用**: 支持多种输入方式 (Figma/自然语言/位置)
3. **有效**: 解决核心痛点 (selector 查找)
4. **不大而全**: 不做断言判断,交给 Agent

### 2.2 核心思路

**不要做"全能验证工具",而是做"精准查找工具"**

```
工具职责:
- ui_match: 找到元素 + 返回信息

Agent 职责:
- 判断是否符合预期
- 生成报告
- 修复代码
```

### 2.3 与现有工具的关系

| 工具 | 保留/废弃 | 原因 |
|------|----------|------|
| layout_dump | **保留** | 底层数据源 |
| layout_verify | **待定** | 可能被 ui_match 替代 |
| eval_view | **保留** | 查询特殊属性 |
| screenshot | **保留** | 兜底方案 |
| tap | **保留** | 交互操作 |

---

## 3. 接口设计 - 20 个使用场景

### A. 完整页面验证 (有 Figma)

#### UC-1: 首次实现页面,全量对比
```json
{
  "projectDir": "/path/to/project",
  "figmaJson": "design.json",
  "mode": "verify_all"
}
```
**返回**: 差异报告 (哪些元素位置/尺寸/颜色不对)

**Agent 使用**:
```
用户: "检查这个页面是否符合 Figma 设计稿"
Agent: 调用 ui_match(mode=verify_all) → 获取完整差异报告 → 输出给用户
```

---

#### UC-2: 修改后验证特定区域
```json
{
  "projectDir": "/path/to/project",
  "figmaJson": "design.json",
  "targetNodes": ["34:12200", "34:12202", "34:12204", "34:12206"],
  "mode": "verify"
}
```
**返回**: 4 个 Tab 的验证结果

**Agent 使用**:
```
用户: "我改了 Tab 栏,检查是否符合设计"
Agent: 调用 ui_match(targetNodes=[...]) → 获取 Tab 验证结果 → 输出差异
```

---

#### UC-3: 只想知道哪些元素匹配上了
```json
{
  "projectDir": "/path/to/project",
  "figmaJson": "design.json",
  "mode": "match_only"
}
```
**返回**: 匹配表 (不做验证,只返回映射关系)

**Agent 使用**:
```
用户: "看看设计稿里的元素有哪些已经实现了"
Agent: 调用 ui_match(mode=match_only) → 获取映射表 → 列出已实现/未实现元素
```

---

### B. 指定控件查询 (无 Figma)

#### UC-4: 自然语言查找
```json
{
  "projectDir": "/path/to/project",
  "query": "绿色按钮"
}
```
**返回**: 匹配的元素信息

**Agent 使用**:
```
用户: "找到绿色的按钮"
Agent: 调用 ui_match(query="绿色按钮") → 获取元素信息 → 返回位置和属性
```

---

#### UC-5: 文本精确查找
```json
{
  "projectDir": "/path/to/project",
  "query": "Avatar",
  "matchType": "text"
}
```
**返回**: text="Avatar" 的元素

**Agent 使用**:
```
用户: "Avatar 按钮在哪"
Agent: 调用 ui_match(query="Avatar", matchType="text") → 返回位置
```

---

#### UC-6: 位置查找
```json
{
  "projectDir": "/path/to/project",
  "bounds": [0, 0, 100, 100]
}
```
**返回**: 该区域内的元素

**Agent 使用**:
```
用户: "屏幕左上角的按钮是什么"
Agent: 调用 ui_match(bounds=[0,0,100,100]) → 返回该区域元素列表
```

---

### C. 属性查询

#### UC-7: 查询单个属性
```json
{
  "projectDir": "/path/to/project",
  "query": "Avatar",
  "properties": ["bounds.width"]
}
```
**返回**: `{ "bounds.width": "67dp" }`

**Agent 使用**:
```
用户: "Avatar 按钮多宽"
Agent: 调用 ui_match(query="Avatar", properties=["bounds.width"]) → 返回 "67dp"
```

---

#### UC-8: 查询多个属性
```json
{
  "projectDir": "/path/to/project",
  "query": "Avatar",
  "properties": ["bounds.width", "bounds.height", "backgroundColor"]
}
```
**返回**: `{ "bounds.width": "67dp", "bounds.height": "46dp", "backgroundColor": "#FF09DE6E" }`

**Agent 使用**:
```
用户: "Avatar 按钮的宽度、高度、颜色"
Agent: 调用 ui_match(properties=[...]) → 返回所有属性
```

---

#### UC-9: 查询特殊属性 (需 eval_view)
```json
{
  "projectDir": "/path/to/project",
  "query": "Avatar",
  "properties": ["cornerRadius"]
}
```
**返回**: `{ "cornerRadius": "15dp" }`

**内部实现**: 自动调用 eval_view

**Agent 使用**:
```
用户: "Avatar 按钮的圆角半径"
Agent: 调用 ui_match(properties=["cornerRadius"]) → 工具内部调用 eval_view → 返回结果
```

---

### D. 关系验证

#### UC-10: 两个元素的间距
```json
{
  "projectDir": "/path/to/project",
  "query": "Avatar",
  "relation": {
    "type": "spacing",
    "target": "App",
    "axis": "x"
  }
}
```
**返回**: `{ "spacing": "18dp" }`

**Agent 使用**:
```
用户: "Avatar 和 App 按钮之间的间距"
Agent: 调用 ui_match(relation={type:"spacing", target:"App"}) → 返回间距值
```

---

#### UC-11: 元素是否对齐
```json
{
  "projectDir": "/path/to/project",
  "queries": ["Avatar", "App", "Comment", "Suite"],
  "relation": {
    "type": "alignment",
    "axis": "y"
  }
}
```
**返回**: `{ "aligned": true, "centerY": [293, 293, 293, 293] }`

**Agent 使用**:
```
用户: "4 个 Tab 是否水平对齐"
Agent: 调用 ui_match(queries=[...], relation={type:"alignment"}) → 返回对齐结果
```

---

### E. 列表/网格验证

#### UC-12: 网格布局检查
```json
{
  "projectDir": "/path/to/project",
  "figmaNode": {
    "id": "42:18053",
    "gridLayout": {
      "columns": 3,
      "itemSize": "109x151dp"
    }
  },
  "mode": "verify_grid"
}
```
**返回**: `{ "columns": 3, "itemCount": 12, "itemSize": "109x151dp", "match": true }`

**Agent 使用**:
```
用户: "装扮列表是否是 3 列布局"
Agent: 调用 ui_match(mode=verify_grid) → 返回网格验证结果
```

---

#### UC-13: 列表项间距
```json
{
  "projectDir": "/path/to/project",
  "figmaNode": {
    "gridLayout": {
      "horizontalSpacing": "8dp",
      "verticalSpacing": "15.5dp"
    }
  },
  "mode": "verify_spacing"
}
```
**返回**: `{ "horizontalSpacing": "8dp", "verticalSpacing": "16dp", "diff": "+0.5dp" }`

**Agent 使用**:
```
用户: "列表项之间的间距是否正确"
Agent: 调用 ui_match(mode=verify_spacing) → 返回间距对比结果
```

---

### F. 多元素批量查询

#### UC-14: 批量获取属性
```json
{
  "projectDir": "/path/to/project",
  "filter": {
    "className": "Button"
  },
  "properties": ["bounds.width"]
}
```
**返回**:
```json
[
  { "selector": {"text": "Avatar"}, "bounds.width": "67dp" },
  { "selector": {"text": "App"}, "bounds.width": "53dp" }
]
```

**Agent 使用**:
```
用户: "所有按钮的宽度"
Agent: 调用 ui_match(filter={className:"Button"}) → 返回所有按钮宽度列表
```

---

#### UC-15: 查找所有匹配元素
```json
{
  "projectDir": "/path/to/project",
  "filter": {
    "className": "ImageView"
  },
  "mode": "count"
}
```
**返回**: `{ "count": 15, "elements": [...] }`

**Agent 使用**:
```
用户: "页面上有几个 ImageView"
Agent: 调用 ui_match(filter={className:"ImageView"}, mode="count") → 返回数量
```

---

### G. 容错与降级

#### UC-16: 低置信度时返回候选
```json
{
  "projectDir": "/path/to/project",
  "query": "Avater"  // 拼写错误
}
```
**返回**:
```json
{
  "matched": null,
  "confidence": 0.65,
  "suggestions": [
    { "text": "Avatar", "confidence": 0.85 }
  ]
}
```

**Agent 使用**:
```
Agent: 调用 ui_match(query="Avater") → 获取建议 → 提示用户可能是 "Avatar"
```

---

#### UC-17: 匹配失败时的提示
```json
{
  "projectDir": "/path/to/project",
  "figmaNode": {
    "text": "Close",
    "bounds": [350, 50, 380, 80]
  }
}
```
**返回**:
```json
{
  "matched": null,
  "reason": "No element found in target area",
  "nearbyElements": [
    { "text": "Back", "bounds": [20, 50, 50, 80] }
  ]
}
```

**Agent 使用**:
```
Agent: 调用 ui_match(figmaNode={...}) → 匹配失败 → 提示附近有 "Back" 按钮
```

---

### H. 性能优化场景

#### UC-18: 缓存 layout_dump
```json
// 第一次调用
{
  "projectDir": "/path/to/project",
  "query": "Avatar"
}

// 第二次调用 (同一页面)
{
  "projectDir": "/path/to/project",
  "query": "App",
  "useCache": true
}
```
**内部实现**: 复用上次的 layout_dump

**Agent 使用**:
```
Agent: 连续查询多个元素时,第二次起使用 useCache=true 提速
```

---

#### UC-19: 仅返回 selector (不返回属性)
```json
{
  "projectDir": "/path/to/project",
  "query": "Avatar",
  "returnOnly": "selector"
}
```
**返回**: `{ "selector": { "text": "Avatar", "className": "KRView" } }`

**Agent 使用**:
```
用户: "找到 Avatar 按钮,我要点击它"
Agent:
1. 调用 ui_match(returnOnly="selector")
2. 获取 selector
3. 调用 tap(text="Avatar", className="KRView")
```

---

### I. 调试场景

#### UC-20: 查看匹配详情
```json
{
  "projectDir": "/path/to/project",
  "figmaNode": {
    "id": "34:12200",
    "text": "Avatar"
  },
  "debug": true
}
```
**返回**:
```json
{
  "matched": {...},
  "confidence": 0.92,
  "debugInfo": {
    "iou_score": 0.85,
    "text_score": 1.0,
    "type_score": 0.8,
    "candidates": [
      { "selector": {...}, "score": 0.92 },
      { "selector": {...}, "score": 0.65 }
    ]
  }
}
```

**Agent 使用**:
```
开发者调试时: 使用 debug=true 查看匹配算法的详细得分
```

---

## 4. 讨论进度

### 4.1 已确定

1. ✅ 模糊匹配是必要的 (解决身份识别问题)
2. ✅ 定位为"查找工具",不做断言判断
3. ✅ 支持 Figma + 自然语言 + 位置查询
4. ✅ 内部自动调用 layout_dump + eval_view

### 4.2 待讨论

1. ❓ **工具数量**: 一个工具 (ui_match) 还是多个工具 (ui_find, ui_verify, ui_relation)?
2. ❓ **mode 参数**: 是否需要多个 mode (match_only, verify, verify_all, verify_grid, count)?
3. ❓ **关系查询**: spacing/alignment 是否应该独立成单独的工具?
4. ❓ **批量操作**: filter + properties 的批量查询是否必要?
5. ❓ **layout_verify 去留**: 是否完全废弃 layout_verify?

### 4.3 核心问题

从 20 个场景观察到:

**复杂度来源**:
- 输入方式多样: query/figmaNode/bounds/filter/queries
- 返回内容可选: selector/properties/relation/debugInfo
- 模式切换: match_only/verify/verify_all/verify_grid/count

**简化方向**:
- 是否可以用"输入类型"自动推断 mode?
- 是否可以拆分成 2-3 个职责单一的工具?
- 哪些场景是**必须支持**的,哪些可以**暂不支持**?

---

## 5. 下一步

1. **确定工具数量和职责边界**
2. **精简参数设计** (去掉不必要的 mode)
3. **定义最小可用场景集** (MVP)
4. **设计返回格式规范**
5. **评估实现复杂度**

---

## 附录: 参考文件

- 对话记录: `/Users/wormchen/Downloads/new_verify.txt`
- Figma 示例: `/Users/wormchen/IdeaProjects/joox/JOOX_Android/docs/request_forms/装扮二期/figma/头像页/design_structure.json`
- Layout Dump 示例: `/Users/wormchen/IdeaProjects/joox/JOOX_Android/build/jugg/mcp_fetch/layout_dump/layout_1773213617400.json`
- MCP 使用文档: `docs/ai_knowledge/08_mcp_usage.md`
- MCP 设计文档: `docs/ai_knowledge/08_mcp_design.md`
- UI 验证规范: `docs/ai_knowledge/08_mcp_ui_verify_checklist.md`
