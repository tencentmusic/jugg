# ViewHierarchy 可靠性问题修改意见（2026-03-03）

> 来源：review 结果 + 本轮代码核查  
> 适用范围：`jvmti_agent`（设备端）+ `main`（IDE 端）  
> 一致性规则：文档与代码冲突时，以代码为准。  
> 交付对象：后续实施修复的 agent

---

## 1. 目标

本次仅聚焦 3 个已确认问题，提升 `layout_dump` 与 element-mode `tap` 的可用性和稳定性：

1. 过滤不可见节点，避免误判多匹配。
2. 初始化失败后允许重试，避免永久失效。
3. 多进程场景下正确选择 socket（主进程优先，子进程兜底）。

---

## 2. 问题总览（按优先级）

1. `P2` 不可见节点污染 selector 匹配。  
证据：`ElementFinder.findInView()` 在命中判断前未做可见性/可用 bounds 过滤。  
参考：[ElementFinder.java](../../jvmti_agent/src/main/java/com/sickworm/intellij/jugg/viewhierarchy/ElementFinder.java)

2. `P2` 初始化标记置位时机错误。  
证据：`ViewHierarchyServerLoader.init()` 在 `ViewHierarchyServer.start()` 前将 `sInitialized=true`。  
参考：[ViewHierarchyServerLoader.java](../../jvmti_agent/src/main/java/com/sickworm/intellij/jugg/viewhierarchy/ViewHierarchyServerLoader.java)

3. `P3` 仅取首个 PID，可能选错进程 socket。  
证据：`ViewHierarchyClient.parseFirstPid()` 遇到多 PID 输出仅返回第一个数字 token。  
参考：[ViewHierarchyClient.kt](../../main/src/main/java/com/sickworm/intellij/jugg/mcp/viewhierarchy/ViewHierarchyClient.kt)

---

## 3. 修改意见明细

### 3.1 P2: 先过滤不可见节点再判定命中

### 现象与影响

`find_and_tap` 在元素唯一性判断时把 `GONE/INVISIBLE/零尺寸` 节点也算作命中。  
当页面存在同文案/同 id 的隐藏模板节点时，会触发 `Multiple elements matched`，导致拒绝点击。

### 建议改法（必须）

1. 在 `ElementFinder` 中引入“可操作节点”判定，命中判断前先过滤。  
2. 可操作节点建议至少满足以下条件：
   - `view.getVisibility() == View.VISIBLE`
   - `view.isShown() == true`
   - `width > 0 && height > 0`
   - `bounds.right > bounds.left && bounds.bottom > bounds.top`
3. 只有通过上述过滤的节点才进入 `isMatch(...)` 命中判断。

### 取舍说明（来自本轮探索）

收益：
1. 减少“假多匹配”报错，element-mode `tap` 成功率更高。
2. 结果语义更贴近真实用户可点击目标。

代价：
1. 动画过渡瞬间，节点可能短时不可见，出现短时 `not found`。
2. 调试隐藏节点时信息会变少。

建议：
1. 当前 patch 先保障 `find_and_tap` 稳定性。  
2. 隐藏节点调试需求由 `layout_dump` 承担，不在本次 patch 引入额外模式开关。

### TDD 用例（先写测试）

1. 构造同 selector 的两个节点：一个 `VISIBLE`、一个 `GONE`，期望仅匹配可见节点。  
2. 构造 `VISIBLE` 但 `width/height=0` 节点，期望不计入匹配。  
3. 构造两个都可见且可用节点，期望仍返回多匹配错误。

---

### 3.2 P2: 初始化标志仅在启动成功后置位

### 现象与影响

`ViewHierarchyServerLoader.init()` 里先置 `sInitialized=true` 再启动。  
首次启动抛异常时，该标志不会回滚，后续所有 `init()` 调用直接跳过，服务永久不可用。

### 建议改法（必须）

1. 调整时序：先尝试启动，成功后再置 `sInitialized=true`。  
2. 启动失败时保持 `sInitialized=false`，允许下一次重试。  
3. 建议把 `ViewHierarchyServer.start(...)` 改为可返回成功状态（`boolean`）或在异常路径显式回滚实例状态，避免“半初始化”。

