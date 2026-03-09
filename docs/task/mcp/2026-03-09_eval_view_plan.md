# eval_view + layout_verify 精简方案

> 日期：2026-03-09
> 状态：草稿
> 关联文档：`2026-03-09_mcp_to_ui_test_strategy.md`、`layout_verify_eval/`
> 已读文档：`00_overview.md`、`97_ai_usage.md`、`08_mcp_design.md`、`08_mcp_usage.md`
> 代码依据：`ViewHierarchyServer.java`、`LayoutVerifier.java`、`ElementFinder.java`、`MatchedElement.java`、`LayoutVerifyMcpToolAction.kt`、`McpToolSchemas.kt`

---

## 1. 背景与问题

### 1.1 当前痛点

| # | 痛点 | 依据 |
|---|------|------|
| 1 | `layout_verify` schema 持续膨胀但仍无法覆盖足够多的场景 | 25 种属性 + 6 种 check type，每新增一个属性需要改动 3~5 个文件（`LayoutVerifyMcpToolAction.kt`、`LayoutVerifier.java`、MCP schema、Skill 文档、测试用例） |
| 2 | 通用的 `layout_dump` 消耗大量上下文，且 Agent 需要先学习其结构才能验证 | 完整 dump JSON 可达 2K~10K tokens；Agent 必须解析树结构、理解节点字段 |
| 3 | `screenshot` 在属性级别验证上精度有限 | 无法准确判断精确的颜色值、字号大小、padding 值 |
| 4 | `layout_verify` 的属性覆盖率在真实场景中仅约 72%，已达瓶颈 | `real_scene_analysis_report.md`：Cap-16（自定义属性）、Cap-17（tint/colorFilter）、Cap-22（cornerRadius）均不可达 |
| 5 | `alignment.direction` 语义反直觉（无 Skill 时错误率 40~60%） | `checks_complexity_eval.md` 风险 #1：`direction:"vertical"` 实际检查的是 X 轴中心对齐 |

### 1.2 核心洞察

Agent 已经掌握 Android View API（公开训练数据）。与其设计一个不断膨胀的私有 MCP schema，**不如让 Agent 通过一个轻量反射桥直接调用 View 的 getter 方法**。

ViewHierarchyServer 已通过 `MatchedElement.view` 持有**活跃的 `View` 引用**。"查找 View → 在主线程执行 → 返回结果"的基础设施已完全就绪。

---

## 2. 方案概览

### 2.1 双工具架构

```
┌──────────────────────────────────────────────────────┐
│  Agent 验证工具箱                                      │
├──────────────────────────┬───────────────────────────┤
│  layout_verify（精简后）  │  eval_view（新增）          │
│  仅处理空间关系           │  通用属性查询               │
│                          │                           │
│  · spacing（间距）        │  · 任意 View getter 方法   │
│  · alignment（对齐）      │  · 方法链调用               │
│  · overlap（重叠）        │  · 子类转型                 │
│  · containment（包含）    │  · 批量表达式查询           │
│  · order（排列顺序）      │  · 返回原始值               │
│                          │                           │
│  需要 2 个 target         │  只需 1 个 target           │
│  Jugg 负责坐标运算        │  Jugg 负责反射执行          │
│  返回 PASS/FAIL          │  返回原始值                 │
│  （Agent 自己做坐标计算    │  （Agent 根据 Android SDK  │
│   容易出错）              │   知识自行判断 PASS/FAIL）  │
└──────────────────────────┴───────────────────────────┘
```

**分工原则：**
- `layout_verify`：需要**两个 View 的坐标**进行计算的场景——Agent 自己做这种运算容易出错。
- `eval_view`：查询**单个 View 的属性**——Agent 了解 Android SDK，直接查询即可。

### 2.2 覆盖率影响

