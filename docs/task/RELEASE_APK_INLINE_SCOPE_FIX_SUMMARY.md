# Release APK 编译范围膨胀治理方案总结

> 日期：2026-02-21  
> 范围：最近 3 个提交 `b0654df4e`、`ac03afe2c`、`e81cf1297`

## 1. 问题与目标

### 1.1 问题根因

Release 构建开启 R8 后，方法可能被内联到调用方类。  
当被内联的方法实现变更时，传统增量路径会把大量“调用方类”标记为需要重编译，导致编译范围级联膨胀。

### 1.2 目标

将“受内联影响类”的处理从“级联重编译”改为“定向重定向”，把编译范围收敛到固定规模。

## 2. 三个提交的演进

### 2.1 `b0654df4e`（change plan）

主要完成方案骨架和通路打通：

1. 引入 `MinifyInfo` 数据结构，承载内联影响信息。  
2. `ICompileContext` 新增 `getMinifyInfo()`，`BaseCompileContext` 实现该接口。  
3. `EffectedType.CLASS` 重命名为 `EffectedType.INLINE_IMPL_CHANGE`，语义从“类级影响”明确为“内联实现变更影响”。  
4. `DeployFileManager.getRecompileFiles()` 对 `INLINE_IMPL_CHANGE` 改为 `emptyList()`，停止把该类问题走“源码级联重编译”路径。  
5. `DexObfuscator` 增加 `obfuscateWithInlineRedirect()` 与重定向逻辑雏形。  
6. 测试侧引入固定 mapping（`-applymapping mapping-fixed.txt`）保证 Release 场景可复现。

### 2.2 `ac03afe2c`（clean code）

主要做 Phase 1 清理与边界收敛：

1. `MinifyInfo` 临时收敛为“检测与日志”最小字段。  
2. `DeployFileManager.getMinifyInfo()` 去掉未启用字段（如 `classFiles`/`inlineMappings` 的早期实现）。  
3. 注释与类型语义统一，强调当时阶段是“先检测，后重定向”。

### 2.3 `e81cf1297`（phase 2）

主要完成最终可用的 Phase 2：

1. `MinifyInfo` 恢复并固化 `classFiles`，用于 `_jugg_fix` 生成。  
2. `DeployFileManager.getMinifyInfo()` 增加 `.class` 文件收集逻辑。  
3. `DexMinifyCompiler` 新增 `generateJuggFixClasses()`：  
   - 用 ASM 将类名重命名为 `*_jugg_fix`；  
   - 用 D8 `--file-per-class` 产出 DEX；  
   - 将生成 dex 作为新增编译产物并入部署。  
4. `DexObfuscator` 的重定向映射改为基于 ASM 签名格式，`remapType()` 中“先重定向，再混淆映射”。  
5. 新增 `DexMinifyCompilerPhase2Test`，并增强 `SimpleCompileContext` 以注入测试 `MinifyInfo`。

## 3. 最终方案（当前代码行为）

### 3.1 识别阶段

`InlineMethodDetector` 把受 R8 内联影响的类标记为 `INLINE_IMPL_CHANGE`，并进入部署影响集。

### 3.2 编译范围收敛阶段

`DeployFileManager.getRecompileFiles()` 对 `INLINE_IMPL_CHANGE` 不再下发“缺失 class 反查重编译”，避免范围继续扩散。

### 3.3 定向修复阶段

`DexMinifyCompiler` 从 `context.getMinifyInfo()` 读取受影响类和对应 `.class` 文件，生成 `_jugg_fix` 副本 dex。

### 3.4 调用重定向阶段

`DexObfuscator.obfuscateWithInlineRedirect()` 在 DEX remap 时优先使用 redirect map：

1. 原类型 `Lcom/foo/Bar;`  
2. 定向到 `Lcom/foo/Bar_jugg_fix;`  
3. 若无定向项，再走普通 mapping 混淆映射。

## 4. 关键实现位置

1. `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexMinifyCompiler.kt`  
2. `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexObfuscator.kt`  
3. `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/MinifyInfo.kt`  
4. `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt`  
5. `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/EffectedClassNode.kt`  
6. `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/InlineMethodDetector.kt`  
7. `main/src/main/java/com/sickworm/intellij/jugg/project/BaseCompileContext.kt`  
8. `main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt`

## 5. 测试与稳定性支撑

1. `main/src/test/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexMinifyCompilerPhase2Test.kt` 覆盖 `_jugg_fix` 生成链路。  
2. `main/src/test/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGeneratorReleaseTest.kt` 适配新影响类型。  
3. `android_demo_project/app/proguard-rules.pro` 通过 `-applymapping mapping-fixed.txt` 固化混淆输出，降低 Release 测试波动。
4. `android_demo_project/app/mapping-fixed.txt.README.md` 说明了 mapping 固化与更新流程。

## 6. 方案收益

1. 从“受影响调用方级联重编译”转为“受影响类重定向修复”，明显抑制编译范围膨胀。  
2. 发布包混淆场景可复现性提升（固定 mapping）。  
3. 架构分层清晰：检测在 deploy/data，修复在 compiler/obfuscation。

## 7. 已知限制与后续建议

1. 当前重定向粒度是“类级别”，不是“方法级别”。  
2. `_jugg_fix` 主要覆盖“实现变更”路径，结构性变更仍需按现有 hot_fix/hot_reload 规则处理。  
3. `.class` 文件收集仍依赖路径匹配，复杂构建输出布局下建议补充更稳健的符号索引。  
4. 建议后续补一个端到端基准：对比相同变更下“修复前后”的重编译类数量与耗时。

## 8. 文档一致性说明

在本次核对中，`docs/ai_knowledge/03_deploy_data_generator.md` 部分示例仍含旧枚举命名（如 `INLINE` 等历史表述）；此类冲突以代码为准，当前代码主语义为 `INLINE_IMPL_CHANGE`。
