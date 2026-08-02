# wait-logs 工具设计方案

> 创建日期：2026-04-19
> 状态：设计对齐完成，待实施
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 背景

### 1.1 驱动场景

`docs/skills/jugg-android-dev-loop/references/flow_with_auto_run.md` 的 **Step 4: Verify Results** 中，auto-run 代码执行完成的结束信号是日志 marker（`[JUGG_AR] DONE`），当前 sub-flow 使用 `adb logcat -d | grep` 快照轮询：

- agent 无法确定 auto-run 何时完成，只能反复快照 → 时序不稳、上下文浪费
- app 崩溃时 PID 已消失，快照难捞全 crash 段
- 超时无硬边界，可能被卡死

### 1.2 历史脉络（本次方案的前置决策）

| 节点 | 结论 |
|------|------|
| 通用 logcat 包装工具 | **否决**。通用 Android 知识不封装，食谱沉淀到 `logcat_recipes.md` |
| `crash-report` 工具（post-mortem） | **已移除**（commit `ba462a2d0`），不满足 "Jugg 特有价值" 标准 |
| 本方案 | **通过**。"部署时序窗口 + 条件停止" 是 agent 不可替代的语义 |

### 1.3 工具价值定位

满足 Jugg 工具存在三项价值标准中的两项：

| 标准 | 是否满足 | 说明 |
|------|----------|------|
| agent 做不到 / 容易做错 | ✅ | 阻塞式 tail-until-marker/crash 在 Bash tool 下不稳定 |
| 需要访问 IDE 内部状态 | ✅ | `sinceLastDeploy` 依赖 Jugg 记录的部署时刻，agent 无法获取 |
| 封装 Jugg 特有领域知识 | ✅ | `[JUGG_AR] *` marker 协议是 Jugg skill 定义的 |

---

## 2. 功能定义

**`wait-logs`：阻塞式等待日志，直到命中 marker、发生 crash 或超时，返回窗口内过滤后的日志。**

### 2.1 核心行为

1. 以 Jugg 记录的**最近一次 deploy 完成时刻**为日志起点（自然时间）
2. 按"目标进程 PID 集合"和"crash 信号 tag 白名单"合并过滤日志行
3. 实时匹配 marker 正则与 crash 信号，任一命中即停止
4. 硬超时保护，超时返回已收集日志
5. 返回 `stopReason` 明确终止原因

### 2.2 非职责（显式排除）

- **不做 post-mortem**：不读 `-b crash` buffer；app 已崩场景不在本工具范围
- **不做通用 logcat 过滤**：tag 过滤仅作辅助，不作为卖点
- **不做多 marker 组合**：单正则 marker，`|` 自行组合
- **不做长期 follow**：必须有明确超时，无 "永久监听" 模式

---

## 3. 参数与返回

### 3.1 MCP 参数

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `projectDir` | string | **是** | — | 项目绝对路径（pattern: `^/.+`） |
| `marker` | string | **是** | — | 停止 marker 正则（Java Pattern 方言，匹配日志 message 部分） |
| `tags` | array[string] | 否 | `[]` | 关注的 tag 白名单（精确匹配，空数组表示不按 tag 过滤） |
| `timeoutMs` | integer | 否 | `30000` | 硬超时毫秒（范围 `[1000, 300000]`） |

**说明**：

- `marker` 必填。无 marker 即"只等 crash 或超时"，使用场景少、易误用，收益低于复杂度，v1 强制要求
- `sinceLastDeploy=true` 作为**固定行为**不暴露为参数；若 Jugg 无 deploy 记录（如从未部署），返回 `NO_DEPLOY_BASELINE` 错误码
- crash 检测作为**固定行为**内化，命中 crash 信号始终停止；CrashDetector 内部实现，不暴露开关

### 3.2 CLI 参数（1:1 透传）

遵循 `08_cli_tools_list.md §1.3` 的 1:1 透传原则。