| 场景 | 改造前（仅 layout_verify） | 改造后（layout_verify + eval_view） |
|------|:--:|:--:|
| 标准属性（text、visibility、clickable、enabled） | ✅ | ✅（eval_view） |
| textColor、backgroundColor | ✅/❌ | ✅（eval_view） |
| textSize、alpha、padding、bounds | ✅ | ✅（eval_view） |
| **maxLines、ellipsize**（Cap-13） | ❌ | **✅** |
| **tintColor、colorFilter**（Cap-17） | ❌ | **✅** |
| **cornerRadius**（Cap-22） | ❌ | **✅** |
| **自定义 View getter**（Cap-16） | ❌ | **✅** |
| **Drawable 子属性** | ❌ | **✅**（通过类型转换 + getter） |
| 间距、对齐、重叠、包含、排列顺序 | ✅ | ✅（layout_verify） |
| Drawable 内部 pathData/viewport | ❌ | ❌（超出范围） |

**覆盖率：~72% → ~95%+**

---

## 3. `eval_view` 详细设计

### 3.1 MCP 工具定义

**工具名称：** `eval_view`

**输入 schema：**

```json
{
  "type": "object",
  "properties": {
    "target": {
      "type": "object",
      "description": "元素选择器。与 layout_verify/find_elements 使用相同的选择器格式。",
      "properties": {
        "resourceId": { "type": "string" },
        "text": { "type": "string" },
        "contentDesc": { "type": "string" },
        "className": { "type": "string" }
      }
    },
    "expressions": {
      "type": "array",
      "description": "要在匹配的 View 上执行的 getter 方法表达式列表。每个表达式是 View 对象上的方法调用链。仅允许 getter/查询方法（无副作用）。使用 Android SDK 的 View/TextView/ImageView 等公开方法。示例：'getText()'、'getCurrentTextColor()'、'getBackground().getClass().getSimpleName()'、'isEnabled()'、'getMaxLines()'",
      "items": { "type": "string" },
      "minItems": 1,
      "maxItems": 20
    }
  },
  "required": ["target", "expressions"]
}
```

**输出 schema：**

```json
{
  "status": "ok",
  "data": {
    "className": "android.widget.AppCompatTextView",
    "resourceId": "tv_mcp_title",
    "values": [
      { "expression": "getText()", "value": "MCP Test Page", "type": "string" },
      { "expression": "getCurrentTextColor()", "value": -14606047, "type": "int" },
      { "expression": "getMaxLines()", "value": 2147483647, "type": "int" }
    ]
  }
}
```

**错误场景：**

```json
// 目标未找到
{ "status": "error", "message": "未找到匹配选择器 {resourceId: 'xxx'} 的元素", "data": { "candidates": [...] } }

// 表达式解析错误
{ "status": "ok", "data": { "values": [
  { "expression": "getText()", "value": "Hello", "type": "string" },
  { "expression": "invalidMethod()", "value": null, "type": "error", "error": "NoSuchMethodException: invalidMethod()" }
] } }

// 多个匹配
{ "status": "error", "message": "匹配到多个元素（2 个）。请缩小选择器范围或添加 className。", "data": { "matches": [...] } }
```

### 3.2 表达式语言规范

#### 支持的语法

```
expression := method_call ("." method_call)*
method_call := method_name "()"
             | method_name "(" literal ")"

method_name := Java 标识符（如 "getText"、"getCurrentTextColor"、"getClass"）
literal := integer | float | string_literal
integer := [0-9]+
float := [0-9]+"."[0-9]+
string_literal := '"' [^"]* '"'
```

#### 示例（从简单到复杂）

```
# 基本 getter（无参数）
getText()                              → "MCP Test Page"
getCurrentTextColor()                  → -14606047
isEnabled()                            → true
getAlpha()                             → 1.0
getVisibility()                        → 0    (View.VISIBLE=0)
getWidth()                             → 1080  (px)
getHeight()                            → 144   (px)
getPaddingLeft()                       → 48    (px)

# 方法链
getText().toString()                   → "MCP Test Page"
getText().length()                     → 13
getBackground().getClass().getSimpleName() → "GradientDrawable"

# 不需要类型转换——使用方法链即可
# Agent 知道 TextView 有 getText()，因此用已知 TextView 的 resourceId 来定位

# Drawable 属性访问（之前不可达的场景）
getMaxLines()                          → 1
getEllipsize().name()                  → "END"
getImageTintList().getDefaultColor()   → -16777216
getBackground().getCornerRadius()      → 36.0   (GradientDrawable)
getBackground().getColor().getDefaultColor() → -1   (ColorStateList)
getTypeface().isBold()                 → true
getLetterSpacing()                     → 0.0
getLineHeight()                        → 64
```

