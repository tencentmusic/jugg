# MCP layout_verify / restart_app 改进提案

> 来源：layout_verify 盲测评估（60题）执行过程中收集的实际问题
> 日期：2026-03-08

---

## Proposal 1：修复 alpha 属性 gt/gte 操作符数值解析 bug

**优先级**：P0 Bug

**现象**

调用 `layout_verify`，传入：

```json
{
  "target": { "resourceId": "btn_mcp_unique_text" },
  "type": "property",
  "property": "alpha",
  "op": "gt",
  "value": "0.5"
}
```

返回：

```
FAIL: alpha = 1.0 (expected: gt 1.0)
```

`value` 传入的是 `0.5`，但 message 中显示 `gt 1.0`，且结论为 FAIL（实际 1.0 > 0.5 应为 PASS）。

**根因猜测**

数值解析时 `value` 字段可能被 `alpha` 当前值覆盖，或存在变量引用错误。

**期望行为**

```
PASS: alpha = 1.0 (expected: gt 0.5)
```

---

## Proposal 2：`type=spacing` 增加 op 支持

**优先级**：P1 功能缺失

**现象**

`type=spacing` 目前只接受 `expected + tolerance`，无法表达开区间比较（如"间距 > 0"、"间距 >= 8dp"）。

当前唯一的绕道方案是通过已知坐标手动推算，对 agent 不友好。

**期望设计**

在 `type=spacing` 中增加可选 `op` 字段：

```json
{
  "type": "spacing",
  "direction": "vertical",
  "target": { "resourceId": "btn_a" },
  "target2": { "resourceId": "btn_b" },
  "op": "gt",
  "expected": 0
}
```

`op` 与 `tolerance` 互斥：
- 传 `op` 时为开区间比较
- 传 `tolerance` 时为精确匹配（现有行为不变）

---

## Proposal 3：`bounds.width` / `bounds.height` 支持 `width` / `height` 别名，或改进报错信息

**优先级**：P2 易用性

**现象**

传入 `property: "width"` 返回：

```
ERROR: unsupported property in dumpFile mode: width
```

需要改写为 `bounds.width` 才能工作，但 agent 和开发者的第一直觉都是 `width`。

**期望改进（二选一）**

- 方案 A：支持 `width` / `height` 作为 `bounds.width` / `bounds.height` 的别名，直接透传
- 方案 B：保持现有限制，但将报错信息改为：

  ```
  ERROR: unsupported property "width". Did you mean "bounds.width"?
  ```

---

## Proposal 4：`type=alignment` direction 语义说明或命名优化

**优先级**：P2 易用性

**现象**

验证"两元素水平居中对齐"（X-center 相同）需要传 `direction: "vertical"`，与直觉相反（容易传成 `horizontal`）。

**根因**

当前语义是"沿 vertical 轴方向 → 检查 X-center"，但用户心智模型是"水平对齐 → horizontal"。

**期望改进（二选一）**

- 方案 A：在工具描述 / schema description 中补充说明：
  > `direction=vertical`：检查 X-center（两元素水平居中对齐）
  > `direction=horizontal`：检查 Y-center（两元素垂直居中对齐）

- 方案 B（breaking change，可选）：将 `direction` 字段改为 `axis`，值为 `x` / `y`，语义直接对应被检查的轴

---

## Proposal 5：`restart_app` 增加 `tap_actions` 参数

**优先级**：P2 易用性

**背景**

交互类测试任务通常需要在 `restart_app` 后立即执行一组固定的导航操作（如点击 "MCP Test Page" 进入子页面），目前每次都需要 agent 单独发起 tap 调用，增加了交互轮次。

**期望设计**

`restart_app` 增加可选 `tap_actions` 数组参数，App 启动完成后依次执行：

```json
{
  "projectDir": "...",
  "tap_actions": [
    { "text": "MCP Test Page" },
    { "resourceId": "btn_some_secondary_entry" }
  ]
}
```

**行为规范**

- 每步 tap 使用与 `tap` 工具相同的元素匹配逻辑（text / resourceId / contentDesc）
- 步骤串行执行，任意一步失败则整体返回 ERROR，并在 message 中指明第几步失败及原因
- App 启动后、执行第一步 tap 前，建议内置短暂等待或对"元素未找到"做 1~2 次重试，避免渲染未完成导致的误报

**收益**

将"重启 + 导航到目标页"压缩为单次 MCP 调用，减少 agent 交互轮次，降低 timing 问题风险。