```bash
jugg wait-logs \
  --marker '\[JUGG_AR\] DONE' \
  --tags MyAutoRun,AndroidRuntime \
  --timeout-ms 30000
```

kebab-case → camelCase 机械转换后与 MCP 参数对齐。

### 3.3 返回 structuredContent

```json
{
  "status": "OK",
  "message": "stopped by marker",
  "data": {
    "stopReason": "marker | crash | timeout",
    "startTime": "04-19 10:32:15.120",
    "endTime": "04-19 10:32:18.445",
    "targetPids": [18234, 18290],
    "logs": "04-19 10:32:15.120  18234 18234 I MyAutoRun: [JUGG_AR] START ...\n04-19 10:32:18.440  18234 18234 I MyAutoRun: [JUGG_AR] DONE",
    "allLogsPath": "build/jugg/mcp_fetch/wait-logs/wait-logs-20260419-103218.log",
    "truncated": false
  },
  "artifacts": [
    { "type": "file", "path": "build/jugg/mcp_fetch/wait-logs/wait-logs-20260419-103218.log" }
  ],
  "errorCode": null
}
```

**字段说明**：

- `stopReason`：
  - `marker`：marker 正则命中 **且命中行 pid ∈ targetPids**，`logs` 最后一行即为命中行
  - `crash`：crash 信号命中（跨 PID 兜底，不校验 pid），`logs` 尾部即为 crash 片段
  - `timeout`：超时停止
- `logs`：**字符串**，`\n` 分割，每行为 `adb logcat -v threadtime` 原生格式 `MM-dd HH:mm:ss.SSS  pid  tid level tag: message`；**已按 §4.2 过滤策略过滤**，**最多保留最后 100 行**
- `truncated`：`logs` 是否被截断（超过 100 行时为 `true`）
- `startTime` / `endTime`：同 logcat threadtime 格式 `MM-dd HH:mm:ss.SSS`，无年份
- `targetPids`：停止那一刻由 `pidof + ps` 枚举的目标进程集合（停止原因为 `timeout` 时同样枚举一次）
- `allLogsPath`：**完整原始日志**落盘路径（未经 §4.2 过滤，用于排查被过滤掉的噪声）
- `message`：截断时追加 `"logs truncated to last 100 lines, full log at <allLogsPath>"`

> **为什么 logs 用字符串而非结构化数组**：agent 对 logcat 原生格式有先验知识，字符串形态 context 开销最小；结构化解析反而引入重复字段与 JSON quoting 膨胀。需要逐行处理时 agent 自行 `split("\n")` 即可。

### 3.4 错误码

| errorCode | HTTP-like | 触发条件 |
|-----------|-----------|----------|
| `INVALID_PARAMS` | 400 | 参数缺失（含 `marker` 缺失）、`timeoutMs` 越界 |
| `INVALID_REGEX` | 400 | `marker` `Pattern.compile` 失败，message 附列位置 |
| `NO_DEPLOY_BASELINE` | 409 | Jugg 未记录过本项目的 deploy 完成时刻 |
| `NO_DEVICE` | 503 | 无可用设备 |
| `INTERNAL_ERROR` | 500 | adb 调用失败、IO 异常等 |

---

## 4. 实现设计

### 4.1 日志起点：logcat 原生时间戳

- **数据源**：`deployTargetManager` 或新增的 `LastDeployTimestampRegistry`，在 `deploy` / `restart` 成功路径末尾写入 `"MM-dd HH:mm:ss.SSS"`（与 `-v threadtime` 输出一致）
- **查询**：wait-logs 启动时读取该时间戳作为 `adb logcat -T "<time>"` 的起点
- **清理**：不主动过期；下一次 deploy 覆盖即可

### 4.2 过滤策略：采集期粗过滤 + 返回前精过滤（双阶段）

**采集阶段不依赖 targetPids**，原因：子进程可能在 wait-logs 启动之后才 fork，启动时枚举会遗漏。

