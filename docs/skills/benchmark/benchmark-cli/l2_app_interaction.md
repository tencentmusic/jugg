# L2 应用控制与交互（~14 条）

> INTERACT-4~8 需先导航到 McpTestActivity。

**McpTestActivity 控件说明：**
- 唯一文本：`Unique MCP Target`
- 资源 ID：`btn_mcp_resource_target`（文本 `Resource Tap Target`）
- 重复文本：`Repeat Tap Target`（两个可见节点）
- 可见/隐藏同文案：`Visibility Tap Target`（一个可见、一个 invisible）
- swipe 区域：`sv_mcp_swipe_target`，标记 `Swipe Start Marker`/`Swipe End Marker`

---

### INTERACT-1: 重启应用 - 默认入口

执行 `restart`，验证 `status=OK`。

---

### INTERACT-2: 坐标点击

执行 `tap --x 540 --y 960`，验证 `status=OK`。

---

### INTERACT-3: 百分比点击

执行 `tap --xp 50 --yp 50`，验证 `status=OK`，输出含 `screenWidth/screenHeight`。

---

### INTERACT-4: swipe 坐标模式

先 `layout-dump` 确认 `Swipe Start Marker` 可见，执行 `tap --action swipe --xp 50 --yp 80 --end-xp 50 --end-yp 20`，再 `layout-dump` 验证 `Swipe End Marker` 可见。

---

### INTERACT-5: longPress 百分比

执行 `tap --action long-press --xp 50 --yp 50`，验证 `status=OK`。

---

### INTERACT-6: 元素模式 - text 精确匹配

先 `layout-dump` 找唯一 text 元素，执行 `tap --text "Unique MCP Target"`，验证 `status=OK`，输出含 `matchedElement` 信息。

---

### INTERACT-7: 元素模式 - resourceId

执行 `tap --id btn_mcp_resource_target`，验证 `status=OK`。

---

### INTERACT-8: 元素模式 - 无匹配返回候选

执行 `tap --text "NonExistentElementXYZ"`，验证 `status=ERROR`，输出含候选元素列表。

---

### INTERACT-9: 元素模式 - 多匹配返回候选

执行 `tap --text "Repeat Tap Target"`，验证 `status=ERROR`，匹配数 > 1，输出含多个匹配元素坐标。

---

### INTERACT-10: 隐藏节点不计入匹配

执行 `tap --text "Visibility Tap Target"`，验证不返回"多匹配"（仅匹配可见节点）。

---

### INTERACT-11: tap 缺少必填参数

执行 `tap`（不传任何选择器参数），验证 `status=ERROR`。
