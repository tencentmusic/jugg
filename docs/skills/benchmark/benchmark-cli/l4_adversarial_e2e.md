# L4 对抗 + 端到端组合（~10 条）

---

## 错误处理

### ERR-1: 在非项目目录执行

在非项目目录执行 `screenshot`，验证 `status=ERROR`。

---

### ERR-2: 不存在的子命令

执行 `nonexistent_cmd`，验证返回错误（exit code 非 0）。

---

### ERR-3: 返回结构一致性

对所有 16 个子命令各执行一次，验证输出 JSON 均含 `status`/`message` 字段。

---

## 端到端组合

### E2E-1: 完整开发迭代

`devices` → `restart` → `screenshot`（前）→ 修改源码 → `deploy`（等完成）→ `screenshot`（后）→ 对比前后截图。

---

### E2E-2: 编译失败后 Gradle 回退

引入错误 → `deploy` 失败 → 修复 → `gradle-build`（等完成）→ `deploy` 成功。

---

### E2E-3: UI 自动化操作

`restart` → `screenshot` → `layout-dump` → 从输出找坐标 → `tap --x <x> --y <y>` → `screenshot` → `activity-stack`。

---

### E2E-4: 两段式录屏

`record-start`（获取 sessionId）→ `restart` → `tap --text "MCP Test Page"` → 等 2~3s → `record-stop --session-id <sessionId>` → 验证 mp4 文件存在。

---

### E2E-5: UI 检查流程

`restart` → `tap --text "MCP Test Page"` → `view-locate --text "Unique MCP Target"`（获取 bounds）→ `view-inspect --id btn_mcp_resource_target text visibility`（获取属性）→ 验证返回值正确。

---

### E2E-6: 构建回退链完整演练

`deploy` 失败（引入错误）→ 修复错误 → `deploy` 再次失败 → `gradle-build`（等完成）→ `deploy` 成功。