#### 4.2.1 采集期粗过滤（边收边过滤）

对每行 logcat 原生字符串，解析一次行头 `MM-dd HH:mm:ss.SSS  pid  tid level tag: message` 取出 `pid` / `tag`，用于：

```
进入缓冲区条件 = (
  (tags 为空 OR tag ∈ tags)       -- 用户关注的 tag
  OR
  (tag ∈ CRASH_TAGS)              -- crash 兜底白名单
)
```

**粗过滤后量级已很小**（agent 传 tags 时过滤严格；不传 tags 时也只保留 CRASH_TAGS）。

保留时**原样输出行字符串**（不重组），保证输出与 `adb logcat -v threadtime` 形态一致。

**缓冲区上限**：环形缓冲 10000 行（兜底防 OOM），超出后淘汰最早行；**原始全量日志同时落盘到 `allLogsPath`**（不经过滤，用于事后排查被过滤掉的噪声）。

#### 4.2.2 停止判定（边收边判）

停止判定涉及三条独立查询轴，请区分：

| 用途 | 查询命令 | 节流策略 | 检测目标 |
|------|---------|---------|---------|
| marker PID 校验 | `pidof` + `ps -ef \| grep <pkg>:` | 200ms 缓存 | 完整 `targetPids`（含子进程） |
| crash 主进程存活判定 | 仅 `pidof <pkg>` | 500ms 节流 | 仅主进程是否死亡 |
| 返回前精过滤 | `pidof` + `ps -ef \| grep <pkg>:` | 停止后查一次 | 完整 `targetPids`（含子进程） |

**伪代码**：

```
state: lastTargetPidsQuery = 0, cachedTargetPids = ∅
       lastCrashCheck = 0, lastCrashResult = null
       mainProcessEverSeen = false

for each line:
  // --- marker 判定 ---
  if line matches marker:
      if now - lastTargetPidsQuery >= 200ms:
          cachedTargetPids = pidof + ps 枚举子进程       // 完整 targetPids
          lastTargetPidsQuery = now
      if line.pid ∈ cachedTargetPids:
          stopReason = "marker"; break
      else:
          continue    // 其它 app 或外部进程打同名 marker，忽略

  // --- crash 判定（仅关注主进程存活）---
  if CrashDetector.classify(line) != NONE AND line.tag ∈ CRASH_TAGS:
      if now - lastCrashCheck < 500ms AND lastCrashResult == ALIVE:
          continue    // 节流：同一 crash stacktrace 多行期间不反复查
      mainPids = adb shell pidof <packageName>
      lastCrashCheck = now
      if mainPids 非空:
          mainProcessEverSeen = true
          lastCrashResult = ALIVE
          continue    // 主进程还活着，这个 crash 不是我们的
      else:
          lastCrashResult = DEAD
          if mainProcessEverSeen:
              stopReason = "crash"; break
          else:
              continue    // 主进程从未启动，忽略启动前的系统 crash

  // --- 超时 ---
  if elapsed > timeoutMs:
      stopReason = "timeout"; break
```

**设计要点**：

- **v1 明确放弃子进程 crash 检测**：crash 判定只查主进程 `pidof <pkg>`，实现简单可靠，不做 PID 集合 diff / 文本归属解析
- **子进程 marker / 精过滤仍覆盖**：marker PID 校验和精过滤用完整 `targetPids`（pidof + ps 枚举子进程），子进程打的 marker 照常能停，子进程日志照常返回
- **marker PID 校验必做**：避免其它 app 偶然打出同名字符串误停
- **`mainProcessEverSeen` 防误触发**：主进程从未启动（`pidof` 从未返回过非空）时，忽略 crash 行，避免启动前的系统 crash 被误识别
- **500ms crash 节流**：同一次 crash 会产生多行 stacktrace，避免每行都查 `pidof`
- **多实例场景**：`pidof` 返回多个 PID 时全部纳入；只要**至少一个**主进程还活着，就不触发 crash 停止（接受"任一实例崩不触发"的权衡）

