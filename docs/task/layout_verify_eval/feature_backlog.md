# layout_verify 功能待办

> 来源：`suggestion_by_test_agent.md`，经评审认为价值明确但超出 bug 修复范围，单独立项讨论。

---

## F-01：批量断言（asserts 数组）

**背景**：多属性验证（如 C-12、D-09、E-07）需要对同一元素发起 3-5 次独立调用，消耗大量 token 且中间状态变化风险高。

**建议方案**：
```json
{
  "target": {"resourceId": "btn_mcp_unique_text"},
  "asserts": [
    {"property": "clickable", "value": true},
    {"property": "text", "value": "Unique MCP Target"},
    {"property": "visibility", "value": "visible"}
  ]
}
```
返回每项独立结果，整体 result 取最差值（任一 FAIL 则整体 FAIL）。

**价值**：1 次调用替代 5 次，对 agent token 消耗友好。

---

## F-02：纯读取模式（只返回属性当前值，不做断言）

**背景**：调试和探索性验证时，agent 想知道某属性当前值，但工具强制要求传 assert/relation。目前只能传 dummy 断言（op=eq, value=0）然后从 FAIL 的 data.actual 读出真实值，体验差。

**建议方案**：`assert` 中只传 `property`，不传 `op`/`value` 时，工具返回当前值，`result` 设为 `"INFO"` 或类似标识。

---

## F-03：dumpFile 过期感知

**背景**：交互（tap）之后 dumpFile 数据就过期，但工具不感知，复用旧路径会正常返回但数据是错的——最危险的静默错误。

**候选方案**：
- **方案 A**：dumpFile 接受 `"latest"` 关键字，工具自动取最近一次 dump（agent 无需管路径）。
- **方案 B**：dumpFile 中嵌入时间戳，工具在文件超过 N 秒时在 message 输出 warning。

---

## F-04：元素未找到时 candidates 按相似度排序

**背景**：当前 candidates 返回 5 个可点击元素，但选取逻辑不透明，对拼写错误类的调试帮助有限。

**建议**：按 resourceId 编辑距离或前缀匹配排序，并在 message 说明推荐逻辑，帮助 agent 快速区分"拼写错误"和"控件真的不存在"。

---

## F-05：并发调用行为明确化

**背景**：文档写了"MCP 同时只支持一个客户端调用"，但并发调用时会排队、报错还是返回脏数据，对 agent 是黑盒。

**建议**：在工具层面给出明确的并发边界行为描述，或加并发保护并返回专用错误。