### 设计注意

1. `init()` 仍保持 `synchronized`，避免并发重复启动。  
2. 失败日志要保留（现有 `LogUtils.e` 可继续复用）。

### TDD 用例（先写测试）

1. 首次 `start()` 抛异常，第二次 `init()` 能再次进入启动流程。  
2. 首次成功后，多次 `init()` 仍只启动一次。  
3. `context == null` 时保持安全返回。

---

### 3.3 P3: socket 选择改为“主进程优先 + 全量 PID 兜底”

### 现象与影响

多进程应用下，`pidof`/`ps` 可能返回多个 PID。  
当前只取首个 PID，可能命中不承载当前界面的进程，导致误报 server 不可用。

### 建议改法（必须）

1. 把“单 PID”改为“候选 PID 列表”：
   - 收集全部 PID（不要截断为第一个）。
   - 尽量补齐 `processName` 信息用于排序。
2. 候选排序策略：
   - 第一优先：`processName == packageName`（主进程）
   - 第二优先：其他同包进程（如 `package:xxx`）
3. `resolveSocketCandidates()` 输出顺序：
   - `jugg_vh_<pid1>`
   - `jugg_vh_<pid2>`
   - ...
   - `jugg_vh`（兼容名，最后兜底）
4. `sendRequest()` 逐个 socket 尝试，任一成功即返回。

### 取舍说明（来自本轮探索）

1. “非 UI 进程”是工程语义，不是 Android 官方术语。  
2. Android 官方是“多进程”概念（`android:process`/`isolatedProcess`）。  
3. 在本模块里可把“UI 进程”理解为：当前能提供目标界面窗口树的进程。  
4. 不能假设 UI 一定在主进程，因此必须保留“遍历所有 PID”兜底。

### TDD 用例（先写测试）

1. `pidof` 返回多个 PID，首个不可连、次个可连，期望最终成功。  
2. 同时存在主进程与子进程，主进程可连时应先命中主进程。  
3. 所有 `jugg_vh_<pid>` 不可连时，仍尝试 `jugg_vh`。

---

## 4. 建议实施顺序（给执行 agent）

1. 先补测试，再改实现（TDD）。  
2. 优先修 `P2` 两项，再修 `P3`。  
3. 最后回归 `tap` 与 `layout_dump` 的现有测试：
   - `main/src/test/java/com/sickworm/intellij/jugg/mcp/actions/TapMcpToolActionTest.kt`
   - `main/src/test/java/com/sickworm/intellij/jugg/mcp/actions/LayoutDumpMcpToolActionTest.kt`

---

## 5. 验收标准

1. 存在隐藏重复节点时，element-mode `tap` 不再误报多匹配。  
2. 首次初始化失败后，再次触发初始化可恢复服务能力。  
3. 多进程应用下，不因首个 PID 选错而误报 server unavailable。  
4. 现有 `TapMcpToolAction` / `LayoutDumpMcpToolAction` 测试不回退。

---

## 6. 实施结果（2026-03-03）

已按本方案落地，完成项：
1. `ElementFinder` 新增可操作节点过滤（可见、isShown、非零尺寸、有效 bounds）。
2. `ViewHierarchyServerLoader` 调整为“启动成功后置位初始化标志”，失败允许重试。
3. `ViewHierarchyServer.start(...)` 改为返回启动结果，并在失败路径回滚实例状态。
4. `ViewHierarchyClient` 改为多 PID 候选 + 主进程优先排序 + `jugg_vh` 兼容兜底。

新增测试：
1. `jvmti_agent/src/test/.../ElementFinderTest.java`
2. `jvmti_agent/src/test/.../ViewHierarchyServerLoaderTest.java`
3. `main/src/test/.../ViewHierarchyClientTest.kt`

回归验证：
1. `TapMcpToolActionTest` 通过。
2. `LayoutDumpMcpToolActionTest` 通过。
