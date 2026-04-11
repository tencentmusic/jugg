# figma-layout-verify 内部算法文档

> 最后更新：2026-04-07  
> 口径：以代码为准（`FigmaLayoutVerifier.kt`、`RelationExtractor.kt`、`ElementMatcher.kt`、`RelationVerifier.kt`、`FigmaJsonParser.kt`）

---

## 1. 总体流程

```
get_design_context (Figma API)
        │ figmaJsonPath
        ▼
FigmaLayoutVerifyMcpToolAction.execute()
        │
        ├─① LayoutDumpHelper.dump()  → 内部调用 layout_dump，获取 androidJsonPath
        │
        ├─② FigmaJsonParser.parse()  → FigmaNode 树
        │
        ├─③ RelationExtractor.extractRelations()  → List<Relation>（间距 + 对齐）
        │
        ├─④ ElementMatcher.match()（每个 Relation 的两端节点）  → AndroidNode
        │
        └─⑤ RelationVerifier.verify*()  → VerifyResult（PASS / FAIL）
```

整个算法仅在 IDE 侧（Kotlin）运行。App 侧 `LayoutVerifier.java` 是 `layout_verify` 工具专用，`figma-layout-verify` 不调用它。

---

## 2. 阶段一：Figma JSON 解析（FigmaJsonParser）

### 2.1 格式自动检测

支持三种格式，按顺序匹配：

| 格式 | 判断条件 | 取根节点方式 |
|------|----------|-------------|
| Direct node | `json.has("id") && (json.has("layout") || json.has("bounds"))` | `json` 本身即根节点 |
| Nodes wrapper | `json.has("nodes")` | `json["nodes"].entrySet().first().value` |
| Document wrapper | `json.has("document")` | `json["document"]["children"][0]` |

### 2.2 bounds 解析规则

`get_design_context` 产出的 Figma JSON 使用 `layout` 字段，格式为 `[x, y, width, height]`：

```
bounds[left]  = layout[0]         (x)
bounds[top]   = layout[1]         (y)
bounds[right] = layout[0] + layout[2]  (x + width)
bounds[bottom]= layout[1] + layout[3]  (y + height)
```

也兼容 `bounds` 字段（原始 Figma API 格式），格式为 `[left, top, right, bottom]`，直接使用。

> ⚠️ 此处 bounds 单位为 Figma 设计像素（未经 dpr 缩放）。dpr 在后续阶段使用。

### 2.3 节点树展平

`flattenNodes()` 做先序深度遍历（DFS），将所有节点（包括叶子和中间容器）收集为平铺列表，顺序与 Figma 层级中从上到下的层叠顺序一致。

---

## 3. 阶段二：关系提取（RelationExtractor）

`RelationExtractor` 构造时接收 `dpr` 参数，所有公差均乘以 `dpr` 以在 Figma 像素空间中计算，最终输出换算为 dp。

### 3.1 间距关系（SpacingRelation）

对展平后的节点列表，逐对扫描相邻节点对 `(nodes[i], nodes[i+1])`：

**水平相邻判断** (`isHorizontallyAdjacent`):
```
tolerance = 20 * dpr
abs(node1.bounds[1] - node2.bounds[1]) < tolerance   // top 差距足够小
AND node2.bounds[0] >= node1.bounds[2]               // node2 在 node1 右侧
```

**垂直相邻判断** (`isVerticallyAdjacent`):
```
tolerance = 20 * dpr
abs(node1.bounds[0] - node2.bounds[0]) < tolerance   // left 差距足够小
AND node2.bounds[1] >= node1.bounds[3]               // node2 在 node1 下方
```

满足条件时，计算期望间距（单位：dp）：
```
水平间距 expected_x = (node2.bounds[0] - node1.bounds[2]) / dpr   // node2.left - node1.right
垂直间距 expected_y = (node2.bounds[1] - node1.bounds[3]) / dpr   // node2.top  - node1.bottom
```

> 注：间距可为负值（重叠情形），但通常设计稿中为正值。

### 3.2 对齐关系（AlignmentRelation）

对展平后的所有节点，按坐标分桶聚类：

```
tolerance = 5 * dpr
bucket_y = (bounds[1] / tolerance) * tolerance   // top 分桶 → Y 轴对齐（水平方向）
bucket_x = (bounds[0] / tolerance) * tolerance   // left 分桶 → X 轴对齐（垂直方向）
```

同一 bucket 内节点数 ≥ 2，则产生一条对齐关系。

| 分桶依据 | `axis` | 含义 |
|----------|--------|------|
| `bounds[1]`（top） | `"y"` | 一行内元素水平对齐（共享相同 top） |
| `bounds[0]`（left） | `"x"` | 一列内元素垂直对齐（共享相同 left） |