#### 安全约束

1. **仅限 getter**：方法名必须匹配以下规则之一：
   - 以 `get`、`is`、`has`、`can`、`should` 开头
   - 在显式白名单中：`toString`、`length`、`name`、`ordinal`、`size`、`isEmpty`、`hashCode`、`intValue`、`floatValue`、`longValue`、`doubleValue`
2. **黑名单**：以下开头的方法被禁止：`set`、`remove`、`add`、`clear`、`delete`、`put`、`write`、`post`、`send`、`dispatch`、`perform`、`request`、`invoke`、`execute`、`notify`、`register`、`unregister`、`attach`、`detach`
3. **最大链深度**：5（如 `a().b().c().d().e()` 为上限）
4. **执行超时**：每个表达式 1 秒（复用主线程调度器，通过 `Future.get(1, SECONDS)` 实现单表达式超时）
5. **空值安全**：如果链中任何中间结果为 null，返回 `{ "value": null, "type": "null" }` 而非 NPE

#### 值序列化

| Java 类型 | JSON 类型 | 格式 |
|-----------|-----------|------|
| `String` / `CharSequence` | `"string"` | 原始字符串值 |
| `int` / `Integer` | `"int"` | 数字 |
| `long` / `Long` | `"long"` | 数字 |
| `float` / `Float` | `"float"` | 数字（3 位小数） |
| `double` / `Double` | `"double"` | 数字（6 位小数） |
| `boolean` / `Boolean` | `"boolean"` | `true` / `false` |
| `Enum` | `"string"` | `enum.name()` |
| `null` | `"null"` | `null` |
| 其他对象 | `"string"` | `object.toString()`（截断至 500 字符） |

### 3.3 App 端实现

位置：`jvmti_agent/src/main/java/com/sickworm/intellij/jugg/viewhierarchy/`

#### 新增文件

| 文件 | 职责 | 预估代码量 |
|------|------|-----------|
| `ViewExpressionEvaluator.java` | 解析表达式字符串 → 反射方法链调用 | ~150 行 |

#### 修改文件

| 文件 | 改动内容 | 预估代码量 |
|------|---------|-----------|
| `ViewHierarchyServer.java` | 在 `dispatchRequest()` 中新增 `case "eval_view"` + `doEvalView()` 处理器 | ~50 行 |

#### `doEvalView()` 实现大纲

```java
private JSONObject doEvalView(JSONObject params) {
    try {
        // 1. 从 target 中提取选择器
        JSONObject targetObj = params.getJSONObject("target");
        String text = optString(targetObj, "text");
        String resourceId = optString(targetObj, "resourceId");
        String contentDesc = optString(targetObj, "contentDesc");
        String className = optString(targetObj, "className");

        // 2. 查找元素
        List<MatchedElement> matches = elementFinder.find(
            text, resourceId, contentDesc, className, true);
        
        if (matches.isEmpty()) {
            return error("未找到匹配的元素。", buildCandidates());
        }
        if (matches.size() > 1) {
            return error("匹配到多个元素（" + matches.size() + " 个）。",
                         buildMatchList(matches));
        }

        MatchedElement target = matches.get(0);
        View view = target.view;

        // 3. 逐一执行表达式
        JSONArray expressions = params.getJSONArray("expressions");
        JSONArray values = new JSONArray();
        
        for (int i = 0; i < expressions.length(); i++) {
            String expr = expressions.getString(i);
            try {
                ViewExpressionEvaluator.Result result =
                    ViewExpressionEvaluator.evaluate(view, expr);
                values.put(new JSONObject()
                    .put("expression", expr)
                    .put("value", result.jsonValue)
                    .put("type", result.typeName));
            } catch (Exception e) {
                values.put(new JSONObject()
                    .put("expression", expr)
                    .put("value", JSONObject.NULL)
                    .put("type", "error")
                    .put("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        // 4. 构建响应
        JSONObject data = new JSONObject();
        data.put("className", view.getClass().getName());
        data.put("resourceId", ViewNode.getResourceIdString(view));
        data.put("values", values);
        return ok(data);

    } catch (Throwable t) {
        return error("eval_view 执行失败：" + t.getMessage(), null);
    }
}
```

