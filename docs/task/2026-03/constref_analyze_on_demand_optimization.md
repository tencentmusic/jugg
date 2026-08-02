# analyzeOnDemand 耗时优化（目标 <1s）

> 创建时间：2026-03-16
> 背景：`MainTabActivity.java`（2595 行 / 245 import）触发 `analyzeOnDemand` 耗时 27002ms

---

## 一、根因回顾

本次 27s 由三段串行开销叠加：

| 阶段 | 原因 | 估算耗时 |
|---|---|---|
| Phase 1 Java AST parse | JavaParser 解析 2595 行 definition | ~2–4s |
| Phase 2 Java AST parse（第2次）| `collectHintsAndParseReferences` Java 路径走了两次完整 parse | ~2–4s |
| DB 查询（主因）| `queryClassesBySimpleNames` 对 176MB DB 全表扫描，应用层过滤 205 个 simpleClassNames | **~20s** |

---

## 二、优化方案 A+B+C 详情

### 方案 A：Java single-pass parse（消去双 parse）

**文件：** `ConstRefAnalyzer.kt`、`JavaConstParser.kt`

**现状问题：**
`collectHintsAndParseReferences` 对 Java 文件走两次独立的 JavaParser parse：
```kotlin
// ConstRefAnalyzer.kt collectHintsAndParseReferences
"java" -> {
    val hints = collectReferenceLookupHints(sourceFile)        // parse #1（TrackingLookup）
    val definitionIndex = definitionIndexFactory(hints) ?: return emptyList()
    javaConstParser.parseReferences(sourceFile, definitionIndex) // parse #2（真实 lookup）
}
```
Kotlin 路径只 parse 一次（复用同一 KtFile），Java 路径没有对齐。

**修改方案：**
1. `JavaConstParser` 新增 `collectHintsAndParseReferences(file, definitionIndexFactory)` 方法：
   - 第一步：`parse(file)` 得到 `CompilationUnit`（唯一一次）
   - 第二步：用 `TrackingDefinitionLookup` 遍历 AST 收集 hints
   - 第三步：调 `definitionIndexFactory(hints)` 查 DB 得到 definitionIndex
   - 第四步：用真实 definitionIndex 再遍历同一 `CompilationUnit` 输出 references
2. `ConstRefAnalyzer.collectHintsAndParseReferences` Java 分支改为调新方法

**预期收益：** 节省 1 次 JavaParser 完整 parse，约 **2–4s**

---

### 方案 B：`queryClassesBySimpleNames` 加列 + 索引（解决全表扫描）

**文件：** `ConstRefCacheDatabase.kt`

**现状问题：**
`const_definitions` 表没有 `simple_class_name` 列，`queryClassesBySimpleNames` 实现为：
```sql
-- 全量读取 const_definitions JOIN file_analysis_head（176MB），应用层过滤
SELECT d.repo_key, d.relative_path, d.package_name, d.fq_class_name, ...
FROM const_definitions d INNER JOIN latest l ...
-- 无 WHERE 过滤 simpleNames，全部加载到内存再 registerSimpleNameMappings 过滤
```

**修改方案：**
1. `const_definitions` 表新增列 `simple_class_name TEXT NOT NULL DEFAULT ''`
   - 写入时从 `fq_class_name` 提取（取 `.` 最后一段，嵌套类取外类名）
   - 提取逻辑：`fqClassName.removePrefix("$packageName.").substringBefore('.')`
2. 新增索引：
   ```sql
   CREATE INDEX idx_const_def_repo_simple_name
       ON const_definitions(repo_key, simple_class_name, const_name);
   ```
3. `queryClassesBySimpleNames` 改为带 `WHERE d.simple_class_name IN (?,?,...)`，分 chunk 点查
4. `schema_version` bump 到 4，触发 DB 自动重建（无需 migration，未发布）
5. 同步更新所有 `upsertBatchDefinitions`/`upsertBatchAnalysis` 写入逻辑，填写 `simple_class_name`

