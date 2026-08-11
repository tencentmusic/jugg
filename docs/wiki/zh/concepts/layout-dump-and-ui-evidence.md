---
title: 布局 dump 与 UI 证据
description: 解释 layout-dump 如何从 App 内即时视图快照生成 HTML 证据，以及结构、属性、单位和快照时效的边界。
status: active
tags:
  - concept
  - ui
  - layout-dump
---

# 布局 dump 与 UI 证据

UI 判断需要同时说明预期是什么，以及设备当前实际呈现了什么。截图记录页面的像素外观，视图树则提供节点身份、层级、文本、bounds 和部分运行时属性；两类证据回答的问题不同，不能互相替代。

Jugg 的 `layout-dump` 从运行中 App 内采集当前视图层级，将 Android View 与 Compose 节点整理成可阅读的 HTML artifact。这个产物代表一次请求发生时的页面快照，用于说明实际页面结构；它本身不提供设计预期，也不等于最终的通过或失败结论。

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
  -> 裁剪缺少语义内容的结构节点
  -> 写入 HTML artifact
```

公开结果提供 HTML 文件路径、artifact 信息和内容大小。用于生成 HTML 的结构化数据只供 Jugg 内部工具消费，不作为稳定的公开接口。

## HTML 是阅读产物，不是完整数据接口

HTML 的目标是让 Agent 和开发者快速阅读页面结构、查找候选节点并引用本轮证据。为了控制信息量，它会省略没有文本、ID、描述或其他有效语义的中间结构节点，因此 HTML 不是 App 原始视图对象的逐字段镜像。

不同证据由不同方式取得：

| 要回答的问题 | 证据来源 |
|---|---|
| 当前有哪些窗口、节点和父子关系 | `layout-dump` 的 HTML 快照 |
| 元素是否存在，以及 bounds、position 和 size | `view-locate` 的几何结果 |
| 文本颜色、可见性、enabled 等运行时属性 | `view-inspect` 的 getter 结果 |
| 交互后页面是否发生预期变化 | 交互前后的新快照或运行时证据 |

`view-locate` 返回适合计算间距和对齐的结构数据；`view-inspect` 返回 getter 原始值。二者用于补充 HTML 中没有直接表达的精确信息，而不是把内部布局 JSON 暴露为公共协议。

## 每个请求都有自己的时间点

`layout-dump`、`view-locate`、`view-inspect` 和元素模式触控共享同一个 App 内视图数据源，但每次调用都会重新采集页面。它们看到的是各自请求时刻的快照，不是跨工具共享的一份固定视图树。

页面动画、列表滚动、窗口切换或 Compose 重组都可能在两次请求之间改变节点、bounds 和虚拟 ID。页面发生交互后，之前的快照只能证明交互前的状态；判断交互结果时必须重新采集页面结构或运行时属性。

虚拟 ID 在窗口顺序和 UI 结构保持不变时可以重复出现，但页面重排、插入节点或重组后可能变化，不能把它当成长期稳定的业务标识。

## 几何数据和运行时属性采用不同单位契约

App 侧视图结构携带设备 density。Jugg 会递归转换普通 View 与 Compose 节点的 `bounds` 和 `padding`：

```text
dp = px / density
```

`layout-dump` 中的节点几何信息，以及 `view-locate` 返回的 bounds、position 和 size，都使用 dp。Agent 可以直接用这些值计算元素间距、中心点和对齐关系。

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
- [Agent 或命令执行失败](../troubleshooting/agent-command-failed.md)