#### `ViewExpressionEvaluator` 核心逻辑

```java
public class ViewExpressionEvaluator {

    public static Result evaluate(Object root, String expression) throws Exception {
        // 1. 按 "." 分割表达式（尊重括号内容）
        //    例如 "getBackground().getCornerRadius()" → ["getBackground()", "getCornerRadius()"]
        List<MethodCall> chain = parseChain(expression);
        
        // 2. 校验链深度
        if (chain.size() > MAX_CHAIN_DEPTH) {
            throw new IllegalArgumentException("链深度超过最大值 " + MAX_CHAIN_DEPTH);
        }
        
        // 3. 执行链
        Object current = root;
        for (MethodCall call : chain) {
            if (current == null) {
                return new Result(null, "null");
            }
            // 4. 安全检查
            validateMethodName(call.methodName);
            // 5. 反射调用
            Method method = findMethod(current.getClass(), call.methodName, call.argTypes);
            method.setAccessible(true);
            current = method.invoke(current, call.args);
        }
        
        // 6. 序列化结果
        return serializeValue(current);
    }
    
    private static void validateMethodName(String name) {
        // 检查黑名单
        for (String prefix : BLOCKED_PREFIXES) {
            if (name.startsWith(prefix)) {
                throw new SecurityException("方法 '" + name + "' 被禁止（有副作用）");
            }
        }
        // 检查 getter/查询模式或白名单
        boolean allowed = false;
        for (String prefix : ALLOWED_PREFIXES) {
            if (name.startsWith(prefix)) { allowed = true; break; }
        }
        if (!allowed && !ALLOWLIST.contains(name)) {
            throw new SecurityException("方法 '" + name + "' 不在 getter 白名单中");
        }
    }
}
```

### 3.4 IDE 端实现

位置：`main/src/main/java/com/sickworm/intellij/jugg/mcp/actions/`

#### 新增文件

| 文件 | 职责 | 预估代码量 |
|------|------|-----------|
| `EvalViewMcpToolAction.kt` | MCP 工具注册、参数校验、分发到 ViewHierarchyClient | ~80 行 |

#### 修改文件

| 文件 | 改动内容 |
|------|---------|
| `McpToolSchemas.kt` | 新增 `eval_view` schema 常量 |
| `ViewHierarchyClient.kt`（或等效文件） | 新增 `evalView()` 方法，发送 `{"action":"eval_view",...}` 到 server |

---

## 4. `layout_verify` 精简

### 4.1 保留的部分（空间关系）

这些 check type 涉及**两个 View 的坐标计算**，Agent 自己难以完成：

| Check type | 语义 | 保留原因 |
|-----------|------|---------|
| `spacing` | 两个元素之间的间距（dp） | 需要两个 bounds + dp 转换 + 容差逻辑 |
| `alignment` | 中心对齐检查（x/y） | 需要两个中心点，像素级阈值（≤2px） |
| `overlap` | 边界框相交检测 | 需要各 4 条边的交叉乘积 |
| `containment` | 子元素完全在父元素内部 | 需要比较全部 4 条边 |
| `order` | 渲染顺序（前/后） | 需要两个位置 + 轴向 |

**这 5 种类型的 schema 保持不变。**

### 4.2 移除的部分（`type=property`）

所有属性断言迁移到 `eval_view`：

