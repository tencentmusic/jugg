# UI Verify Selector 与源码位置增强方案

## 背景

Kuikly Inspector 的节点查找接口提供了组合 selector、可见性过滤和结果数量限制；节点检查结果还能携带源码位置。Jugg 当前的 `view-locate` 主要从完整层级 JSON 中本地筛选，多个 selector 使用 OR 语义，且不支持 className、可见性和结果预算。`view-inspect` 与 `view-locate` 也未公开 runtime 已能提供的 `sourceFile`、`lineNumber`。

本次只落地 selector 契约统一与源码位置输出，其余方向记录方案，不扩展实现范围。

## 本次范围

### 1. 统一 selector 契约

`view-locate` 支持以下 selector：

- `text`
- `resourceId`
- `contentDesc`
- `className`

契约如下：

- 至少提供一个 selector。
- 同时提供多个 selector 时使用 AND 语义。
- `text`、`contentDesc` 精确匹配。
- `resourceId` 同时支持完整资源名和短资源名的精确匹配。
- `className` 同时支持完整类名和 simple class name 的精确匹配，不支持子串匹配。
- `visibleOnly` 默认为 `true`；为 `false` 时允许返回不可见但可检查的节点。
- `maxResults` 默认为 `10`，范围为 `1..100`。

查找下沉到 app runtime 的 `find_elements`，避免 MCP 侧解析完整层级并重复实现 selector 语义。

结果返回：

- `matchCount`：符合条件的总数。
- `returnedCount`：本次实际返回数量。
- `truncated`：是否因 `maxResults` 截断。
- `matches`：最多 `maxResults` 个候选。
- 只有唯一匹配时，保留顶层 `bounds`、`position`、`size` 和源码位置，方便 Agent 直接消费。
- 多个匹配时不隐式选择第一个节点，不返回顶层节点坐标。

Kuikly 的参考点是组合 selector、`visibleOnly`、`maxResults` 和稳定的节点检查入口；Jugg 保留自身 resourceId 完整/短名兼容与 dp 坐标输出。

### 2. 源码位置纳入验证结果

runtime 对节点属性中的 `sourceFile` 和正数 `lineNumber` 做 best-effort 读取，并在以下位置公开：

- `view-locate.matches[].source`
- 唯一匹配时的 `view-locate.source`
- `view-inspect.data.source`

`source` 格式：

```json
{
  "file": "HomeScreen.kt",
  "line": 42
}
```

字段均为可选；辅助源码信息缺失或不合法时只省略对应字段，不影响节点查找和检查主结果。本次不尝试将文件名解析成本地 IDE 绝对路径，也不从 resourceId 推断布局文件。

## 本次不实现，但保留的后续方向

### 截图与节点关联

Kuikly Portal 支持在截图上点选并关联节点，但现有材料不足以证明截图与层级由 runtime 原子采集。Jugg 当前可先通过 ADB 获取全屏截图，再按节点 bounds 在宿主侧裁剪，无需 Android runtime 增加局部截图接口；实现本身较简单，但截图和层级可能来自不同帧。

局部截图通常能降低图像 token，尤其当目标只占全屏较小区域时，也能放大小文字和局部状态；代价是丢失页面上下文。较稳妥的后续接口应同时返回全局缩略图和带少量 padding 的节点裁剪，而不是只保留局部图。是否值得落地，应以真实 UI verify 样本比较 token、识别准确率和错位率后决定。

### virtual ID 与短生命周期 nodeRef

Jugg 当前 virtual ID 是层级快照里的节点标识，主要服务于树结构表达；其生成与有效期没有被明确约束为后续动作句柄。短生命周期 nodeRef 则是服务端持有的临时引用，明确绑定某次会话或快照，并在页面变化、超时或容量淘汰后失效，可用于后续 inspect/tap 避免重复 selector。

本次不引入 nodeRef。后续只有在 selector 重定位存在明显歧义或成本问题时再设计，并需明确失效错误和回退策略。

### 层级输出的渐进式披露

后续可将默认层级响应改成 inline 的超简树，再提供按节点或局部子树放大的接口。这样既减少首次调用，也避免把完整属性树塞入响应。完整树是否直接内联不应只按调用次数判断，还需衡量 token、截断和 Agent 实际读取行为。

本次不修改 `layout-dump`、HTML 表达或现有层级读取方式，仅记录方向。

## 验证策略

这些变更保护的是稳定的外部 MCP/CLI 契约，具备明确输入输出，适合自动化测试：

- runtime selector：验证多字段 AND、className 完整名/simple name 精确匹配、子串不匹配、可见性和截断统计。
- runtime/MCP 协议：验证 `find_elements` 请求与 source location 解析。
- MCP action：验证 schema、单/多匹配输出、dp 坐标换算和源码位置。
- CLI：验证新增参数映射与输出透传。
- 执行 Python 兼容性检查和 diff 格式检查。
