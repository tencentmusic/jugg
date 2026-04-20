# L2 截图 / 录屏 / 布局导出 / 崩溃报告（~12 条）

---

### MEDIA-1: 截图

执行 `screenshot`，验证 `status=OK`，输出含截图文件路径。

---

### MEDIA-2: 开始录屏

执行 `record-start`，验证 `status=OK`，输出含 `sessionId`，快速返回。

---

### MEDIA-3: 停止录屏

先 `record-start` 获取 `sessionId`，等 2~3 秒，执行 `record-stop --session-id <sessionId>`，验证 `status=OK`，输出含 mp4 文件路径。

---

### MEDIA-4: record-stop - 缺少 session-id

执行 `record-stop`（不传 `--session-id`），验证 `status=ERROR`。

---

### MEDIA-5: 布局导出 - 默认

执行 `layout-dump`，验证 `status=OK`，输出含 HTML 文件路径。

---

### MEDIA-6: 布局导出 - 子树模式

先全量 `layout-dump` 找到有 id 的非根节点名称，再执行 `layout-dump --root <id>`，验证输出为局部子树（节点数少于全量）。

---

### MEDIA-7: 布局导出 - 包含 GONE 节点

执行 `layout-dump --include-gone`，验证输出包含 `visibility=gone` 的节点。

---

### MEDIA-8: 布局导出 - 全部窗口

执行 `layout-dump --all-windows`，验证输出包含多个 window 信息。

---

### MEDIA-9: Activity 栈查询

执行 `activity-stack`，验证 `status=OK`，输出含前台 Activity 名称。

---
