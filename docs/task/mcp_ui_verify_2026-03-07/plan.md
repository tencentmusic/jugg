# MCP UI 验收能力增强方案

> 日期：2026-03-07
> 背景：基于 Playwright MCP 调研结论，在 Jugg 现有 MCP 能力基础上增强 UI 验收链路。

---

## 一、背景与目标

### 核心结论（来自调研）

Jugg MCP 设计与 Playwright MCP 高度同构，无需搬运其设计模式。真正的提升空间在：

1. **减少截图依赖**：ViewNode 目前缺少 textColor 字段，颜色验收必须截图。
2. **消除手动计算错误**：agent 手算 dp↔px、bounds 差值易出错，需封装计算型 verify 工具。
3. **建立验收流程规范**：agent 缺乏分层验收协议，截图判断粗放。

### 目标

- P0：减少截图调用次数，提升结构化验收覆盖率
- P0.5：新增 `layout_verify` MCP 工具，消除 agent 手动布局计算
- P1：完善 Skill，规范 agent 的 UI 验收判断流程

---

## 二、改动范围总览

| 优先级 | 内容 | 改动位置 |
|--------|------|----------|
| P0 | ViewNode 新增 textColor 字段；layout_dump JSON 根节点加 deviceInfo | `jvmti_agent/.../ViewNode.java`、`ViewTreeDumper.java` |
| P0.5 | 新增 `layout_verify` MCP 工具（支持 dumpFile 缓存模式 + live query 模式） | `ViewHierarchyServer.java`（新增 action）、新建 `LayoutVerifier.java`、新建 `LayoutVerifyMcpToolAction.kt`、`McpToolActionRegistry.kt` |
| P1 | Skill 增加 UI 验收判定协议与字段速查 | `~/.claude/agents/jugg-android-dev-loop` 或对应 SKILL.md |
| P2 | layout_dump summary 增加 clickable 统计 | `LayoutDumpMcpToolAction.kt` |
| P2 | ViewNode 增加预计算 size/center（可选） | `ViewNode.java` |

---

## 三、P0：ViewNode 新增属性字段 + deviceInfo

### 3.1 设计原则：dump 职责边界

**dump 只扩展"仅通过 bounds + 结构无法推断，且高频出现在验收需求中"的字段。**

其他属性通过 `layout_verify` live query 按需从 View 对象获取，不污染 dump context。

基于此原则：
- `textColor`：颜色完全无法从结构推断，且"价格是红色"、"错误提示是红字"是最高频的截图替代场景 → **加入 dump**
- `textSizeSp`：字号验收频率较低，且 live query 可直接从 View 读取 → **不加入 dump，由 verify live query 覆盖**

### 3.2 ViewNode 新增字段

| 字段 | Java 类型 | 不输出条件 | 示例值 |
|------|-----------|-----------|--------|
| `textColor` | `int`（ARGB） | 黑色（`0xFF000000`）或非 TextView | `"#FFFF0000"` |

**`ViewNode.java`**

```java
// 新增字段（在 padding 之后）
public int textColor = 0;  // 0 = not applicable (non-TextView or default black)

// toJson() 中新增：
if (textColor != 0) {
    json.put("textColor", colorToHex(textColor));
}

// 工具方法：
static String colorToHex(int color) {
    return String.format("#%08X", color);
}
```

**`ViewTreeDumper.buildNode()`**

```java
// 在 buildNode() 末尾追加：
if (view instanceof TextView) {
    int color = ((TextView) view).getCurrentTextColor();
    // Only store non-black colors to save space (black is default)
    if (color != 0xFF000000) {
        node.textColor = color;
    }
}
```

### 3.3 dump JSON 根节点加 deviceInfo

**目标**：为 `layout_verify` dumpFile 模式提供 dp 换算所需的 density。

**`ViewTreeDumper.dumpWindowsJson()`** 在返回的根节点追加：

```java
DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
JSONObject deviceInfo = new JSONObject();
deviceInfo.put("density", dm.density);
deviceInfo.put("scaledDensity", dm.scaledDensity);
data.put("deviceInfo", deviceInfo);
```

输出示例（根节点追加，不进 windows/nodes）：
```json
{
  "windows": [...],
  "deviceInfo": { "density": 3.0, "scaledDensity": 3.0 },
  "truncated": false
}
```

### 3.4 压缩策略

- `textColor` 黑色或非 TextView 时不输出，对 dump 体积影响 < 5%
- `deviceInfo` 是根节点两个 float，可忽略不计

### 3.5 验收标准

