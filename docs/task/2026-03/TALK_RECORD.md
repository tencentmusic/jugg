问：
› docs/task/2026-03/REPORT.md 上一轮我和你做了一个性能测试，全量的 heap.peakMb 增长过大，需要优化。你先分析增量组成，然后给出优化方
案

───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

答：
• 结论先给：heap.peakMb 的大头是 first scan 的瞬时对象暴涨，不是增量复用链路本身。

我已把完整分析和落地方案写到：
HEAP_OPTIMIZATION_PLAN.md

增量组成（基于当前 benchmark）

- first_scan 总增量：3154.1123 - 48.4531 = 3105.6592 MB
- 瞬时增量（峰值后回收）：3154.1123 - 1221.2764 = 1932.8359 MB（62.2%）
- 驻留增量（扫描后仍在）：1221.2764 - 48.4531 = 1172.8233 MB（37.8%）
- 依据数据：ANALYSIS_REPORT.md:33

代码级主因（按优先级）

1. overlay definitions 被逐文件重复重建（P0，最关键）

- analyzeFiles 中逐文件调用 parseReferencesByDbSessionMode(...)：
  ConstRefEngine.kt:456
- 该函数每次都 parsedDefinitionsByPath.values.flatten() 并建 3 个 overlay map：
  ConstRefEngine.kt:600

2. lookup cache 在文件循环前后反复清空（P1）

- ConstRefEngine.kt:461

3. simpleName 查询先拉全量 latest definitions 再 JVM 过滤（P1）

- ConstRefCacheDatabase.kt:715

4. 驻留基线来自解析器环境+会话缓存（P2）

- 缓存默认值：file=500, lookup=4000, ttl=15min
  ConstRefEngine.kt:1140

优化方案（摘要）

- P0：overlay 从“逐文件构建”改为“单次构建复用”，references 改批处理接口。
- P1：缩小 lookup cache 清理粒度；queryClassesBySimpleNames 增加 SQL 过滤。
- P2：full scan chunk 化 + 临时容器阶段释放；最后再调缓存上限。
- 验收目标：first_scan.heap.peakMb <= 2300MB，duration <= 360000ms，增量复用率 >=0.99。

已读取文档列表

- docs/ai_knowledge/00_overview.md
- docs/ai_knowledge/97_ai_usage.md
- docs/ai_knowledge/98_code_map.md
- docs/ai_knowledge/03_deploy_const_ref.md
- docs/ai_knowledge/02_compile_core.md
- docs/task/2026-03/REPORT.md
- docs/task/2026-03/ANALYSIS_REPORT.md

答案依据小节定位

- 97_ai_usage.md §2, §3（检索顺序与 const ref 任务路由）
- 03_deploy_const_ref.md §3.1, §5.1, §5.2, §5.4（ConstRefEngine 设计与缓存/命中链路）
- ANALYSIS_REPORT.md §3.1~§3.3（本次 benchmark 原始数据）
- 代码细节以 ConstRefEngine.kt / ConstRefCacheDatabase.kt 为准。


────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
问：
› 这个方案我目前还不太理解，我的思路是，创建出来的对象，尽可能避免堆积，写入db后取消引用，这样内存增量可以控制在一个比较小的增量；此外也可以移除不必要的字段
（如有）。还有就是 constref 应该并不依赖其他文件，应该可以实现小批量解析生成，写入db即释放的效果？


────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

答：
• 你的方向是对的。我先快速确认一件关键事实：sessionCache 里现在是否真的缓存了“写库后不再需要”的字段（例如 references），然后给你一个可直接落地的“小批量解析->写
库->释放”执行方案。


• 你的判断方向是对的，而且可以落地。

你这句话我补充一下边界：
constref 里“定义解析”基本可按文件独立；但“引用解析”需要依赖其他文件的常量定义索引，所以不是完全无依赖。

最稳妥的做法是你说的“写库即释放”，但采用“两阶段流式”：

1. 第一阶段（Definitions）

- 按小批量解析 definitions（如 100~300 文件/批）。
- 每批立刻 upsert 到 DB。
- 批次结束就清空该批内存对象（definitions map/list）。

2. 第二阶段（References）

- 等第一阶段全量 definitions 入库后，再按小批量解析 references。
- references 解析时从 DB 查候选 definitions，不保留全量 parsedDefinitionsByPath。
- 每批写库后立即释放临时对象。

