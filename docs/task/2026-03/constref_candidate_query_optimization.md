# ConstRef 候选查询笛卡尔积优化方案

## 1. 问题定义

`MainTabActivity.java`（JOOX 项目）单文件分析耗时 49s，其中 99.7% 时间消耗在 `queryCandidateDefinitionsForFile` 的 DB 查询。

### 根因

`ConstRefEngine.queryCandidateDefinitionsForFile`（行 922-943）在处理 `simpleClassNames × constNames` 时产生笛卡尔积：

```
hintsSimpleClassNames=105, hintsConstNames=500
→ 105 simpleClassNames → resolve 为 ~150 fqClassNames
→ 150 × 500 = 75,000 个 (fqClassName, constName) 对
→ 分 188 批（maxDefinitionKeysPerQuery=400）
→ 每批一次 WITH latest AS (ROW_NUMBER OVER ...) CTE 查询
→ 188 次重型 SQL ≈ 49s
```

但实际上，文件中 `Foo.BAR` 形式的访问，`Foo` 只需要和 `BAR` 配对查询，而不是和文件中所有 500 个常量名配对。

### 数据流追踪

1. `JavaConstParser.parseReferencesFromCu` 遍历 AST `FieldAccessExpr` 节点
2. 每个 `FieldAccessExpr`（如 `Foo.BAR`）调用 `resolveOwnerCandidates(ownerText="Foo", constName="BAR", ...)`
3. `resolveOwnerCandidates` 调用 `definitionIndex.findClassBySimpleName("Foo")`
4. 在 tracking 模式下，`findClassBySimpleName` 只记录 `simpleClassNames += "Foo"`，**丢失了与 `constName="BAR"` 的配对关系**
5. `queryCandidateDefinitionsForFile` 被迫用 `simpleClassNames × constNames` 全组合查 DB

---

## 2. 优化方案

### 优化 1：消除笛卡尔积 — 记录 (simpleName, constName) 配对

**核心思路**：在 `ConstReferenceLookupHints` 中新增 `simpleClassConstKeys: Set<Pair<String, String>>`，记录 AST 遍历时实际出现的 (simpleName, constName) 配对。

#### 影响面

| 类 | 改动 |
|---|---|
| `ConstReferenceLookupHints`（ConstRefModels.kt） | 新增 `simpleClassConstKeys` 字段 |
| `TrackingDefinitionLookup`（JavaConstParser + ConstRefAnalyzer） | 新增 `findClassBySimpleNameForConst(simpleName, constName)` 记录配对 |
| `ConstDefinitionLookup` | 新增 `findClassBySimpleNameForConst` 接口方法（默认委托 `findClassBySimpleName`） |
| `resolveOwnerCandidates` | 调用新方法传递 constName |
| `ConstRefEngine.queryCandidateDefinitionsForFile` | 用 `simpleClassConstKeys` 替代笛卡尔积 |

#### 效果估算

原始：105 simpleClassNames × 500 constNames = **75,000** 个 classConstKeys → **188** 批查询
优化后：实际出现的 (simpleName, constName) 配对数 ≈ **几百个**（一个 FieldAccessExpr 产生 1 个配对）→ 每个 simpleName 通过 DB 解析后产生 ~1-3 个 fqClassNames → **几百到千级** classConstKeys → **1-3** 批查询

**预期耗时从 49s → 0.5-2s（降低 96%+）**

### 优化 2：合并 DB 查询

`queryCandidateDefinitionsForFile` 当前分 4 步独立查询：
1. `resolveDefinitionsByConstNamesWithCache`（constNames）
2. `resolveDefinitionsByClassConstKeysWithCache`（hints.classConstKeys）
3. `resolveDefinitionsByPackageConstKeysWithCache`（packageConstKeys）
4. simpleClassNames 解析 + `resolveDefinitionsByClassConstKeysWithCache`（笛卡尔积）

优化 1 已大幅减少第 4 步的查询量。优化 2 进一步将步骤 2 和步骤 4 的 classConstKeys 合并为一次调用：

```
before: resolveDefinitionsByClassConstKeysWithCache(hints.classConstKeys) + resolveDefinitionsByClassConstKeysWithCache(笛卡尔积)
after:  resolveDefinitionsByClassConstKeysWithCache(hints.classConstKeys + resolvedSimpleClassConstKeys)
```

