# 增量 D8 minApi 与 core-library desugar 导致 descriptor 漂移

> 关联 Issue：[tencentmusic/jugg#26](https://github.com/tencentmusic/jugg/issues/26)  
> 修复提交：`ece0338cf`（amend 后 hash 可能变化）  
> 最小复现工程：`/Users/wormchen/IdeaProjects/demo/jugg-issue-26-minapi-desugar`

---

## 1. 背景与现象

用户现场（Issue #26 上报链路之一）：

- `minSdk=29`
- 启用 Java/Kotlin desugar 与 `coreLibraryDesugaring`（`desugar_jdk_libs`）
- Jugg 增量编译部署后，启动阶段出现异常（现场整理：大量 overlay dex → Binder 分配失败 → `DeadSystemException`）

调查报告确认的高置信链路：

```text
基线 Gradle APK（minSdk=29）LocalDate 保持 Ljava/time/LocalDate
  → Jugg 因 isEnableDesugared 将增量 D8 minApi 降为 21
  → 同时传入 desugar.json，命中 api_level_below_or_equal:25 的 java.time rewrite
  → 增量 DEX 出现 Lj$/time/LocalDate
  → ClassNodeComparator 误判结构变化 → 依赖传播 → 大量 file-per-class overlay dex
```

Issue #26 标题描述的是 `dispatchActivityCreated` 未执行；本次修复针对上述 **DEX descriptor 与基线不一致** 的编译链根因。运行时症状与 overlay dex 规模的因果关系仍需原始 system_server / logcat 证据补强，但 descriptor 漂移本身已可由源码与 D8 行为稳定复现。

---

## 2. 讨论与决策记录

### 2.1 删除 `isEnableDesugared && applicationMinApi >= 26 → 21`

| 项 | 内容 |
|---|---|
| **原意** | 基线 APK 存在 desugar 痕迹（`$-CC` / `$DefaultImpls`），而 app `minSdk>=26` 时 Gradle 不做语言级脱糖，故强制 `minApi=21`「打开脱糖」 |
| **问题** | `minApi=21` 与 `desugar.json` 组合会错误触发 `java.time → j$.time` rewrite，与 Gradle 基线（`minApi=29`）产物不一致 |
| **决策** | **删除该分支** |
| **理由** | default-interface 兼容已由 `getDesugarInfo()` 向 D8 临时 classpath 补齐 `$-CC` 类，不依赖降低 `minApi`；Gradle 也不会因基线有 desugar 痕迹而改写 variant `minSdk` |
| **残余风险** | 极低：若 classpath 补齐遗漏且基线语言脱糖形态特殊，可能 `AbstractMethodError`；应修补齐链而非降 `minApi` |

### 2.2 minApi 解析：使用归属 APK owner variant 的 minSdk

| 项 | 内容 |
|---|---|
| **候选** | A) 当前编译 module.minSdk；B) applicationModule.minSdk；C) `resolveApkOwnerModule(module).minSdk` |
| **Gradle 行为** | D8 `--min-api` = **当前产出 APK 的 variant minSdk**；library 打进 base APK 时由 **app variant minSdk** dex，不是 library 自己的 minSdk |
| **决策** | **采用 C**：`resolveApkOwnerModule` → owner.minSdk → applicationModule.minSdk → fallback `21` |
| **理由** | 与 Gradle dex 语义一致；避免 library `minSdk=21` + app `minSdk=29` 时误用 21 |
| **实现** | `ICompileContext.resolveApkOwnerModule()` + `getDexMinApi()`；`BaseCompileContext` 删除重复私有方法 |

### 2.3 `isEnableDesugared` 不再参与 minApi 决策

| 项 | 内容 |
|---|---|
| **标志语义** | APK DB 中是否观察到 `$-CC` / `$DefaultImpls`（启发式，非 variant minSdk） |
| **决策** | **minApi 计算不再读取该标志** |
| **理由** | 标志不能表达「当前 variant 应对哪些 API 做 core-library rewrite」；误用会导致 minApi 与基线 Gradle 构建分叉 |
| **保留用途** | APK 解析写 DB；default-interface / core-library 具体策略仍由 `getDesugarInfo()` 按变更 class 与基线 APK 内容驱动 |