| 从 layout_verify 移除 | eval_view 等价写法 |
|----------------------|-------------------|
| `exists` | `eval_view` 目标未找到 = 不存在 |
| `visibility` | `getVisibility()` → 0/4/8（VISIBLE/INVISIBLE/GONE） |
| `clickable` | `isClickable()` |
| `enabled` | `isEnabled()` |
| `text` | `getText().toString()` |
| `textColor` | `getCurrentTextColor()` |
| `backgroundColor` | `getBackground()` 链式调用 |
| `alpha` | `getAlpha()` |
| `textSizeSp` | `getTextSize()`（返回 px，Agent 用 density 转换） |
| `bounds.width/height/left/top/right/bottom` | `getWidth()`、`getHeight()`、`getLeft()`、`getTop()` 等（px；Agent 用 density 转换） |
| `padding.left/top/right/bottom` | `getPaddingLeft()`、`getPaddingTop()` 等（px） |

### 4.3 代码移除范围

| 文件 | 移除内容 | 保留内容 |
|------|---------|---------|
| `LayoutVerifyMcpToolAction.kt` | 所有 `type=property` 处理逻辑、`PROPERTY_SCHEMA_VALUES`、`PROPERTY_ALIASES`、`LIVE_ONLY_PROPERTIES`、`assertDumpNode()`、属性相关的 `buildPropertyResult()` | `type=spacing/alignment/overlap/containment/order` 处理逻辑、关系类断言逻辑 |
| `LayoutVerifier.java` | `executeAssert()` 方法（所有属性断言）、所有 `assert*()` 辅助方法 | `executeRelation()`（spacing/alignment/overlap/containment/order）、`verify()` 入口（简化后） |
| `McpToolSchemas.kt` | `property` 从 check type 枚举中移除、`property`/`op`/`value` 字段 | `spacing`/`alignment`/`overlap`/`containment`/`order` 相关字段 |
| Skill 文档 `guide_layout_verify_assertion.md` | 属性示例、陷阱 #4/#6/#9（color/live-only/backgroundColor） | 关系示例、陷阱 #1/#3（容差/对齐方向） |

### 4.4 预估代码缩减

| 文件 | 当前代码量 | 精简后 | 缩减比例 |
|------|:---:|:---:|:---:|
| `LayoutVerifyMcpToolAction.kt` | ~1237 行 | ~600 行 | **-52%** |
| `LayoutVerifier.java` | ~606 行 | ~300 行 | **-50%** |
| MCP schema（layout_verify 部分） | ~150 行 | ~60 行 | **-60%** |

### 4.5 精简后的 layout_verify schema（目标状态）

```json
{
  "type": "object",
  "properties": {
    "checks": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "target": { "$ref": "#/definitions/selector" },
          "target2": { "$ref": "#/definitions/selector" },
          "type": {
            "type": "string",
            "enum": ["spacing", "alignment", "overlap", "containment", "order"],
            "description": "空间关系检查类型。所有类型均需要 target + target2。"
          },
          "direction": {
            "type": "string",
            "enum": ["x", "y"],
            "description": "轴向。spacing：x=水平间距，y=垂直间距。alignment：x=水平中心对齐，y=垂直中心对齐。order：x=水平排列顺序（从左到右），y=垂直排列顺序（从上到下）。"
          },
          "expected": { "type": "number", "description": "期望的间距值（dp），仅 spacing 使用。" },
          "tolerance": { "type": "number", "description": "容差值（dp），仅 spacing 使用。默认 0。" }
        },
        "required": ["target", "target2", "type"]
      }
    }
  }
}
```

**关键改进：**
1. `direction` 枚举从 `"vertical"/"horizontal"` 改为 **`"x"/"y"`** — 消除 #1 混淆点
2. `type` 枚举从 6 种缩减至 5 种 — 不再有 `property` 类型
3. 不再有 `op`、`value`、`property`、`unit` 字段 — 大幅简化
4. `target2` 现在**始终必填**（所有剩余类型都需要）

