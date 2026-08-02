# Jugg Control Panel 布局规格提取 Review

> Review 状态：已按建议通过。

## 1. 提取结果

机器可读规格：[`jugg_control_panel_layout_spec.json`](jugg_control_panel_layout_spec.json)。

本次通过 `chrome-devtools` 读取浏览器 computed layout，测量环境如下：

| 项目 | 值 |
|---|---:|
| Chrome viewport | 1835 × 873 |
| Device pixel ratio | 2 |
| Jugg panel | 420 × 711 |
| 一级 tabs | 419 × 35 |
| 页面可视区域 | 419 × 640 |
| Overview / Settings 滚动 viewport | 404 × 640 |

坐标原点为 `.jugg-panel` 左上角。实现验收只包含一级 tabs 和页面内容，不包含 HTML 模拟的 `.jugg-titlebar`。

## 2. 建议直接锁定的硬规格

### 2.1 全局

- 面板基准宽度为 420 logical px。
- tabs 顺序固定为 Overview、Logs、Settings。
- 页面填满 tabs 下方剩余区域。
- Overview 和 Settings 禁止横向滚动。
- 页面内部宽度跟随原生 scroll viewport，不固定复制浏览器扣除滚动条后的 404px。

### 2.2 Overview

| 关系 | 规格 |
|---|---|
| Section 顺序 | Context → Current Task → Quick Actions → Last Deploy → Project Health → Recent Activity |
| Section padding | 12 |
| Section divider | 1 |
| Eyebrow bottom gap | 8 |
| Context meta | wrap；row gap 5；column gap 12；top gap 7 |
| Context 420px 换行 | 第一行 3 项，第二行 1 项 |
| Quick Actions | 2×2；row/column gap 8；等宽等高 |
| Timeline | `18px / 1fr / auto`；gap 6；row height 31 |
| Project Health | `16px / 1fr / auto`；gap 7；row gap 9 |
| Recent Activity | `48px / 1fr / auto`；gap 8；vertical padding 6 |

### 2.3 Logs

| 关系 | 规格 |
|---|---|
| Toolbar | 两行；水平 padding 9；垂直 padding 7；gap 7 |
| Source selector | 三列等宽；inner padding 2；item height 25 |
| 第二行 | Search → Level → Task → Follow → Overflow |
| Search | 占用原生控件 preferred width 之外的剩余空间 |
| Console padding | top 9、right 10、bottom 20、left 10 |
| Log columns | `76px / 62px / 118px / 1fr`；gap 8 |

### 2.4 Settings

| 关系 | 规格 |
|---|---|
| Page padding | 10 |
| Search bottom gap | 10 |
| Group bottom gap | 10 |
| Group border | 1 |
| Group title padding | vertical 8、horizontal 10 |
| Row padding | vertical 9、horizontal 10 |
| Text/control gap | 12 |
| Label/help gap | 2 |
| Row layout | text 占剩余宽度；control 右侧垂直居中 |
| Action minimum width | 92 |

## 3. 不建议锁定的软规格

以下测量值受浏览器字体或原生 IntelliJ 控件影响，不应直接写成 Swing 固定尺寸：

- Tab item 的具体宽度和高度。
- Section、Settings group 和 Settings row 的最终高度。
- `OnOffButton` 的宽高和内部皮肤。
- 原生 action button 的高度。
- 文本行高、baseline 和抗锯齿结果。
- Browser scrollbar 导致的 404px 内容宽度。

实现测试应断言包含、对齐、等宽和 viewport 关系，截图验收再检查视觉节奏。

## 4. 提取中发现的原型问题

### 4.1 Quick Action 高度与当前方案不一致

HTML computed height 为 `51.34px`，当前实施方案中的 `ACTION_HEIGHT` 是 `48px`。

建议：Swing 不固定复制 `51.34px`。四个按钮使用相同 native preferred height，并设置 `48px` minimum height。这样保留原生字体度量，也不会让按钮低于设计密度下限。

### 4.2 Logs 存在 4px 纵向溢出

实测：

- Logs 页面 client height：640px。
- Toolbar 实际高度：80px。
- `.log-layout` 按 `calc(100% - 76px)` 得到 564px。
- 两者合计 644px，比页面高 4px。

建议：这是 HTML 原型误差，不作为 Swing 规格。Swing 使用 `BorderLayout`，toolbar 放 NORTH，console 放 CENTER，严格填满剩余高度。

### 4.3 Overview 的 404px 不是设计常量

420px panel 扣除左边框和浏览器垂直滚动条后，Overview viewport 实测为 404px，Section 内容宽度为 380px。

建议：测试关系应为：

```text
section width = native scroll viewport width
section content width = section width - 24
```

不要在 Swing 中硬编码 404 或 380。

### 4.4 Settings 控件尺寸需要原生化

HTML toggle 为 30×16，value button 为 92×27。`OnOffButton` 和原生 `JButton` 的高度会随 LAF 改变。

建议：

- toggle 只锁定右对齐和垂直居中。
- action button 锁定 minimum width 92，不锁定高度。
- Settings row 高度由 text block 与原生 control preferred height 的较大值决定。

## 5. 已确认决策

1. Quick Actions 采用 native preferred height，minimum 48px，不固定 52px。
2. Logs 的 4px 原型溢出按建议修正，不复制。
3. Scroll viewport 只锁定宽度关系，不锁定 404px。
4. 原生 toggle/button 只锁定对齐和 minimum width，不锁定 HTML 高度。