这减少了 DB 连接获取、CTE 构造、查询规划的重复开销。

---

## 3. 详细设计

### 3.1 ConstDefinitionLookup 接口

新增方法，默认实现委托已有方法：

```kotlin
interface ConstDefinitionLookup {
    // ... 现有方法 ...
    fun findClassBySimpleName(simpleName: String): Set<String>
    
    // NEW: 带 constName 上下文的 simpleName 解析
    fun findClassBySimpleNameForConst(simpleName: String, constName: String): Set<String> {
        return findClassBySimpleName(simpleName)
    }
}
```

### 3.2 ConstReferenceLookupHints

```kotlin
data class ConstReferenceLookupHints(
    val constNames: Set<String>,
    val classConstKeys: Set<Pair<String, String>>,
    val packageConstKeys: Set<Pair<String, String>>,
    val simpleClassNames: Set<String>,
    // NEW: (simpleName, constName) 配对，取代 simpleClassNames × constNames
    val simpleClassConstKeys: Set<Pair<String, String>> = emptySet(),
)
```

### 3.3 TrackingDefinitionLookup

在 `JavaConstParser` 和 `ConstRefAnalyzer` 中的两份 `TrackingDefinitionLookup` 中：

```kotlin
override fun findClassBySimpleNameForConst(simpleName: String, constName: String): Set<String> {
    val normalizedSimple = simpleName.trim()
    val normalizedConst = constName.trim()
    if (normalizedSimple.isNotEmpty()) simpleClassNames += normalizedSimple
    if (normalizedSimple.isNotEmpty() && normalizedConst.isNotEmpty()) {
        simpleClassConstKeys += normalizedSimple to normalizedConst
    }
    return emptySet()
}
```

### 3.4 resolveOwnerCandidates

```kotlin
// before:
candidates += definitionIndex.findClassBySimpleName(ownerText)

// after:
candidates += definitionIndex.findClassBySimpleNameForConst(ownerText, constName)
```

### 3.5 queryCandidateDefinitionsForFile

```kotlin
// before (行 922-943):
if (hints.simpleClassNames.isNotEmpty() && hints.constNames.isNotEmpty()) {
    val classNames = resolveClassesBySimpleNamesWithCache(...)
    classNames × hints.constNames → 笛卡尔积
}

// after:
if (hints.simpleClassConstKeys.isNotEmpty()) {
    val simpleNames = hints.simpleClassConstKeys.map { it.first }.toSet()
    val resolvedClassMap = resolveClassesBySimpleNamesWithCache(simpleNames, filePath)
    val classConstKeys = linkedSetOf<Pair<String, String>>()
    hints.simpleClassConstKeys.forEach { (simpleName, constName) ->
        resolvedClassMap[simpleName]?.forEach { fqClassName ->
            classConstKeys += fqClassName to constName
        }
    }
    // Merge with hints.classConstKeys for unified query
    val mergedClassConstKeys = classConstKeys - hints.classConstKeys.toSet()
    resolveDefinitionsByClassConstKeysWithCache(mergedClassConstKeys, filePath)
        .forEach { definition -> candidates[definition.uniqueDefinitionKey()] = definition }
} else if (hints.simpleClassNames.isNotEmpty() && hints.constNames.isNotEmpty()) {
    // Fallback: old behavior for callers that don't populate simpleClassConstKeys
    ...
}
```

---

## 4. 兼容性

- `simpleClassConstKeys` 默认空集，老路径（`parseReferencesByDbSessionMode`、`KotlinConstParser`）行为不变
- 接口新增方法有默认实现，不影响 `ConstDefinitionIndex` 等已有实现
- `simpleClassNames` 字段保留，用于 `resolveClassesBySimpleNamesWithCache` 的 fallback

## 5. 测试计划

1. 单测：构造含多个 FieldAccessExpr 的 Java 文件，验证 hints.simpleClassConstKeys 只包含实际配对
2. 单测：验证优化后 queryCandidateDefinitionsForFile 的 DB 查询次数远小于笛卡尔积
3. 已有测试回归：所有 ConstRefEngineTest 和 ConstRefCacheDatabaseTest 需通过