这样可以保证正确性（避免“后面文件定义影响前面文件引用”漏算），同时把峰值内存压平。

你提到“移除不必要字段”，这里有一个立即可做的点：

- ConstRefSessionCache.FileCacheEntry 里存了 references，但当前代码只读 getFileDefinitions()，references 没被读取。
- 这个字段可以先删（或改成不缓存），能直接减少驻留内存。

另外一个关键点：

- 现在热点是 parsedDefinitionsByPath.values.flatten() 在逐文件循环里重复构建（这是峰值主因），必须改成“批次级一次构建或完全移除全量 overlay”。

如果你同意，我下一步直接按这个方案改代码（先做最小改动版：去掉重复 flatten + 去掉 sessionCache 中无用 references 缓存），然后跑同一 benchmark 对比。

已读取文档：

- 00_overview.md
- 97_ai_usage.md
- 98_code_map.md
- 03_deploy_const_ref.md
- 02_compile_core.md
- ANALYSIS_REPORT.md

依据小节：

- 03_deploy_const_ref.md §3.1, §5.1, §5.2, §5.4
- ANALYSIS_REPORT.md §3.1~§3.3
- 代码以 ConstRefEngine.kt / ConstRefSessionCache.kt / ConstRefCacheDatabase.kt 为准。

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

问：
› 针对“但“引用解析”需要依赖其他文件的常量定义索引，所以不是完全无依赖”这一个点，我想知道在文件增量更新扫描时，如何做到依赖其他文件的常量定义索引

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

答：
• 你这个问题很关键。constref 在“增量扫描”里依赖其他文件常量索引，靠的是 DB 最新快照 + 本批次内存 overlay 两层。

1. 文件保存后先入待分析队列
   onFileSaved 只做排队，不立即全量解析。
   参考：ConstRefEngine.kt:69
2. 增量任务执行时，先算 checksum，能复用就直接 touch DB
   如果内容未变，直接复用已有分析版本，不重解析。
   参考：ConstRefEngine.kt:397
3. 对“确实变更”的文件先解析 definitions
   先拿到当前批次 changed files 的 definitions。
   参考：ConstRefEngine.kt:447
4. 解析 references 时，候选定义来源有两部分

- DB：按 constName/class+const/package+const/simpleClass 查“其他文件”最新定义
- overlay：本批 changed files 尚未全部落库时，用内存 definitions 补齐
  参考：ConstRefEngine.kt:600
  参考：ConstRefCacheDatabase.kt:590

5. 解析完立刻 upsert 到 DB，成为后续文件的“其他文件索引”
   所以增量轮次内后处理文件能看到前处理文件的最新定义；跨轮次依赖靠 DB 持续累积。
   参考：ConstRefEngine.kt:466

一句话总结：
不是“每次去读所有其他文件源码”，而是“读 DB 的全局常量索引 + 本批内存补丁”，这就是增量时依赖其他文件定义的实现方式。

已读取文档列表：

- docs/ai_knowledge/00_overview.md
- docs/ai_knowledge/97_ai_usage.md
- docs/ai_knowledge/98_code_map.md
- docs/ai_knowledge/03_deploy_const_ref.md
- docs/ai_knowledge/02_compile_core.md
- docs/task/2026-03/ANALYSIS_REPORT.md

答案依据小节：

- 03_deploy_const_ref.md 的 §3.1、§5.1、§5.2、§5.4（full scan/增量/DB-session/命中链路）
- 97_ai_usage.md 的 §2、§3（任务路由）

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

问：

› 我不理解这个依赖，是必要的吗？如果在全量扫描的情况下，前面的文件依赖了后面未曾解析的文件索引，会发生什么现象？


────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

答：
• 这个依赖在当前语义下是必要的，不是“实现习惯”而是“正确性约束”。

1. 为什么必要
   constref 的目标是找“某个引用是否指向一个真实存在的常量定义”。
   如果没有“其他文件的定义索引”，很多引用只能看到 A.CONST 文本，无法确认 A 里是否真有 CONST，也无法处理同名常量歧义（import、同包、别名等）。
2. 全量扫描里如果前文件依赖后文件、但后文件还没进索引，会怎样
   会出现顺序相关的漏报（false negative）：

- 前文件引用关系没被记录进 const_references。
- 后续某次常量变更时，受影响文件集合不完整。
- 直接表现为：本该重编译的引用方没被重编译，热更/增量行为偶发不生效。
- 通常“下一轮扫描”可能补齐，所以会呈现“第二次好了”的不稳定现象。