---

## 5. Agent 工作流示例

### 5.1 典型属性验证（原 layout_verify，现 eval_view）

**改造前（layout_verify）：**
```
Agent → layout_verify(checks=[
  {target:{resourceId:"tv_title"}, type:"property", property:"textColor", op:"eq", value:"#FF1976D2"},
  {target:{resourceId:"tv_title"}, type:"property", property:"textSizeSp", op:"eq", value:20}
])
→ Agent 需要学习：ARGB 格式、live-only 模式、property 枚举、op 枚举
```

**改造后（eval_view）：**
```
Agent → eval_view(
  target: {resourceId: "tv_title"},
  expressions: ["getCurrentTextColor()", "getTextSize()"]
)
→ { values: [{value: -14606047, type: "int"}, {value: 60.0, type: "float"}] }
→ Agent 知道：-14606047 = 0xFF1976D2 ✓；60.0px / 3.0 density = 20sp ✓
```

**Agent 学习成本：零。** 它已经知道 `getCurrentTextColor()` 返回 int，`getTextSize()` 返回 px。

### 5.2 之前不可能的场景（现在通过 eval_view 实现）

**Cap-13：maxLines + ellipsize**
```
eval_view(
  target: {resourceId: "tv_long_text"},
  expressions: ["getMaxLines()", "getEllipsize().name()"]
)
→ { values: [{value: 1, type: "int"}, {value: "END", type: "string"}] }
```

**Cap-17：ImageView tint**
```
eval_view(
  target: {resourceId: "iv_icon"},
  expressions: ["getImageTintList().getDefaultColor()"]
)
→ { values: [{value: -16777216, type: "int"}] }  // 0xFF000000 = 黑色
```

**Cap-22：GradientDrawable cornerRadius**
```
eval_view(
  target: {resourceId: "card_bg"},
  expressions: ["getBackground().getCornerRadius()"]
)
→ { values: [{value: 36.0, type: "float"}] }
```

**Cap-16：自定义 View getter**
```
eval_view(
  target: {resourceId: "custom_switch"},
  expressions: ["getBackColor()"]  // 自定义 View 的公开 getter
)
→ { values: [{value: -1, type: "int"}] }  // 0xFFFFFFFF = 白色
```

### 5.3 空间关系验证（保留在 layout_verify 中）

```
layout_verify(checks=[
  {target:{resourceId:"tv_title"}, target2:{resourceId:"btn_login"}, type:"spacing", direction:"y", expected:12, tolerance:3},
  {target:{resourceId:"btn_a"}, target2:{resourceId:"btn_b"}, type:"alignment", direction:"x"},
  {target:{resourceId:"btn_a"}, target2:{resourceId:"btn_b"}, type:"overlap"},
  {target:{resourceId:"btn_a"}, target2:{resourceId:"container"}, type:"containment"},
  {target:{resourceId:"tv_title"}, target2:{resourceId:"btn_login"}, type:"order", direction:"y"}
])
```

### 5.4 完整 UI 验收流程（组合使用两个工具）

```
# 步骤 1：使用 eval_view 进行属性验证
eval_view(
  target: {resourceId: "tv_title"},
  expressions: [
    "getText().toString()",
    "getCurrentTextColor()",
    "getTextSize()",
    "getVisibility()",
    "isEnabled()"
  ]
)

# 步骤 2：使用 layout_verify 进行空间关系验证
layout_verify(checks=[
  {target:{resourceId:"tv_title"}, target2:{resourceId:"btn_submit"}, type:"spacing", direction:"y", expected:16, tolerance:2},
  {target:{resourceId:"tv_title"}, target2:{resourceId:"btn_submit"}, type:"alignment", direction:"x"}
])
```

---

## 6. 迁移计划

### 阶段一：构建 `eval_view`（优先级：P0）