**simple_class_name 提取规则：**
```
fqClassName = "com.example.OuterClass"       → "OuterClass"
fqClassName = "com.example.OuterClass.Inner" → "OuterClass"  (取外类，与 Java import 对齐)
fqClassName = "com.example.OuterClass$Inner" → "OuterClass"  (也处理 $ 分隔)
package = ""，fqClassName = "TopLevel"        → "TopLevel"
```
注意：Java `import com.example.OuterClass` 时 simpleName 是 `OuterClass`，`Inner` 是通过 `OuterClass.Inner` 访问的，所以应该对 `OuterClass` 建索引而不是 `Inner`。

**预期收益：** 全表扫描 → 索引点查，约 **15–20s**

---

### 方案 C：WAL checkpoint（减少 DB 读放大）

**文件：** `ConstRefCacheDatabase.kt`

**现状问题：**
日志显示 `const_ref_shared.db`（176MB）+ WAL（23MB）未合并，每次读操作需在内存合并 WAL。

**修改方案：**
在 `init()` 建立连接后（或 cleanup 流程中）追加 WAL checkpoint 调用：
```kotlin
connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(PASSIVE)") }
```
- `PASSIVE` 模式不阻塞 writer，安全
- 建议在 `scheduleCacheCleanup` 的 cleanup 任务中附带执行（已有异步 cleanup 入口）

**预期收益：** 所有 DB 操作提速约 **20–30%**

---

## 三、Mock 测试（开发入口）

### 测试位置
`idea/src/test/java/local/idea/LocalTest.kt`，新增 `testConstRefAnalyzeOnDemandPerf` 方法

### 测试目标
验证对 `MainTabActivity.java` 调用 `analyzeOnDemand` 的全流程耗时 **<1000ms**（首次 crcMiss 分析完成后，第二次 mtime/analysisReuse 命中应 <10ms）

### 测试代码结构
```kotlin
@Test
fun testConstRefAnalyzeOnDemandPerf() {
    val projectDir = File("/Users/wormchen/IdeaProjects/joox/JOOX_Android")
    TestGlobal.init()
    val jugg = MockJugg(projectDir)
    jugg.loadFromHistory()

    val targetFile = File("/Users/wormchen/IdeaProjects/joox/JOOX_Android/wemusic/src/com/tencent/wemusic/ui/main/activity/MainTabActivity.java")

    // Round 1: may hit crcMiss path; after optimization should still be <1000ms
    val cost1 = measureTimeMillis {
        jugg.deployFileManager.awaitConstRefAnalysis(listOf(targetFile.absolutePath))
    }
    println("analyzeOnDemand round1 cost: ${cost1}ms")
    assertTrue(cost1 < 1_000, "Round 1 analyzeOnDemand should finish in <1000ms, actual: ${cost1}ms")

    // Round 2: must hit mtime/analysisReuse cache, should be near-instant
    val cost2 = measureTimeMillis {
        jugg.deployFileManager.awaitConstRefAnalysis(listOf(targetFile.absolutePath))
    }
    println("analyzeOnDemand round2 cost: ${cost2}ms")
    assertTrue(cost2 < 50, "Round 2 analyzeOnDemand should hit cache in <50ms, actual: ${cost2}ms")
}
```

### 测试用于验证各方案的方式
- **优化前**：round1 ~27000ms，用于建立 baseline（先注释掉 assertTrue 跑一次打印耗时）
- **方案 A 完成后**：round1 应从 ~27s 降至 ~23s（双 parse → 单 parse）
- **方案 B 完成后**：round1 应从 ~23s 降至 **<1s**（全表扫描 → 索引点查）
- **方案 C 完成后**：在 B 基础上再微降

---

## 四、开发步骤

### Step 1：搭建性能基线测试
- [ ] 在 `LocalTest.kt` 中新增 `testConstRefAnalyzeOnDemandPerf`（先不加 assertTrue，只打印耗时）
- [ ] 运行确认 baseline：round1 ~27000ms，round2 <10ms
- [ ] 打开 assertTrue，作为后续所有方案的回归门禁

