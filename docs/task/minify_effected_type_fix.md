# Minify EffectedType 修复方案

## 背景

`checkMaybeMinifiedRemoveClass` 检测到的被 R8 移除的类/成员目前标记为 `SOURCE`，但这些类只需要字节码级别补全（`_jugg_fix`），不需要源码重编译。同时 `merge()` 函数会将 `SOURCE` 强制覆盖为 `INLINE_IMPL_CHANGE`，导致需要源码重编译的类被降级。

## 修改点

### 1. `EffectedClassNode.EffectedType` 新增 `MINIFY_MEMBER_REMOVED`

- 文件: `EffectedClassNode.kt`
- 新增枚举值 `MINIFY_MEMBER_REMOVED`，表示 R8 minify 移除了类或成员
- 新增扩展属性 `minifyMemberRemoved`

### 2. `checkMaybeMinifiedRemoveClass` Check 1/2 使用新类型

- 文件: `DeployDataDatabaseSqLiteHelper.kt`
- L1125 和 L1182 的 `EffectedType.SOURCE` → `EffectedType.MINIFY_MEMBER_REMOVED`

### 3. `merge()` 保留更高优先级类型

- 文件: `DeployDataGenerator.kt` L252-267
- 当 `existing.effectedType == SOURCE` 时保留 `SOURCE`，否则设为 `INLINE_IMPL_CHANGE`

### 4. `CompileEffectAnalyzer.getMinifyInfo` 过滤条件调整

- 文件: `CompileEffectAnalyzer.kt` L125-126
- 从 `== INLINE_IMPL_CHANGE` 改为 `!= SOURCE`（即选中 `INLINE_IMPL_CHANGE` 和 `MINIFY_MEMBER_REMOVED`）

### 5. `CompileEffectAnalyzer.getRecompileFiles` 中变量名更新

- 文件: `CompileEffectAnalyzer.kt` L125
- 变量名 `inlineEffectedNodes` 重命名为 `minifyEffectedNodes` 以准确反映语义

## 影响范围

| 下游分支 | 影响 |
|---------|------|
| `getRecompileFiles` L76 `.sources` 过滤 | MINIFY_MEMBER_REMOVED 不再被选中 → 期望行为 |
| `getMinifyInfo` L125 过滤 | MINIFY_MEMBER_REMOVED 被选中 → 期望行为 |
| `merge()` | SOURCE 不再被覆盖 → 修复降级问题 |
| `IncrementalCompilerHelper` L132-143 | 不依赖 effectedType → 无影响 |
| `DeployDataDatabase.getEffectedSourceAndClass` L311-314 | oldNode?.copy 保留 SOURCE → 无影响 |

## 执行状态

- [x] TDD 失败测试编写完成
  - `EffectedClassNodeTypeTest.kt` — 扩展属性 `sources`/`inlineImplChanges`/`minifyMemberRemoved` 过滤验证
  - `DeployDataGeneratorMergeTest.kt` — merge 类型优先级验证（SOURCE 不被降级）
  - `DeployDataGeneratorReleaseTest.kt#testMinifyRemovedClassNodesHaveMinifyMemberRemovedType` — 端到端验证 minify 节点类型
- [x] 业务代码实现
- [x] 测试全部通过
- [x] ai_knowledge 文档同步
  - `03_deploy_data_generator.md` — 新增 §5.4(EffectedType 说明) §5.5(inline) §5.6(minify 移除) §5.7(merge 优先级)
  - `02_compile_manifest_obfuscation.md` — 增加 minify 移除成员问题定位条目
  - `99_index.md` — 增加 EffectedType/merge 优先级任务路由行