- [ ] 非黑色 TextView 正确输出 textColor
- [ ] 黑色文字、非 TextView 不输出 textColor
- [ ] dump JSON 根节点包含 deviceInfo.density 和 deviceInfo.scaledDensity
- [ ] 单元测试：`ViewNodeTest` 增加 textColor 输出 case

---

## 四、P0.5：新增 `layout_verify` MCP 工具

### 4.1 设计原则

**双模式：dumpFile 缓存模式 + live query 模式**

| 模式 | 触发条件 | 属性来源 | App 通信 |
|------|---------|---------|---------|
| dumpFile 模式 | 传入 `dumpFile` 参数 | 解析 dump JSON | 无（复用已有 dump） |
| live query 模式 | 不传 `dumpFile` | 直接访问 View 对象 | 需要（实时 socket） |

**agent 推荐工作流（dumpFile 模式，0 额外 App 通信）**：

```
layout_dump  →  file=/tmp/.../layout_xxx.json

layout_verify(dumpFile=..., target={resourceId:"btn_login"}, assert={property:"bounds.width", op:"eq", value:328, unit:"dp"})
layout_verify(dumpFile=..., target={resourceId:"tv_price"}, assert={property:"textColor", op:"eq", value:"#FFFF0000"})
layout_verify(dumpFile=..., target={resourceId:"et_username"}, target2={resourceId:"et_password"}, relation={type:"spacing", direction:"vertical", expected:16, unit:"dp"})
```

**live query 模式**（属性不在 dump 字段中时，如 textSizeSp）：

```
layout_verify(target={resourceId:"tv_title"}, assert={property:"textSizeSp", op:"eq", value:18})
```

### 4.2 MCP 工具参数设计

```json
{
  "projectDir": "/path/to/project",

  "dumpFile": "/tmp/.../layout_xxx.json",

  "target": {
    "resourceId": "btn_login",
    "text": "",
    "contentDesc": "",
    "className": ""
  },

  "target2": {
    "resourceId": "btn_register"
  },

  "assert": {
    "property": "bounds.width",
    "op": "eq",
    "value": 328,
    "unit": "dp"
  },

  "relation": {
    "type": "spacing",
    "direction": "vertical",
    "expected": 16,
    "unit": "dp",
    "tolerance": 2
  }
}
```

**规则：**
- `dumpFile`：可选；传入时走 dumpFile 模式，不传时走 live query 模式
- `assert` 和 `relation` 互斥，至少提供一个
- `assert` 只需要 `target`；`relation` 需要 `target` 和 `target2`

### 4.3 支持的属性空间（assert.property）

| property | 说明 | dumpFile 模式 | live query 模式 |
|----------|------|:---:|:---:|
| `exists` | 元素是否存在 | ✓ | ✓ |
| `visibility` | visible / invisible / gone | ✓ | ✓ |
| `clickable` | 是否可点击 | ✓ | ✓ |
| `enabled` | 是否启用 | ✓ | ✓ |
| `text` | 文本内容（op: eq/contains/matches） | ✓ | ✓ |
| `bounds.width/height` | 尺寸（支持 dp/px） | ✓ | ✓ |
| `bounds.left/top/right/bottom` | 单边坐标 | ✓ | ✓ |
| `alpha` | 透明度 | ✓ | ✓ |
| `textColor` | 文本颜色（P0 完成后 dumpFile 可用） | ✓（需 P0） | ✓ |
| `textSizeSp` | 字号（不加入 dump，live query 覆盖） | ✗ | ✓ |
| `padding.left/top/right/bottom` | 内边距 | ✓ | ✓ |

> **扩展原则**：live query 模式可支持任意 View 属性，随需增加。dumpFile 模式仅能断言 dump JSON 中存在的字段，受 dump 字段集约束。

### 4.4 支持的关系类型（relation.type）

| type | 覆盖场景 | direction |
|------|---------|-----------|
| `spacing` | 两元素间距（最高频）| horizontal / vertical |
| `alignment` | 对齐检测（左/右/上/下/中心）| horizontal / vertical |
| `overlap` | 穿模检测（bounds 是否重叠）| - |
| `containment` | 包含检测（target 是否在 target2 内）| - |
| `order` | 排列顺序（A 在 B 的方向上）| horizontal / vertical |

### 4.5 返回格式

PASS：
```json
{
  "result": "PASS",
  "message": "bounds.width of #btn_login = 328dp (expected: eq 328dp) ✓",
  "actual": 328,
  "expected": 328,
  "unit": "dp"
}
```

