---
title: 布局 dump 与 UI 证据
description: 解释 layout-dump 如何从 App 内即时视图快照生成双模式 JSON 证据，以及结构、属性、单位和快照时效的边界。
status: active
tags:
  - concept
  - ui
  - layout-dump
---

# 布局 dump 与 UI 证据

UI 判断需要同时说明预期是什么，以及设备当前实际呈现了什么。截图记录页面的像素外观，视图树则提供节点身份、层级、文本、bounds 和部分运行时属性；两类证据回答的问题不同，不能互相替代。

Jugg 的 `layout-dump` 从运行中 App 内采集当前视图层级，将 Android View 与 Compose 节点整理成稀疏 JSON artifact。这个产物代表一次请求发生时的页面快照，用于说明实际页面结构；它本身不提供设计预期，也不等于最终的通过或失败结论。

## 像素外观与视图结构回答不同问题

截图适合确认颜色、图像、阴影和整体视觉效果，但节点身份、资源 ID、精确层级和运行时 getter 值需要从 App 的视图结构中读取。反过来，视图树即使记录了 bounds 和文本，也不能完整表达抗锯齿、图片内容或最终像素合成效果。

因此，一次可复核的 UI 判断通常需要分开记录：

- 预期值来自设计稿、产品要求、代码公式或用户明确给出的标准。
- 实际结构来自当前页面的视图快照。
- 实际属性来自目标节点的运行时查询。
- 通过或失败由预期值与实际值的比较得出。

`layout-dump` 负责提供实际页面结构，不负责推断预期值或自动生成最终结论。

## 一次 layout-dump 如何形成页面快照

Jugg 不从系统级 uiautomator dump 读取页面，而是连接目标 App 内的 ViewHierarchy 服务。App 侧在收到请求时采集当前窗口中的 Android View 与 Compose 节点，Jugg 再统一整理节点字段和几何信息：

```text
运行中的目标 App
  -> 采集当前窗口的 Android View 与 Compose 节点
  -> 整理窗口、层级、文本、ID 和 bounds
  -> 按设备 density 将节点 bounds / padding 从 px 换算为 dp
  -> 按 interaction 裁剪摘要，或按 ui 保留已采集树
  -> 写入 JSON artifact
```

公开结果提供 JSON 文件路径、artifact 信息和 UTF-8 内容大小。文件携带 mode、units、窗口、设备密度、预算 truncated 和可选 warnings/rootLayout，可独立读取本次采集范围与诊断。摘要统计实际输出节点。

小结果同时随响应返回完整布局对象，省去额外读取文件的步骤。大结果保留在文件中，节点之间换行便于按需检索；节点属性和坐标数组保持紧凑。内联与文件表达同一份快照，数据范围由模式和采集参数决定。具体大小条件见 [MCP 工具参考](../reference/mcp-tools.md)。

## 交互摘要与 UI 层级采用不同节点范围

interaction（默认）用于浏览页面并发现交互候选。除下述窗口外节点保留规则外，它保留可见窗口根节点，以及具有真实 ID、文本、描述、点击行为或诊断的节点；其他节点被删除，孩子提升到保留的祖先。无语义的图片等叶节点也可能省略，所以摘要不能证明元素不存在或真实父子关系，也不保证输出节点都可点击。

真实节点另起一行，属性和坐标保持紧凑；`jugg_details` 伪节点不换行。children 至少包含一个真实节点时，开头 `[` 和末尾 `]}` 各另起一行。

普通窗口中，interaction 保留首个完全位于窗口范围外的节点及其 className、ID、bounds 和状态，将后代替换为带 `jugg_details` 的伪子节点：`View is off-screen; descendants omitted in interaction mode.`。伪节点说明后代在摘要中省略，不代表真实控件，也不计入节点或可点击数量。原本没有孩子的越界节点直接保留。

部分露出的节点、零面积节点，以及包含窗口内后代、未知几何后代或诊断后代的分支不会被折叠。窗口 bounds 无效或通过 `rootLayout` 请求子树时，继续执行原有语义裁剪，不按子树边界推断屏幕范围。需要展开后代时，使用 `jugg layout-dump --mode ui --root-layout <id>`。保留节点的顺序、bounds 和 className 可提示滑动探索方向；滑动后应重新采集确认内容变化。