#### 4.2.3 返回前精过滤

停止后（含 timeout 分支），再拿一次最终 `targetPids`（`pidof + ps` 完整枚举，覆盖停止前新 fork 的子进程），对缓冲区做二次过滤：

```
keep(line) = (
  (line.pid ∈ targetPids)           -- 本次启动的目标进程（含子进程）
  OR
  (line.tag ∈ CRASH_TAGS AND CrashDetector.classify(line) != NONE)
)
AND (tags 为空 OR line.tag ∈ tags)   -- 用户显式过滤
```

精过滤后取**最后 100 行**作为 `logs` 返回，多余部分丢弃（但 `allLogsPath` 保留全量未过滤）。

**`targetPids` 计算（v1 支持多进程；精过滤与 marker 共用）**：

- 主进程：`adb shell pidof <packageName>` 可能返回多个 PID（多实例 / monkey），全部纳入
- 子进程：`adb shell ps -ef | grep <packageName>:` 枚举独立进程（`:push`/`:web`/`:render` 等），全部纳入
- 合并后去重，最终 `targetPids` 为 Set<Int>

**`CRASH_TAGS`**：`{AndroidRuntime, libc, DEBUG, tombstoned, ActivityManager}`（沿用 crash-report 原逻辑）

**降级**：停止时 `targetPids` 仍为空（app 从未启动）→ 精过滤只保留 CRASH_TAGS 命中行 + 用户 tags 匹配行

### 4.3 crash 信号识别（复用 crash-report 实现）

**抽取共享工具类 `CrashDetector.kt`**（internal，不作为独立工具暴露）：

```kotlin
object CrashDetector {
  // Strong markers: immediate stop
  // Weak markers: fallback aggregation
  fun classify(rawLine: String): CrashSignal   // STRONG | WEAK | NONE
  fun extractSnippet(rawLines: List<String>, hitIndex: Int): String
  val CRASH_TAGS: Set<String>
}
```

- 入参直接是 logcat 原生行字符串（`-v threadtime` 格式），避免额外的结构化解析开销
- 从 `git show ba462a2d0^:src/main/.../CrashReportMcpToolAction.kt` 取回原实现
- 仅抽 "crash 信号识别 + snippet 截取" 两部分，采集流程不复用

### 4.4 执行流程

```
1. 参数校验（INVALID_PARAMS / INVALID_REGEX）
2. 读取 sinceLastDeploy 时间戳（NO_DEPLOY_BASELINE）
3. 启动 adb logcat -T "<time>" -v threadtime（子进程）
4. 每行:
   - 同步写入 allLogsPath（全量原始流，不落盘已淘汰的行）
   - 粗过滤（tags 白名单 OR CRASH_TAGS）→ 命中则入环形缓冲区（上限 10000 行，溢出淘汰最老）
   - 判停（见 §4.2.2 三轴）:
     · marker 命中 + PID ∈ targetPids(200ms 缓存) → 停
     · CrashDetector 命中 + 主进程 pidof 为空(500ms 节流) + mainProcessEverSeen → 停
     · 超时 → 停
5. 停止后:
   - 拿最终 targetPids（pidof + ps 完整枚举）
   - 用 targetPids 对缓冲区做精过滤（§4.2.3）
   - 取精过滤结果最后 100 行组装 logs（超过则 truncated=true）
6. 杀 adb 子进程 → 返回 structuredContent
```

**超时实现**：使用 `ProcessBuilder` + 独立读取线程，监控超时用 `ScheduledExecutorService`，超时调 `Process.destroy()`。

**落盘文件**：`allLogsPath` 仅包含**未被环形缓冲淘汰**的原始流（即最多 10000 行的最近窗口）。理由：超过 10000 行的日志体量已失去排查价值（见 §8 风险权衡）。

### 4.5 正则方言