| 任务 | 位置 | 工作量 | 详情 |
|------|------|--------|------|
| 实现 `ViewExpressionEvaluator.java` | `jvmti_agent/` | 中等 | 表达式解析器 + 反射 + 安全检查（~150 行） |
| 在 `ViewHierarchyServer.java` 中添加 `doEvalView()` | `jvmti_agent/` | 小 | 新增 action 处理器（~50 行） |
| 实现 `EvalViewMcpToolAction.kt` | `mcp/actions/` | 小 | MCP 工具注册 + 参数校验（~80 行） |
| 在 `McpToolSchemas.kt` 中添加 `eval_view` schema | `mcp/actions/` | 小 | Schema 常量 |
| 在 `ViewHierarchyClient` 中添加 `evalView()` | `viewhierarchy/` | 小 | 客户端方法 |
| `ViewExpressionEvaluator` 单元测试 | `jvmti_agent/test/` | 中等 | 表达式解析 + 安全校验 |
| `EvalViewMcpToolAction` 单元测试 | `main/test/` | 小 | 参数校验 |

**预估总量：~400 行新代码 + 测试**

### 阶段二：精简 `layout_verify`（优先级：P1，在阶段一验证通过后进行）

| 任务 | 位置 | 工作量 | 详情 |
|------|------|--------|------|
| 从 `LayoutVerifyMcpToolAction.kt` 中移除 `type=property` | `mcp/actions/` | 中等 | 移除约 600 行属性断言逻辑 |
| 从 `LayoutVerifier.java` 中移除 `executeAssert()` | `jvmti_agent/` | 中等 | 移除约 300 行，保留 `executeRelation()` |
| 更新 MCP schema | `mcp/actions/` | 小 | 移除 property 枚举、op、value 字段 |
| **将 `direction` 枚举从 `vertical/horizontal` 改为 `x/y`** | 两端 | 小 | 修复 #1 混淆点 |
| 更新 Skill 文档 `guide_layout_verify_assertion.md` | `docs/skills/` | 小 | 移除属性示例，更新关系示例 |
| 更新测试用例 | `main/test/` | 中等 | 移除属性断言测试，保留关系测试 |
| 更新 `plan.md` 测试计划 | `docs/task/` | 小 | 重新分类用例：属性 → eval_view，关系 → layout_verify |

**预估总量：~900 行移除，~100 行修改**

### 阶段三：优化与加固（优先级：P2）

| 任务 | 详情 |
|------|------|
| 在 `eval_view` 响应中添加 density 信息 | 返回 `{"density": 3.0}` 使 Agent 无需额外调用即可将 px 转换为 dp |
| 在 Skill 文档中添加常用辅助表达式 | 预置颜色十六进制转换、dp 计算等表达式模式 |
| 考虑 `eval_view` 多目标批量模式 | `targets: [{selector, expressions}, ...]` 以减少往返次数 |
| 性能分析 | 确保每个表达式的反射开销 < 50ms |

---

## 7. 风险评估

| 风险 | 严重性 | 概率 | 缓解措施 |
|------|--------|------|---------|
| 反射安全：Agent 生成有副作用的表达式 | 中 | 低 | 严格的 getter-only 白名单 + 黑名单；执行超时 1s |
| 特定 View 子类上方法未找到 | 低 | 中 | 返回清晰的错误信息和可用方法列表；Agent 重试 |
| 方法链中间结果为 null | 低 | 高 | 空值安全求值；返回 `{type:"null"}` 而非 NPE |
| Agent 对 eval_view 返回结果中 px vs dp 产生困惑 | 中 | 中 | 在响应中包含 `density`；在 Skill 文档中说明 |
| layout_verify direction 变更（`vertical/horizontal` → `x/y`）破坏现有 Skill 提示词 | 中 | 确定 | 阶段二同步更新 Skill 文档；考虑向后兼容别名 |
| 性能：深层反射链阻塞主线程 | 低 | 低 | 最大链深度 = 5；单表达式超时 = 1s |

---

## 8. 成功标准