---

## 4. 阶段三：元素匹配（ElementMatcher，IoU）

### 4.1 归一化

将 Figma 节点 bounds 和 Android 节点 bounds 分别映射到 1000×1000 虚拟空间：

```
normalized[0] = bounds[0] / screenWidth  * 1000    // left
normalized[1] = bounds[1] / screenHeight * 1000    // top
normalized[2] = bounds[2] / screenWidth  * 1000    // right
normalized[3] = bounds[3] / screenHeight * 1000    // bottom
```

- Figma 侧 screenSize：从 Figma JSON 的 `layout[2], layout[3]`（或 `bounds[2], bounds[3]`）读取，即设计稿整体画布尺寸（Figma 像素）
- Android 侧 screenSize：从 `layout-dump` 产出的 `androidJson.deviceInfo.screenWidth/screenHeight` 读取（dp）

**归一化消除了分辨率和 dpr 差异**，使 Figma 设计稿坐标与实际设备 dp 坐标可直接比较。这也是状态栏/导航栏高度不一致、屏幕尺寸不同时仍能正确匹配元素的原因——元素的**相对位置**在归一化后一致。

### 4.2 IoU 计算

```
intersect_area = max(0, min(r1,r2) - max(l1,l2)) * max(0, min(b1,b2) - max(t1,t2))
area1 = (r1 - l1) * (b1 - t1)
area2 = (r2 - l2) * (b2 - t2)
iou = intersect_area / (area1 + area2 - intersect_area)
```

**匹配条件**：IoU > 0.7（硬阈值）。取 IoU 最高的节点作为最终匹配，最多保留 3 个备选。

---

## 5. 阶段四：关系验证（RelationVerifier）

`AndroidNode.bounds` 单位为 dp（由 `layout-dump` 在 IDE 侧按 `dp = round(px / density)` 转换）。

### 5.1 间距验证

```
实际间距（dp）:
  axis=x: element2.bounds[0] - element1.bounds[2]   // right-to-left gap
  axis=y: element2.bounds[1] - element1.bounds[3]   // bottom-to-top gap

diff = actual - expected

通过条件（满足其一）:
  abs(diff) <= 2dp    (TOLERANCE_DP = 2)
  abs(diff) / expected <= 5%   (TOLERANCE_PERCENT = 0.05)
```

> 若 expected=0，百分比容差退化为 0，仅靠绝对值容差 ±2dp 判定。

### 5.2 对齐验证

```
center_x(node) = (bounds[0] + bounds[2]) / 2
center_y(node) = (bounds[1] + bounds[3]) / 2

maxDiff = max(centers) - min(centers)

通过条件: maxDiff <= 2dp   (TOLERANCE_DP = 2)
```

---

## 6. 坐标单位流转总结

| 阶段 | Figma 侧单位 | Android 侧单位 |
|------|-------------|---------------|
| JSON 解析后 | Figma px（未缩放） | dp（layout_dump 已转换） |
| 间距计算（RelationExtractor） | Figma px → `/ dpr` → dp | - |
| 元素匹配归一化（ElementMatcher） | Figma px / 画布尺寸 × 1000 | dp / 屏幕 dp 尺寸 × 1000 |
| 验证（RelationVerifier） | expected: dp | actual: dp（直接用 AndroidNode.bounds） |

---

## 7. 差异容忍说明

| 差异类型 | 处理方式 | 原理 |
|----------|----------|------|
| 状态栏高度不同 | 自动容忍 | IoU 归一化后，App 内容区元素相对位置一致 |
| 底部导航栏高度/样式不同 | 自动容忍 | 同上；若影响 App 内容区位置可能导致 FAIL |
| 设计稿分辨率/DPI ≠ 设备 | 通过 `dpr` 参数解决 | 间距提取时 `/ dpr` 转换为 dp；匹配时归一化消除尺寸差异 |
| 元素命名不同 | IoU 匹配，不依赖名称 | 仅用位置/尺寸相似度匹配 |

---

## 8. 已知局限

1. **仅验证相邻节点对**：`SpacingRelation` 仅产生于 `flattenNodes` 后的相邻下标对，非全量配对，可能遗漏跨层级关系。
2. **对齐分桶精度**：以 `5 * dpr` 为 bucket 大小，在 2x 设计稿（dpr=2）时容差为 10px，可能将非同行元素错误归为对齐组。
3. **不检查属性**：颜色、字号、圆角等属性需配合 `view-inspect` 单独验证。
4. **节点遮挡**：`layout-dump` 返回所有可见节点，若存在重叠布局（如 FrameLayout），IoU > 0.7 可能误匹配。