- Java `Pattern`（PCRE-like，支持 `\d`、`\w`、`*?`、`(?i)` 等）
- 匹配范围：**只匹配日志 message 部分**，不含行头 `time/pid/tag/level`
- 入参校验：`Pattern.compile(marker)` 失败 → `INVALID_REGEX`，message 包含 `PatternSyntaxException.getIndex()`
- skill 文档需明确标注 "regex dialect = Java Pattern"

---

## 5. 文件改动清单

### 5.1 新增代码

| 文件 | 说明 |
|------|------|
| `src/main/.../mcp/actions/WaitLogsMcpToolAction.kt` | 工具入口 |
| `src/main/.../mcp/util/CrashDetector.kt` | 共享 crash 识别（从历史 commit 取回并重构） |
| `src/main/.../mcp/util/LastDeployTimestampRegistry.kt` | 部署时刻记录（或并入 deployTargetManager） |
| `docs/skills/jugg-android-dev-loop/scripts/py/cmd/cmd_wait_logs.py` | CLI 入口 |

### 5.2 修改代码

| 文件 | 变更 |
|------|------|
| `McpToolActionRegistry.kt` | 注册 `WAIT_LOGS` 常量 + action |
| `DeployMcpToolAction.kt` / `RestartMcpToolAction.kt` | 成功路径末尾写入 LastDeployTimestamp |
| `jugg.py` | USAGE 行 + COMMANDS 字典新增 `wait-logs` |

### 5.3 新增测试（TDD 先行）

| 测试文件 | 覆盖场景 |
|----------|---------|
| `WaitLogsMcpToolActionTest.kt` | marker 命中、**主进程 crash 命中**、超时、无效正则、缺 marker、无 deploy 基线、多主进程 PID、子进程 marker（PID 校验通过）、tag 过滤、**marker 被其它 app 误触发（PID 校验拦截）**、**其它 app crash 不误触发（主进程仍活着）**、**启动前系统 crash 不误触发（mainProcessEverSeen=false）**、**子进程自然退出不被误识别为 crash**、**子进程 crash 被忽略（主进程仍活着）**、**超过 100 行触发 truncated=true**、**缓冲区溢出 10000 行环形淘汰** |
| `CrashDetectorTest.kt` | strong/weak 信号分类、snippet 截取边界（向后80行、换行截断） |
| `McpInvokerTestBase.kt` | fake wait-logs action 注册 |
| `McpInvokerToolSuccessTest.kt` | `testWaitLogsToolCallSuccess` |
| `McpInvokerValidationTest.kt` | `testWaitLogsRejectMissingMarker` |

### 5.4 文档同步

| 文件 | 变更 |
|------|------|
| `docs/ai_knowledge/08_mcp_tools_list.md` | 工具数 17 → 18，新增 `wait-logs` 小节 |
| `docs/ai_knowledge/08_cli_tools_list.md` | 新增 CLI 条目 |
| `docs/ai_knowledge/98_code_map.md` | 新增 `WaitLogsMcpToolAction` / `CrashDetector` 路径 |
| `docs/ai_knowledge/06_testing.md` | 新增测试表格条目 |
| `docs/skills/jugg-android-dev-loop/SKILL.md` | Runtime Basic Commands 列入 `wait-logs` |
| `docs/skills/jugg-android-dev-loop/references/flow_with_auto_run.md` | **Step 4 Log Verification Sub-flow 改写**（见 §6） |
| `docs/skills/jugg-android-dev-loop/references/cli_manual.md` | 新增命令条目 |
| `docs/skills/jugg-android-dev-loop/references/logcat_recipes.md` | 顶部加一句 "优先使用 `wait-logs`，食谱用于长尾场景" |

---

## 6. flow_with_auto_run.md Step 4 改写预案

**现状**（L58-62）：

```
Log Verification Sub-flow:
1. Run `adb logcat -d -s <TAG>` or `adb logcat -d | grep -E '<regex>'`.
2. Parse auto-run log output for expected markers/values.
3. Match against expected results.
```

