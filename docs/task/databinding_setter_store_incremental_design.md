# DataBinding setter store 完整增量方案（方案 B）

## 1. 背景与当前结论

方案 A 已支持在 layout 增量编译时复用最近一次 Gradle 完整构建产生的模块 setter store，并同时加载全部 AAR 中的 setter store。该方案可以覆盖“自定义 `BindingAdapter` 已存在，只修改或新增使用它的 layout”的场景。

方案 A 不更新模块 setter store，因此以下场景仍需要完整增量能力：

- 新增、删除或修改 `@BindingAdapter` 方法。
- 修改 `@BindingMethods`、`@BindingConversion` 等会改变 DataBinding setter 解析结果的声明。
- 修改 adapter 的参数类型、属性名、命名空间或多属性组合。
- adapter 所在 library module 变化后，下游 module 的 layout 立即引用新声明。

方案 B 的核心结论是：**不对 `setter_store.json` 做字段级增量合并，而是按模块重新生成一份完整 store，并在成功后原子替换缓存。**

## 2. 为什么不能直接补丁 JSON

DataBinding annotation processor 当前轮输出只表达本轮识别到的声明，不等价于模块完整状态。直接把新 JSON 合入旧 JSON 会留下这些问题：

- 删除或重命名 adapter 后，旧记录无法可靠移除。
- 方法签名、属性组合和优先级变化时，旧记录可能与新记录同时存在。
- 多文件声明同名属性时，需要遵循官方 processor 的冲突与覆盖规则。
- JSON 属于 AGP/DataBinding 内部协议，跨版本字段和语义可能变化。

因此 Jugg 应把 JSON 当作不透明产物，让对应 AGP 版本的官方 processor 生成和读取。

## 3. 目标数据模型

缓存按 `project + module + variant + AGP/DataBinding version` 隔离，至少维护：

1. `adapter source index`
   - 记录模块内可能声明 DataBinding adapter 的 Java/Kotlin 源文件。
   - 记录文件指纹，用于识别新增、修改和删除。
   - 索引只负责确定“哪些源需要参与完整 store 重建”，不自行解释 setter store JSON。

2. `module setter store`
   - 由官方 DataBinding processor 对该模块全部 adapter declaration sources 生成。
   - 只有生成成功才替换上一版缓存。

3. `dependency setter stores`
   - 外部 AAR 继续直接读取 transform 产物。
   - project library module 优先读取该模块最新的 Jugg store；没有时读取 Gradle 基线 store。

4. `attribute impact index`
   - 记录 layout 使用的绑定属性，用于 adapter 变化后定位受影响 layout。
   - 第一阶段可以保守地重编译下游模块全部 DataBinding layout，后续再缩小到属性级影响范围。

## 4. 编译流程

### 4.1 初始化

首次启用或缓存失效时：

1. 扫描模块 Java/Kotlin source roots，建立 adapter source index。
2. 以全部 adapter declaration sources 运行官方 Java APT/Kotlin KAPT。
3. 生成模块完整 setter store 到临时目录。
4. 校验 processor 成功且目标 store 存在后，原子替换模块缓存。
5. Mapper APT 使用新缓存、project dependency stores 和 AAR stores。

初始化扫描只筛选含 DataBinding adapter 注解的候选源码，避免把模块全部业务源码长期加入 processor 输入。

### 4.2 普通源码或 layout 变化

- 未涉及 adapter declaration source：直接复用模块缓存，行为等同方案 A。
- layout 变化：根据现有 DataBinding layout info 进入 Mapper APT，不重建 store。

### 4.3 adapter 声明变化

1. 根据 ChangedFile 更新 adapter source index；删除文件必须从索引移除。
2. 使用更新后的**全部 adapter declaration sources**重建模块完整 store。
3. Java 声明走 APT，Kotlin 声明走 KAPT；混合模块按官方 DataBinding processor 可见性要求准备双方 classpath。
4. store 生成失败时保留旧缓存，但本次增量编译必须失败或触发 Gradle fallback，不能继续部署旧语义。
5. store 成功后原子替换缓存，并重编译受影响的 DataBinding layout/mapper。
6. library module 的 store 变化时，向依赖它的 application/library modules 传播失效。

## 5. 一致性与失效规则

以下任一条件变化时，丢弃对应模块的 Jugg store 并重新初始化：

- build variant、namespace/package、AGP/DataBinding compiler 版本变化。
- source roots 或 project module dependency graph 变化。
- 最近一次 Gradle 基线 store 的路径、时间戳或内容指纹变化。
- Jugg 缓存格式版本变化。
- 完整 Gradle clean 后基线产物消失或重新生成。

写缓存使用临时目录加原子 rename。重建过程中不覆盖可用旧缓存，防止进程中断留下半份 JSON。

## 6. 失败与降级策略

- 无法识别的 Kotlin 注解形态、processor 崩溃、store 缺失或依赖 classpath 不完整：本轮停止增量部署并提示执行 Gradle 构建。
- Jugg store 不可用但 adapter 声明本轮未变化：允许回退到当前 variant 的 Gradle 基线 store。
- adapter 声明本轮已变化：禁止静默使用旧 Gradle/Jugg store，否则 layout 可能编译成功但运行语义错误。
- 外部 AAR store 缺失时保持官方 processor 的报错，不由 Jugg 猜测 adapter 签名。

## 7. 分阶段落地建议

### B1：安全重建

- 建立 adapter source index。
- adapter 源变化时重建当前模块完整 store。
- 下游影响先按模块全量 DataBinding layout 处理。
- 失败时明确回退 Gradle，不做旧 store 静默部署。

### B2：影响范围收窄

- 建立属性到 layout 的反向索引。
- 仅重新处理使用受影响属性的 layout，以及 `<include>` 传播链。
- 增加 project library module store 变化的精确下游传播。

### B3：版本兼容与性能

- 覆盖 AGP 7.x、8.x、9.x 的 processor 参数与中间产物差异。
- 对无 adapter 变化的源码编译跳过索引扫描和 store 重建。
- 增加缓存命中率、重建耗时和 fallback 原因日志。

## 8. 测试要求

- L1：adapter source index 的新增、修改、删除、重命名和缓存失效规则。
- L2：Java/Kotlin/mixed adapter declarations 生成完整 store；失败时旧缓存不被覆盖。
- L3：
  - 新增 boolean `android:visibility` adapter 后，无 Gradle 构建即可编译并部署使用它的 layout。
  - 修改 adapter 参数类型后，旧签名不再可用，新签名可用。
  - 删除 adapter 后，引用它的 layout 必须明确编译失败。
  - library module adapter 变化能触发 application module mapper/layout 更新。

## 9. 方案边界

方案 B 解决的是 adapter 声明自身的增量更新。方案 A 仍作为基线与降级能力保留：普通 layout 修改优先复用现有完整 store，只有 adapter declaration source 变化才触发模块 store 重建。
