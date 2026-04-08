# L2 Unit: 截图 / 录屏 / 布局导出 / 崩溃报告

> 覆盖 `screenshot`、`start_record`、`stop_record`、`layout_dump`、`activity_stack`、`crash_report` 六个工具。
> 所有用例需要设备连接且 App 已部署。

---

## 四、截图 / 录屏 / 布局导出 / 崩溃报告

**MEDIA-1: 截图 - 有设备连接**
在有设备连接的情况下，调用 `screenshot`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`artifacts` 数组非空，包含一个类型为 `screenshot` 的产物，`path` 字段指向一个实际存在的图片文件。

**MEDIA-2: 开始录屏 - 立即返回 session**
在有设备连接的情况下，调用 `start_record`，仅传入 `projectDir`，验证返回 `status` 为 `OK`，`data` 中包含 `sessionId`，且调用应快速返回（不阻塞至录屏结束）。

**MEDIA-3: 停止录屏 - 拉取产物**
先调用 `start_record` 获取 `sessionId`，等待 2~3 秒后调用 `stop_record`（传入 `projectDir`、`sessionId`），验证返回 `status` 为 `OK`，`artifacts` 中包含可读取的 mp4 文件。

**MEDIA-4: start_record - 不接受旧参数**
调用 `start_record`，传入 `projectDir` 和旧参数 `durationSec=10`（或 `tapX`/`tapY`），验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_INVALID_PARAMS`。

**MEDIA-5: stop_record - 缺少 sessionId**
调用 `stop_record`，仅传入 `projectDir`，不传 `sessionId`，验证返回 `status` 为 `ERROR`，`errorCode` 为 `MCP_INVALID_PARAMS`。

**MEDIA-6: 布局导出 - 有设备**
在有设备连接的情况下，调用 `layout_dump`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`data.file` 指向一个 JSON 文件绝对路径，`artifacts` 数组中包含一个 `type=json` 的产物，且 `path` 与 `data.file` 一致。读取该文件，确认内容是有效的 ViewHierarchy JSON 树。

**MEDIA-7: 布局导出 - data.content 内联数据**
在有设备连接的情况下，调用 `layout_dump`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`data.content` 字段存在且为有效 JSON 对象（包含 `windows` 数组），内容与 `data.file` 指向的文件一致。Agent 可直接使用 `data.content` 而无需额外读取文件。

**MEDIA-8: 布局导出 - rootLayout 局部 dump**
在有设备连接的情况下，先调用 `layout_dump`（不传 `rootLayout`）获取完整层级，找到一个有 `id` 属性的非根节点。然后调用 `layout_dump`，传入 `projectDir` 和 `rootLayout=<该节点 id>`，验证返回 `status` 为 `OK`，`data.content` 中 `windows` 数组的 `windowType` 为 `"subtree"`，`rootLayout` 字段与传入值一致，且节点数明显少于全量 dump。若传入不存在的 `rootLayout`（如 `"non_existent_id_12345"`），验证返回全量 dump（fallback 行为）。

**MEDIA-9: Activity 栈查询**
在有设备连接的情况下，调用 `activity_stack`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`data` 或 `artifacts` 中包含当前 Activity 栈信息，能看到前台 Activity 名称。

**MEDIA-10: 崩溃报告 - 无崩溃**
在有设备连接且应用正常运行的情况下，调用 `crash_report`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`data` 中包含以下字段：
- `isProcessAlive` 为 `true`（应用进程存活）
- `hasCrash` 为 `false`（无崩溃信号）
- `crashLogs` 为空数组
- `allErrorLogPath` 指向一个实际存在的 `.log` 文件
- `packageName` 为当前项目的包名

`artifacts` 数组中包含一个 `type=log` 的产物，`path` 与 `data.allErrorLogPath` 一致。

**MEDIA-11: 崩溃报告 - 有崩溃**
先通过某种方式让应用产生崩溃（如在代码中故意引入一个运行时异常并 `compile_and_deploy`，或手动触发崩溃），然后调用 `crash_report`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`data` 中：
- `hasCrash` 为 `true`
- `crashLogs` 为非空数组，包含崩溃关键日志（如 `FATAL EXCEPTION`、堆栈信息等）
- `allErrorLogPath` 指向一个实际存在的 `.log` 文件，读取该文件内容应包含完整的错误日志
- `relatedActivity` 字段可能存在，包含崩溃时的前台 Activity 名称

`artifacts` 数组中包含一个 `type=log` 的产物。

**MEDIA-12: 崩溃报告 - 应用未启动**
在有设备连接但目标应用未启动（进程不存在）的情况下，调用 `crash_report`，传入有效 `projectDir`，验证返回 `status` 为 `OK`，`data` 中：
- `isProcessAlive` 为 `false`
- `allErrorLogPath` 指向一个实际存在的 `.log` 文件

`artifacts` 数组中仍包含一个 `type=log` 的产物（即使无崩溃，也会导出完整错误日志）。