ui 保留当前已采集树，用于几何、父子结构、装饰节点和隐藏状态验证。它提供辅助还原所需的结构与文案，但完整视觉或源码还原还需要图片、字体、绘制效果和源码等信息。两模式均受窗口、子树、GONE 筛选和采集预算限制；主动裁剪不设置 truncated。

两模式使用相同的字段含义，公开虚拟 ID 简写为 `_vid_<hash>`；旧 `_vir_id_` selector 仍可使用。interaction 先删除 invisible/gone 节点及整个子树（不会提升孩子，`--include-gone` 也不例外），省略 `padding`、`textColor`，并保留 className。ui 在请求的采集范围内保留隐藏节点和视觉字段；GONE 节点仍需 `--include-gone`。空值和默认状态省略，以控制信息量。

不同证据由不同方式取得：

| 要回答的问题 | 证据来源 |
|---|---|
| 当前有哪些窗口、节点和父子关系 | `layout-dump --mode ui` 的 JSON 快照 |
| 元素是否存在，以及 bounds、position 和 size | `view-locate` 的几何结果 |
| 文本颜色、可见性、enabled 等运行时属性 | `view-inspect` 的 getter 结果 |
| 交互后页面是否发生预期变化 | 交互前后的新快照或运行时证据 |

`view-locate` 返回适合计算间距和对齐的结构数据；`view-inspect` 返回 getter 原始值。二者用于实时定位和补充 dump 未覆盖的属性。已知 selector 时可以直接查询或元素点击，无需先读取整份 dump。

## 每个请求都有自己的时间点

`layout-dump`、`view-locate`、`view-inspect` 和元素模式触控共享同一个 App 内视图数据源，但每次调用都会重新采集页面。它们看到的是各自请求时刻的快照，不是跨工具共享的一份固定视图树。

页面动画、列表滚动、窗口切换或 Compose 重组都可能在两次请求之间改变节点、bounds 和虚拟 ID。页面发生交互后，之前的快照只能证明交互前的状态；判断交互结果时必须重新采集页面结构或运行时属性。

虚拟 ID 在窗口顺序和 UI 结构保持不变时可以重复出现，但页面重排、插入节点或重组后可能变化，不能把它当成长期稳定的业务标识。

## 几何数据和运行时属性采用不同单位契约

App 侧视图结构携带设备 density。Jugg 会递归转换普通 View 与 Compose 节点的 `bounds` 和 `padding`：

```text
dp = px / density
```

正常采集的 `layout-dump` 节点几何信息，以及 `view-locate` 返回的 bounds、position 和 size，使用 dp。dump 取整数 dp；缺少有效 density 时保留 px，并由文件 units 明示。Agent 应确认单位后计算元素间距、中心点和对齐关系。

`view-inspect` 返回 getter 的原始值，并同时返回 density。某个 getter 返回 px、sp、颜色整数还是业务对象，取决于该属性本身；使用前需要按对应 Android API 语义解释，不能因为布局 bounds 使用 dp，就把所有 getter 结果或触控坐标都视为 dp。

## 证据链的边界

- App 内 ViewHierarchy 服务不可用时，公开流程不会自动切换到 uiautomator；应先确认 App 是否在线、处于前台并已加载对应运行时。
- 视图快照有窗口、节点数量和层级深度限制。产物标记为截断时，未出现的节点不能直接判定为不存在。
- 隐藏或 GONE 节点仍可能保留可查询属性，但这些属性不能证明节点当前可见或可点击。
- Android View 与 Compose 节点使用统一结构输出，但 Compose 节点可查询的 getter 和可执行的交互语义取决于运行时实际暴露的信息。
- `layout-verify` 与 `figma-layout-verify` 当前不是公开 MCP 工具。公开 UI 验证应保留预期来源、实际证据和比较过程，而不是依赖未注册的批量断言入口。

## 相关页面

- [App 进程内 Jugg runtime](./jugg-runtime.md)
- [UI 检查指南](../guide/ui-inspection.md)
- [UI 自动化能力](../capabilities/tools/ui-automation.md)
- [UI 布局证据能力](../capabilities/tools/layout-verify.md)
- [MCP 工具参考](../reference/mcp-tools.md)
- [Agent 或 CLI 执行失败](../troubleshooting/agent-command-failed.md)