FAIL：
```json
{
  "result": "FAIL",
  "message": "spacing between #et_username and #et_password = 12dp (expected: 16dp ±2dp)",
  "actual": 12,
  "expected": 16,
  "unit": "dp"
}
```

元素未找到：
```json
{
  "result": "ERROR",
  "message": "target not found: resourceId=btn_nonexist",
  "candidates": [...]
}
```

### 4.6 实现路径

#### Step 1：App 侧（`jvmti_agent`）

仅 live query 模式需要 App 侧改动（dumpFile 模式在 MCP 侧解析 JSON，无需 App 通信）。

**`ViewHierarchyServer.java`** 新增 `verify` action：

```java
case "verify":
    JSONObject finalParamsVerify = params;
    return runOnMainThread(() -> doVerify(finalParamsVerify));
```

新建 **`LayoutVerifier.java`**（与 `ElementFinder` 平级）：
- 复用 `ElementFinder.find()` 做元素选择
- 属性提取直接从 View 对象读取（不经 JSON，可查任意属性）
- dp 换算：`displayMetrics.density`（px → dp = px / density）
- sp 换算：`displayMetrics.scaledDensity`（px → sp = px / scaledDensity）

#### Step 2：MCP 侧（`main` 模块）

新建 **`LayoutVerifyMcpToolAction.kt`**（与 `LayoutDumpMcpToolAction.kt` 平级）：
- 参数 schema 定义（dumpFile/target/target2/assert/relation）
- 路由判断：有 `dumpFile` 走本地 JSON 解析；无 `dumpFile` 走 `ViewHierarchyClient.verify()`
- dumpFile 模式：从 JSON 的 `deviceInfo.density` 读 density，做 dp/px 换算
- 返回 PASS/FAIL/ERROR 结构

**`ViewHierarchyClient.kt`** 新增 `verify()` 方法（复用现有 socket 通道）

**`McpToolActionRegistry.kt`** 注册 `LayoutVerifyMcpToolAction`

#### Step 3：协议版本

`ViewHierarchyServer.PROTOCOL_VERSION` 递增（参考 `08_mcp_design.md §6` Versioning rule）

### 4.7 验收标准

- [ ] dumpFile 模式：属性断言 bounds.width/height/left/top/right/bottom（dp/px 两种单位）
- [ ] dumpFile 模式：属性断言 exists/visibility/clickable/enabled/text/textColor
- [ ] dumpFile 模式：density 从 deviceInfo 正确读取做 dp 换算
- [ ] dumpFile 模式：5 种关系断言（spacing/alignment/overlap/containment/order）
- [ ] live query 模式：textSizeSp 断言正确
- [ ] 元素未找到时返回 candidates
- [ ] 单元测试：`LayoutVerifierTest`（live）、`LayoutVerifyDumpParserTest`（dumpFile）

---

## 五、P1：Skill 增加 UI 验收协议

### 5.1 UI 验收三步法

在 `jugg-android-dev-loop` Skill 中增加：

```markdown
## UI 验收三步法

验收时按以下顺序执行，满足当前层级即停：

### 1. 结构验证（必做）
使用 `layout_dump` 检查：
- 目标元素是否存在
- 父子关系是否正确
- visibility 是否符合预期

### 2. 布局验证（按需）
先调用一次 `layout_dump` 获取 dumpFile 路径，后续所有 verify 复用该文件（无额外 App 通信）：

- 尺寸：assert.property=bounds.width/height，unit=dp
- 间距：relation.type=spacing，需 target + target2
- 对齐：relation.type=alignment
- 颜色：assert.property=textColor（仅非黑色文字有效）
- 字号（需 live query，不传 dumpFile）：assert.property=textSizeSp

bounds 格式速查：
- `bounds: [left, top, right, bottom]`（px，屏幕绝对坐标）
- dp 换算：dp = px / deviceInfo.density（见 dump JSON 根节点）

### 3. 视觉验证（仅当结构化数据无法覆盖时）
使用 `screenshot`，需明确说明截图验证的具体目标，不做泛泛判断。
```

### 5.2 layout_dump 字段速查