| 指标 | 目标 | 衡量方式 |
|------|------|---------|
| 真实场景覆盖率（基于 `real_scene_analysis_report.md` 的 25 个能力项） | ≥ 92%（23/25） | 用 eval_view 重新评估每个 Cap |
| Agent 使用 eval_view 的学习成本 | 零私有 API 学习 | Agent 仅使用 Android SDK 方法名 |
| layout_verify schema 大小 | ≤ 60 行 | 精简后的 schema |
| eval_view 首次调用成功率 | ≥ 90% | Agent 首次调用就使用正确的 getter 方法 |
| eval_view 单次调用往返延迟 | < 200ms | 包含 socket + 主线程 + 反射 |
| layout_verify 代码缩减量 | ≥ 50% | 代码行数对比 |

---

## 9. 与替代方案的对比

| 方案 | 属性覆盖率 | Agent 学习成本 | Jugg 工程量 | 持续维护成本 |
|------|:-:|:-:|:-:|:-:|
| 持续扩展 `layout_verify` | ~72%（瓶颈） | 高（私有 schema） | 高（每个属性 3~5 个文件） | 线性增长 |
| 混合 Android Test（Espresso） | ~95% | 零 | **非常高**（编译管线改造） | 中 |
| 动态代码编译 | ~99% | 零 | **非常高**（运行时编译器） | 高（安全性） |
| **`eval_view` 反射（本方案）** | **~95%** | **零** | **小（~400 行）** | **接近零** |

---

## 10. 附录：能力覆盖重新评估

基于 eval_view 重新评估 `real_scene_analysis_report.md` 的 25 个能力项：

| Cap ID | 能力 | 改造前（layout_verify） | 改造后（eval_view） | 变化 |
|--------|------|:---:|:---:|:---:|
| Cap-01 | textColor 运行时断言 | 是 | 是（eval_view） | — |
| Cap-02 | backgroundColor ARGB | 部分 | **是** | ↑ |
| Cap-03 | 状态下的 textColor | 是 | 是 | — |
| Cap-04 | 多控件相同 textColor | 是 | 是 | — |
| Cap-05 | 颜色否定检查 | 是 | 是 | — |
| Cap-06 | visibility == VISIBLE | 是 | 是 | — |
| Cap-07 | 元素存在性 | 是 | 是 | — |
| Cap-08 | 可见但内容过期 | 是 | 是 | — |
| Cap-09 | 图标可见性 | 是 | 是 | — |
| Cap-10 | Margin/间距变化 | 是（layout_verify） | 是（layout_verify） | — |
| Cap-11 | minHeight 约束 | 是 | 是 | — |
| Cap-12 | Drawable 内部 viewport | 否 | **否** | — |
| Cap-13 | maxLines + ellipsize | 部分 | **是** | ↑ |
| Cap-14 | textColor 状态差异 | 是 | 是 | — |
| Cap-15 | backgroundColor 状态差异 | 是 | **是** | ↑ |
| Cap-16 | 自定义 View 属性 | 否 | **是** | ↑↑ |
| Cap-17 | tintColor / colorFilter | 否 | **是** | ↑↑ |
| Cap-18 | 多控件状态更新 | 是 | 是 | — |
| Cap-19 | 本地化文本 | 是 | 是 | — |
| Cap-20 | TabLayout 指示器颜色 | 部分 | **是**（子 View getter） | ↑ |
| Cap-21 | RTL 间距 | 部分（layout_verify） | 部分（layout_verify） | — |
| Cap-22 | cornerRadius | 否 | **是** | ↑↑ |
| Cap-23 | Toast 内部控件 | 部分 | 部分（同一 Window 限制） | — |
| Cap-24 | Toast padding | 部分 | 部分（同一 Window 限制） | — |
| Cap-25 | Dialog 错误 Context | 否 | 否 | — |

**结果：18 项完全支持 + 4 项部分支持 + 3 项不支持 → 覆盖率 ~80% 完全 + ~16% 部分 = ~96% 可达**

对比改造前：10 项完全支持 + 8 项部分支持 + 7 项不支持 → ~72% 可达

**净提升：+24% 覆盖率，仅需 ~400 行新代码。**
