# layout_verify 实际开发工作流

> 本文档整理了在实际 Android 开发中，结合设计稿（Figma/截图）使用 `layout_verify` 进行 UI 验证的推荐流程和注意事项。
> 最后更新：2026-03-08

---

## 一、定位：这个工具解决什么问题

不要把 `layout_verify` 当单元测试框架用，它的正确定位是：

> **设计稿与实现的持续对齐工具**

- 开发过程中：随时验证某个模块是否符合设计稿
- 提测前：系统性跑一遍所有模块的断言
- UI 改版时：作为回归基线，防止非预期改动

---

## 二、推荐工作流

```
设计稿（Figma / 截图）
        ↓
[Step 1] agent 提取可测量属性 → 生成 spec.md（断言声明文件）
        ↓
[Step 2] 人工 review spec.md（重点看中/低置信条目）
        ↓
[Step 3] 开发实现
        ↓
[Step 4] agent 执行 spec.md 中的断言 → 输出 PASS/FAIL 报告
        ↓
[Step 5] 迭代修复
```

**关键原则**：spec.md 是中间产物，是设计稿的可执行描述，不是代码。人工 review 的是这份声明，而不是写断言本身。

---

## 三、spec.md 格式建议

每个模块按以下格式记录：

```markdown
## 登录按钮 (btn_login)

- [高置信] text eq "登录"
- [高置信] clickable eq true
- [高置信] visibility eq visible
- [中置信] textColor eq #FFFFFFFF        ← 截图颜色提取，需人工确认
- [中置信] bounds.height ≈ 48dp ±4dp    ← 截图 px 换算
- [低置信] textSizeSp eq 16             ← 截图无法量化，待设备实测确认
```

置信度标注规则：

| 置信度 | 来源 | 人工 review 要求 |
|--------|------|-----------------|
| 高 | 文本内容直读、逻辑推断（按钮必可点击） | 快速扫过即可 |
| 中 | 截图颜色估算、尺寸换算 | 需确认 |
| 低 | 截图无法量化的属性（字号等） | 建议改用设备实测值补全 |

---

## 四、Figma 标注混乱时的处理方式

Figma 图层名不可信时，以**文本内容**作为跨系统的映射锚点。

### Step 1：建立视觉对应关系

同时给 agent 三样东西：
1. `layout_dump` JSON（resourceId + bounds + text）
2. 设备截图（实现现状）
3. Figma 截图（设计意图）

让 agent 执行：

```
1. 从 layout_dump 找所有 text 不为空的节点
2. 在 Figma 截图中找视觉上相同文本的位置
3. 建立对应关系：Figma 区域 → resourceId
4. 无文本元素（图标、分割线）用"相对最近文本元素的位置"推断
```

### Step 2：分块处理，不要整图分析

按 layout_dump 顶层容器的 bounds 裁剪 Figma 截图，对每个小块单独提取属性。小块分析精度远高于全图。

### Step 3：颜色/尺寸用实测值校准

对截图无法精确提取的属性，先用 layout_verify 读取设备实测值，再与 Figma 视觉对比确认是否一致，而不是单向从截图推断期望值。

### 长期建议（可选）

在 Figma 中约定最小规范：可交互元素图层名加 `@resourceId` 前缀，如 `@btn_login`。存量设计稿无需修改，增量逐步规范。Agent 看到 `@` 前缀直接映射，否则走视觉匹配流程。

---

## 五、各类属性的验证策略

| 属性类型 | 推荐验证方式 | 注意事项 |
|---------|------------|---------|
| 文本内容 | layout_verify `text eq` | 高可靠，优先验证 |
| 元素存在/可见性 | layout_verify `exists` / `visibility eq` | 高可靠 |
| 可点击/启用状态 | layout_verify `clickable` / `enabled` | 高可靠 |
| 相对顺序 | layout_verify `relation.type=order` | 可靠 |
| 元素间距 | layout_verify `relation.type=spacing` | 注意：测量的是屏幕边界距离，**不等于布局 margin**；中间有其他元素时值会偏大 |
| 宽高尺寸 | layout_verify `bounds.width/height` | 注意单位换算；`expected` 字段有已知显示 bug，以 `actual` 字段为准 |
| 水平居中对齐 | layout_verify `relation.type=alignment, direction=vertical` | 注意：`direction=horizontal` 检查的是"是否同行"而非"x 轴中心对齐"，语义与直觉相反，建议用 `direction=vertical` 验证同列元素的中心对齐 |
| 文字颜色/背景色 | layout_verify `textColor` / `backgroundColor` | 截图来源误差较大，建议结合 Figma Dev Mode 导出精确值 |
| 字号 | layout_verify `textSizeSp`，必须用**实时模式**（不传 dumpFile） | dumpFile 不包含此属性 |
| 阴影/圆角/渐变 | 视觉快照工具（截图对比） | layout_verify 不支持，需另配工具 |
| 动画/过渡效果 | 录屏 + 人工确认 | 无法用断言覆盖 |

---

## 六、调用流程注意事项

### 1. dumpFile 生命周期

每次有用户交互（tap、swipe 等）后，必须重新调用 `layout_dump` 获取新路径，不能复用旧 dumpFile。使用旧文件工具不会报错，但数据是过期的，属于**静默错误**，是最危险的坑。

```
✅ 正确：tap → layout_dump（新路径）→ layout_verify（新路径）
❌ 错误：tap → layout_verify（旧路径）← 数据过期，结果不可信
```

### 2. 实时模式 vs dumpFile 模式

- 需要 `textSizeSp`、`maxLines`、`ellipsize` 等属性 → **必须用实时模式**（不传 dumpFile）
- 其他属性 → 优先用 dumpFile 模式（复用同一次 dump，减少设备通信）

### 3. 标准初始化流程

每次开始验证前：

```
restart_app → tap（导航到目标页面）→ layout_dump → 开始 verify
```

### 4. 串行调用

layout_verify 调用必须串行，不可并行。

---

## 七、与视觉快照工具的分工

| 工具 | 适合验证 | 不适合 |
|------|---------|--------|
| layout_verify | 结构正确性、文本内容、可交互状态、元素层级关系 | 阴影、圆角、渐变、动画 |
| 视觉快照工具（截图对比） | 像素级视觉回归、整体视觉风格 | 语义属性、交互状态逻辑 |

两类工具互补，不要强求 layout_verify 覆盖所有视觉属性。

---

## 八、已知工具 Bug 和规避方式

| Bug | 影响 | 临时规避 |
|-----|------|---------|
| 数值比较时 `data.expected` 显示为 0 | 无法从返回值确认期望值是否正确传入 | 以 `data.actual` 和 `data.result` 为准，忽略 `data.expected` 字段 |
| 正则 `matches` 可能返回错误结果 | agent 误判 FAIL | 遇到 matches FAIL 时，人工用 `data.actual` 手动确认是否真的不匹配 |
| `alignment(horizontal)` 语义与直觉相反 | 垂直排列的同宽元素误报 FAIL | 改用 `direction=vertical` 验证同列元素的水平中心对齐 |
| `className` 过滤精确匹配 | `"TextView"` 无法匹配 `"AppCompatTextView"` | 改用 `resourceId` 或 `text` 定位元素 |