### 2.4 minSdk 不可读时 fallback 21

| 项 | 内容 |
|---|---|
| **决策** | fallback **`21`**（与 `DexMinifyCompiler` 一致） |
| **理由** | 21 是 desugar 常见下界；比旧逻辑「无 desugar 用 31」更保守，避免误跳过必要脱糖 |

### 2.5 明确不采用的缓解方案

- 合并 overlay dex / 限制 dex 数量
- `ClassNodeComparator` 将 `java.time` 与 `j$.time` 视为等价
- 关闭 `coreLibraryDesugaring` 配置传递

以上只缓解后果，不消除 descriptor 漂移根因。

---

## 3. 实现摘要

| 文件 | 变更 |
|------|------|
| `ICompiler.kt` | 新增 `resolveApkOwnerModule()`、`getDexMinApi()` |
| `DexCompiler.kt` | 使用 `getDexMinApi(module)`，删除 `isEnableDesugared` 相关 `when` |
| `BaseCompileContext.kt` | 复用 `resolveApkOwnerModule`，删除重复 `findRelativeApkModule` |
| `02_compile_source.md` | §4.2 更新 D8 minApi 决策描述 |
| `DexMinApiTest.kt` | L1：minApi 解析 + LocalDate descriptor 矩阵 |

核心约束：

```text
增量 D8 minApi = 产生当前基线 APK 的 owner variant minSdk
core-library desugar 配置继续传入 D8，由真实 minApi + desugar.json 共同决定 rewrite 范围
```

---

## 4. 验证

### 4.1 自动化（仓库内）

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.source.DexMinApiTest"
```

覆盖：

- library `minSdk=21`、app `minSdk=29` → `getDexMinApi(library)=29`
- `minApi=29` + `desugar.json` → `Ljava/time/LocalDate`
- `minApi=21` + `desugar.json` → `Lj$/time/LocalDate`（低版本路径不退化）

### 4.2 最小复现工程（仓库外）

路径：`/Users/wormchen/IdeaProjects/demo/jugg-issue-26-minapi-desugar`

配置对齐用户现场：`minSdk=29`、`coreLibraryDesugaring`、`desugar_jdk_libs:1.1.5`、AGP 7.4.2。

```bash
cd /Users/wormchen/IdeaProjects/demo/jugg-issue-26-minapi-desugar
./gradlew :app:assembleDebug
./verify-descriptors.sh
```

`verify-descriptors.sh` 断言（2026-09-03 本机执行通过）：

1. Gradle 基线 APK 中 `DateProvider.currentDate()` 为 `()Ljava/time/LocalDate;`
2. 手动 D8 `minApi=21` + `desugar_jdk_libs_configuration:1.1.5` 产出 `()Lj$/time/LocalDate;`（修复前 Jugg 错误路径）
3. 手动 D8 `minApi=29` + 同版 desugar 配置产出 `()Ljava/time/LocalDate;`（修复后 Jugg 应对齐路径）

```text
== 1. Gradle baseline APK (minSdk=29 + coreLibraryDesugaring) ==
DateProvider.currentDate()Ljava/time/LocalDate;
== 2. Manual D8 minApi=21 (buggy Jugg path before fix) ==
DateProvider.currentDate()Lj$/time/LocalDate;
== 3. Manual D8 minApi=29 (fixed Jugg path) ==
DateProvider.currentDate()Ljava/time/LocalDate;
PASS
```

---

## 5. 完成标准

- [x] `minSdk=29` 增量 D8 使用 `minApi=29`
- [x] `coreLibraryDesugaring` 配置仍传给 D8
- [x] 不产生 `java.time/j$.time` 漂移导致的伪结构变化
- [x] `minSdk<=25` 时 `LocalDate` 仍可改写为 `j$.time`
- [x] 定向测试与最小 demo 验证通过
- [ ] 用户现场 Jugg 增量部署回归（待用户确认）

---

## 6. 证据边界（未在本任务闭合）

- 约 65 个 overlay dex 是否全部由 descriptor 传播产生
- Binder 689KB 分配失败与 `DeadSystemException` 的因果（需 system_server / 完整 logcat）
- Issue #26 中 `dispatchActivityCreated` 未执行是否由本次 descriptor 漂移直接导致