```markdown
## layout_dump 字段速查

| 字段 | 含义 | 示例 |
|------|------|------|
| `id` | 短 resourceId（去除包名前缀）| `btn_login` |
| `text` | TextView 显示文本 | `"登录"` |
| `contentDesc` | 无障碍描述 | `"关闭按钮"` |
| `bounds` | [left,top,right,bottom] px 屏幕坐标 | `[0,100,1080,244]` |
| `visibility` | 仅非 visible 时存在 | `"gone"` |
| `clickable` | 仅 true 时存在 | `true` |
| `enabled` | 仅 false 时存在 | `false` |
| `alpha` | 仅非 1.0 时存在 | `0.5` |
| `padding` | [l,t,r,b] px | `[24,0,24,0]` |
| `textColor` | 仅非黑色 TextView 存在 | `"#FFFF0000"` |
| `children` | 子节点列表 | `[...]` |
| `composeNodes` | Compose 节点（如有）| `[...]` |

根节点字段（不在 windows 内）：
| `deviceInfo.density` | 屏幕密度，用于 px→dp 换算 | `3.0` |
| `deviceInfo.scaledDensity` | 字体密度，用于 px→sp 换算 | `3.0` |
| `truncated` | 是否因节点数超限被截断 | `false` |
```

---

## 六、P2：layout_dump summary 增强

### 6.1 增加 clickable 统计

当前 `buildSummaryMessage` 返回：
```
2 windows (top: MainActivity), 127 nodes, not truncated
```

目标：
```
2 windows (top: MainActivity), 127 nodes, 45 clickable, not truncated
```

**改动位置**：`LayoutDumpMcpToolAction.kt#buildSummaryMessage()`，新增 `countClickable()` 方法统计 `clickable=true` 的节点数。

### 6.2 ViewNode 增加预计算 size/center（可选）

```json
"size": [360, 48],
"center": [540, 524]
```

> 权衡：增加所有节点的 2 个数组字段，dump 体积约增加 10-15%。agent 通过 dumpFile 模式 verify 时几乎不再需要手算，收益有限。暂缓，视实际反馈决定。

---

## 七、实施顺序与依赖关系

```
P0（ViewNode textColor + deviceInfo）
    ↓ 独立，可并行
P0.5（layout_verify 工具）
    ├─ dumpFile 模式依赖 P0 的 deviceInfo（dp 换算）
    └─ textColor 断言在 dumpFile 模式依赖 P0（live 模式不依赖）

P1（Skill）
    依赖：P0、P0.5 完成后内容最完整

P2（summary 增强）
    完全独立，随时可做
```

### 推荐实施顺序

1. **Phase 1**：P0（ViewNode textColor + deviceInfo）+ P2 summary 增强（风险低，改动集中）
2. **Phase 2**：P0.5 `layout_verify`（App 侧 LayoutVerifier + MCP 侧 LayoutVerifyMcpToolAction，含双模式）
3. **Phase 3**：P1 Skill 更新

---

## 八、测试策略

| 模块 | 测试类 | 覆盖点 |
|------|--------|--------|
| jvmti_agent | `ViewNodeTest` | textColor 输出与压缩策略 |
| jvmti_agent | `ViewTreeDumperTest` | deviceInfo 字段存在且值正确 |
| jvmti_agent | `LayoutVerifierTest` | live query：各 property、5 种 relation、dp/px/sp 换算、未找到元素 |
| main | `LayoutVerifyDumpParserTest` | dumpFile：JSON 解析、density 读取、各断言类型 |
| main | `LayoutVerifyMcpToolActionTest` | 参数校验、dumpFile/live 路由、PASS/FAIL/ERROR 路径 |

遵循现有 TDD 原则：测试先行，使用 Mockito 而非侵入式测试。

---

## 九、文档更新（完成后）

- [ ] `08_mcp_usage.md`：新增 `layout_verify` 工具说明与参数表（含双模式说明）
- [ ] `08_mcp_design.md §6`：更新 ViewHierarchyServer 版本号记录
- [ ] `98_code_map.md`：新增 `LayoutVerifier`、`LayoutVerifyMcpToolAction` 条目

---

## 十、已否决方向（不做）

| 方向 | 否决原因 |
|------|---------|
| ViewNode 增加 margin 字段 | Android 无直接 API，LayoutParams 类型不确定，实现成本高；间距验收用 layout_verify spacing 覆盖 |
| ViewNode 增加 textSizeSp 字段 | 字号验收频率低，live query 模式可直接从 View 读取，无需污染 dump |
| App 侧缓存 View 引用 | WeakReference 随时失效，可靠性差；dumpFile 模式已解决复用问题 |
| App 侧内存 ViewSnapshot | 成本与 dumpFile 方案不对等 |
| JSON → 文本树格式 | LLM 对 JSON 更熟悉，token 差距微不足道 |
| find_element 单独工具 | tap element mode 失败返回 candidates 已覆盖 |
| Playwright ref ID 系统 | resourceId 更天然 |
| 增量 snapshot/diff | 验收场景以单次 dump 为主 |
