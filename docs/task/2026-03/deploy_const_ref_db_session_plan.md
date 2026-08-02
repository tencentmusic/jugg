# 常量引用低内存方案（DB 主导 + 会话缓存）

> 文档版本: v1.0  
> 创建时间: 2026-03-01  
> 适用范围: `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/*`  
> 一致性规则: 文档与代码冲突时，以代码为准。

---

## 1. 背景与目标

现状中，`ConstRefEngine` 会在索引初始化阶段把“当前仓库最新定义”批量加载到内存并构建全局索引。  
在大仓库下，该模式会将内存占用与仓库总体规模绑定。

本方案目标：

- 以 SQLite 作为主数据源（source of truth）；
- 内存只保留“本次 IDE 运行期间编译过/检查过文件”的会话级缓存；
- 保持现有 const-ref 功能语义不变（effected files、ready/timeout、降级逻辑）。

---

## 2. 现状问题（以代码为准）

- 全量初始化入口在 `ConstRefEngine.ensureDefinitionIndexInitializedLocked()`；
- 初始化逻辑会调用 `database.getAllDefinitions()`，再写入：
- `cachedDefinitionsByFile`
- `ConstDefinitionIndex`
- 受影响查询本身已经 DB 驱动（`ConstRefImpactResolver` -> `ConstRefCacheDatabase`），但解析引用时仍依赖全局内存定义索引。

---

## 3. 设计原则

- DB-first：所有权威数据来自 `ConstRefCacheDatabase`。
- Session-only memory：内存缓存只存会话热点，不存全仓库镜像。
- 语义等价：不改变“哪些文件会被判定为受影响”。
- 可回滚：保留旧路径开关，支持快速切回 legacy 逻辑。

---

## 4. 目标架构

### 4.1 分层

- 持久层：`ConstRefCacheDatabase`
- 会话层：`ConstRefSessionCache`（新增）
- 计算层：`ConstRefEngine`（重构）

### 4.2 会话缓存（新增）

`ConstRefSessionCache` 建议包含：

- `fileCache`：`filePath -> latest definitions/references/checksum/mtime`
- `lookupCache`：
- `constName -> candidate definitions`
- `class+const -> definitions`
- `package+const -> definitions`
- `simpleClassName -> fqClassName set`

策略：

- LRU + TTL；
- 默认限制 `fileCache.maxFiles=500`、`lookupCache.maxKeys=4000`（可调）；
- 仅在会话真实访问后入缓存。

---

## 5. 关键流程改造

### 5.1 初始化

- 删除全量 `getAllDefinitions()` 加载路径；
- `initializeFullScan()` 保留“就绪标记”和“缓存命中复用”职责，不再构建全局内存定义索引。

### 5.2 文件分析（`analyzeFiles`）

对单文件：

1. 计算/复用 checksum；
2. 从会话缓存或 DB 读取该文件旧 definitions（仅该文件）；
3. 解析当前文件 definitions；
4. 计算 changed/removed keys；
5. 写回 DB；
6. 更新会话缓存（仅该文件及相关 lookup）。

### 5.3 引用解析（parseReferences）

改成“候选分批查询”模式：

1. 先在源码中提取可能的 const 名/owner/import 线索；
2. 通过 `ConstRefCacheDatabase` 批量查询候选 definitions；
3. 把候选集组装成“临时小索引”仅用于当前文件解析；
4. 解析完成后可写入会话 `lookupCache`。

说明：该模式避免长期持有全局 `ConstDefinitionIndex`，但不改变语义。

---

## 6. 数据库能力扩展（新增 API）

建议在 `ConstRefCacheDatabase` 新增批量查询接口：

- `getLatestDefinitionsByFile(filePath)`
- `queryDefinitionsByConstNames(Set<String>)`
- `queryDefinitionsByClassConstKeys(Set<Pair<String, String>>)`
- `queryDefinitionsByPackageConstKeys(Set<Pair<String, String>>)`
- `queryClassesBySimpleNames(Set<String>)`

要求：

- 全部查询走“latest version”口径；
- 支持 repo scope 过滤；
- 对大集合采用 chunk 分批，避免超长 SQL。

---

## 7. 开关与灰度

新增系统属性：

- `jugg.constref.lookup.mode=legacy|db_session`（默认 `legacy`，灰度后可切 `db_session`）
- 可选：
- `jugg.constref.session.file.cache.max`
- `jugg.constref.session.lookup.cache.max`
- `jugg.constref.session.cache.ttl.ms`

灰度策略：

1. 双模式并行日志对比（只对比不改变结果）；  
2. 对齐后切默认 `db_session`；  
3. 保留一键回退到 `legacy`。

---

## 8. 验收标准

- 内存：const-ref 常驻内存与仓库总文件数解耦，仅随会话热点增长；
- 正确性：现有 const-ref 测试全通过，新增“缓存淘汰后结果一致”用例；
- 性能：常见增量场景 P95 不明显劣化（目标 <10%）。

---

## 9. 风险与应对

- 风险：DB 查询次数上升导致延迟抖动。  
  应对：批量查询 + 会话 lookup cache + chunk 策略。

- 风险：缓存淘汰导致行为不一致。  
  应对：统一以 DB 最新口径回源，缓存仅优化不改变语义。

- 风险：回归周期长。  
  应对：引入 `lookup.mode` 开关做灰度与回滚。

---

## 10. 实施阶段建议

1. 增加 DB 批量查询 API 与会话缓存骨架。  
2. 改 `ConstRefEngine` 文件分析路径，移除全量加载依赖。  
3. 改引用解析为“候选查询 + 临时索引”。  
4. 增加双模式对比日志、测试与压测。  
5. 灰度切默认模式，保留回滚开关。