### Step 2：实施方案 C（WAL checkpoint，最简单，先做）
- [ ] `ConstRefCacheDatabase.init()` 或 cleanup 任务中加 `PRAGMA wal_checkpoint(PASSIVE)`
- [ ] 验证对现有 unit tests 无影响
- [ ] 运行 `testConstRefAnalyzeOnDemandPerf` 观察效果

### Step 3：实施方案 A（Java single-pass parse）
- [ ] `JavaConstParser` 新增 `collectHintsAndParseReferences(file, definitionIndexFactory)`
  - 内部 parse 一次，两遍 AST 遍历共用同一 `CompilationUnit`
- [ ] `ConstRefAnalyzer.collectHintsAndParseReferences` Java 分支改为调新方法
- [ ] 运行 `./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.constref.*"` 回归
- [ ] 运行 `testConstRefAnalyzeOnDemandPerf` 确认耗时下降

### Step 4：实施方案 B（simple_class_name 列 + 索引）—— 核心

#### 4.1 DB Schema 改动
- [ ] `const_definitions` 加列 `simple_class_name TEXT NOT NULL DEFAULT ''`
- [ ] 新增索引 `idx_const_def_repo_simple_name ON const_definitions(repo_key, simple_class_name, const_name)`
- [ ] `schema_version` bump 至 4

#### 4.2 写入侧
- [ ] 新增工具函数 `extractSimpleClassName(packageName: String, fqClassName: String): String`
  ```
  输入："com.example" / "com.example.Outer.Inner" → 输出："Outer"
  输入："com.example" / "com.example.Simple"       → 输出："Simple"
  输入："" / "TopLevel"                            → 输出："TopLevel"
  ```
- [ ] `upsertBatchDefinitions` / `upsertBatchAnalysis` 写入时填入 `simple_class_name`
- [ ] 对应 `INSERT ... ON CONFLICT` 语句加入新列

#### 4.3 查询侧
- [ ] `queryClassesBySimpleNames` 改为：
  ```sql
  SELECT d.repo_key, d.relative_path, d.package_name, d.fq_class_name, ...
  FROM const_definitions d INNER JOIN latest l ...
  WHERE d.simple_class_name IN (?, ?, ...)    -- 走索引
  ```
  分 chunk（沿用 `maxDefinitionKeysPerQuery=400`）

#### 4.4 测试回归
- [ ] `ConstRefCacheDatabaseTest.kt` 补充 `simple_class_name` 相关用例
- [ ] 运行 `./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.constref.*"`
- [ ] 运行 `testConstRefAnalyzeOnDemandPerf` 确认 round1 **<1000ms**

### Step 5：整体回归 & 更新文档
- [ ] 全量 constref 测试：`./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.constref.*"`
- [ ] 运行 `testConstRefAnalyzeOnDemandPerf` 最终验证
- [ ] 更新 `docs/ai_knowledge/03_deploy_const_ref.md`

---

## 五、风险点

| 风险 | 说明 | 缓解 |
|---|---|---|
| `simple_class_name` 提取逻辑与 `registerSimpleNameMappings` 不一致 | DB 侧提取和查询侧使用的 simpleName 必须与 `TrackingDefinitionLookup.hasSimpleClassName` 收集逻辑完全一致，否则索引命中为空，降级为漏报 | 写单元测试验证几种典型 fqClassName 的提取结果与现有 `registerSimpleNameMappings` 一致 |
| DB 重建导致冷启动 full scan | schema v4 重建，JOOX 工程 ~10245 文件 full scan 约 11s（大部分 reuse） | 可接受，重建只发生一次 |
| Java `CompilationUnit` 非线程安全 | JavaParser AST 节点是 mutable，两遍遍历在同一线程内串行执行，无并发问题 | 确认两遍遍历均在同一调用栈中 |