3. 当前实现如何避免这个问题
   当前 full scan 实际是“两阶段”：

- 先把本轮 changed files 的 definitions 全部解析出来；
- 再做 references 解析（用 DB + 本轮 overlay definitions）。
  所以不会因为“文件顺序”导致正确性错误。
  代价是内存峰值偏高，这也是你们这次要优化的点。

你想做的小批量“写库即释放”是可行的，但要保持这条约束：

- 要么“全量 definitions 先就绪，再解析 references”；
- 要么用“延迟补偿机制”保证前批引用在后批定义就绪后会被回填。
  否则就会回到顺序漏报问题。

已读取文档列表（本会话）：

- docs/ai_knowledge/00_overview.md
- docs/ai_knowledge/97_ai_usage.md
- docs/ai_knowledge/98_code_map.md
- docs/ai_knowledge/03_deploy_const_ref.md
- docs/ai_knowledge/02_compile_core.md
- docs/task/2026-03/ANALYSIS_REPORT.md

答案依据小节定位：

- 03_deploy_const_ref.md：§3.1、§4.2、§5.1、§5.4（定义/引用解析与 DB-session 查找模式）
- 97_ai_usage.md：§2、§3（const ref 任务路由）
- 若文档与实现不一致，以代码为准。


────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
问：
› 疑问：1. 同名常量歧义（import、同包、别名等），为何在编译时不会出现，而ast语法解析则会出现；2. 歧义问题出现的多吗？如果忽视歧义一律保存，记录数量会增加多少
3. 如果方案改为忽视歧义问题，一律保存，在识别影响时歧义项目会自然被过滤。这样方案是不是会获得巨大的优化


────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
答：
• 你这 3 个问题都很关键，结论先说：

- “忽视歧义一律保存”可以提速一部分，但不太可能是“巨大优化”的主因。
- 你们当前 heap.peakMb 的主因仍是我前面说的 重复构建大集合（flatten + overlay 反复做），不是歧义判定本身。

1. 为什么编译不歧义，AST 会歧义

- 编译器有完整语义信息：classpath、符号表、类型推断、可见性规则、import 解析、重载决议。
- 你们现在的 constref 解析是“轻语义”：JavaParser/Kotlin PSI + 本地索引/DB 查询，不是完整 type resolver。
- 所以编译期能唯一绑定，AST 侧在 simpleName、* import、top-level const、同名类/常量时会出现多候选。

2. 歧义多不多？忽视后记录会涨多少？

- 一般场景（大量 Owner.CONST）歧义不高。
- 高风险点是无 owner 的名字引用（静态导入、星号导入、Kotlin top-level）。
- 若“一律保存所有候选”，引用记录量大致从 R 变成 R * k（k=平均候选数），常见会到 1.2x~3x，坏场景可更高（常量名如 TAG/DEBUG/VERSION 会放大）。

3. 忽视歧义后，影响阶段会“自然过滤”吗？

- 不会自然过滤干净，只会变成更多 false positive。
- 因为影响计算是按 (defFqClassName, constName) 命中的；你多存了候选，就会多触发受影响文件。
- 结果是：增量重编译范围膨胀、耗时上升，可能抵消你在扫描阶段省下的时间/内存。
- 所以它更像“用精度换吞吐”的策略，不是纯收益优化。

更稳的方向

- 先做结构性优化：去掉 overlay 的重复构建（主收益点）。
- 再考虑“歧义宽松模式”作为可配置开关（例如 benchmark/perf mode），默认仍保持当前精度。

已读取文档列表：

- docs/ai_knowledge/00_overview.md
- docs/ai_knowledge/97_ai_usage.md
- docs/ai_knowledge/98_code_map.md
- docs/ai_knowledge/03_deploy_const_ref.md
- docs/ai_knowledge/02_compile_core.md
- docs/task/2026-03/ANALYSIS_REPORT.md

答案依据小节定位：

- 03_deploy_const_ref.md：§4.2（引用解析覆盖与边界）、§5.1（DB-session 查找模式）、§5.2（索引/持久化）
- ANALYSIS_REPORT.md：§3.1~§3.3（heap 与扫描数据）
- 代码依据：ConstRefEngine.kt（parseReferencesByDbSessionMode 调用链）与 ConstRefCacheDatabase.kt（候选查询逻辑）
- 若文档与代码不一致，以代码为准。