**改写后**：

```
Log Verification Sub-flow:
1. Call `wait-logs --marker '\[JUGG_AR\] DONE' --timeout-ms 30000`.
2. Branch on stopReason:
   - marker  → parse logs[] for expected values → match against expected.
   - crash   → FAIL; crashSnippet is the cause.
   - timeout → INCONCLUSIVE; check if auto-run hung or missed DONE marker.
3. For long-tail scenarios (custom tag, no deploy baseline) fall back to `logcat_recipes.md`.
```

---

## 7. 实施步骤（TDD）

1. **写失败测试**：`CrashDetectorTest.kt` + `WaitLogsMcpToolActionTest.kt`（覆盖 §5.3 全部场景）
2. **实现 CrashDetector**：从 commit `ba462a2d0^` 取回并重构
3. **实现 LastDeployTimestampRegistry**：或并入 deployTargetManager；修改 deploy/restart 写入点
4. **实现 WaitLogsMcpToolAction**：执行流程见 §4.4
5. **CLI 层**：新增 `cmd_wait_logs.py`、修改 `jugg.py`
6. **跑全量测试**：确保绿
7. **文档同步**：按 §5.4 清单更新
8. **commit**：前缀 `[feature]`

---

## 8. 风险与权衡记录

| 风险 | 缓解 |
|------|------|
| `adb logcat -T` 在不同设备/Android 版本时区解析不一致 | 使用 `-v threadtime` 统一格式；timestamp 来自本机，不依赖设备 TZ 转换 |
| 子进程在 wait-logs 启动后才 fork | **采集期不依赖 targetPids**，返回前才二次过滤（§4.2.3） |
| 多主进程 PID（多实例）语义不一 | v1 全部纳入；只要至少一个主进程活着就不触发 crash 停止 |
| marker 被其它 app 偶然打出导致误停 | marker 命中时查 `pidof+ps` 校验 PID ∈ targetPids（§4.2.2） |
| marker 命中但 app 已退出（pidof 返回空） | 忽略本次命中继续采集，由超时兜底收尾 |
| 其它 app 的 crash 日志误触发停止 | crash 判定改为"主进程 `pidof` 存活"判据，不解析日志文本（§4.2.2） |
| 启动前的系统 crash 日志误触发 | `mainProcessEverSeen` 守卫：主进程从未启动时忽略所有 crash 行 |
| 目标 app 被 LMK 杀 + 其它 app crash 同时发生 | 极低概率误报为 crash；可接受（LMK 本身也是异常终止） |
| v1 漏捕子进程 crash | 明确不支持；agent 需要时可将子进程 tag 加入 `tags` 参数，在返回 logs 中自行识别 |
| 缓冲区被海量噪声撑爆 | 粗过滤（tags/CRASH_TAGS）+ 环形缓冲 10000 行硬上限 |
| 正则性能（超长日志流） | Java `Pattern` 编译一次复用；行级匹配，不跨行 |
| `allLogsPath` 不含被环形淘汰的行 | 设计选择：超 10000 行后最早的日志通常已失去排查价值；真需要超大日志时 agent 应收紧 tags |

---

## 9. 未来扩展（不在本次范围）

- `sinceTime` 参数作为 `sinceLastDeploy` 的逃生舱（手动指定起始时间）
- `mode=snapshot` + `timeoutMs=0` 扩展为 post-mortem 模式（当 YAGNI 被证伪时）
- 多 marker 组合 "全部命中"（若出现真实需求）
- `marker` 可选化（即 "只等 crash" 模式）
- **子进程 crash 检测**（v1 明确放弃；若确实需要，可通过解析 crash 日志文本归属或维护子进程 PID 基线 diff 实现）
- follow 模式集成到长期观测工具（跨会话）
- `allLogsPath` 保留被环形淘汰的行（通过直接 tee 到文件而非从缓冲区落盘）

> 以上均为 v2+ 设想，v1 不实现。
