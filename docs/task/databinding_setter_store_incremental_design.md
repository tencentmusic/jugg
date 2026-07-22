# DataBinding setter store 增量合并方案（方案 B）

## 1. 背景与结论

方案 A 已支持 layout 增量编译时复用最近一次 Gradle 完整构建生成的 module setter store，并加载全部 AAR setter store。但新增或修改 `@BindingAdapter` 后，后续 layout 仍需要看到本轮 processor 生成的新声明。

官方 DataBinding processor 的单次 invocation 已按以下顺序执行：

```text
ProcessMethodAdapters
  -> 扫描本轮 BindingAdapter 等声明
  -> 更新内存中的完整 BindingAdapterStore
  -> 输出 current-module setter store
ProcessExpressions
  -> 使用同一个内存 store 生成 BindingImpl / Mapper
ProcessBindable
```

因此 B1 不增加独立 setter-store processor，也不执行第二次 APT/KAPT：

> 复用 `DataBindingGenMapperCompiler` 已有的一次 APT/KAPT；processor 成功后，将其输出的 current-module setter store 合入 Jugg merged store，供下一轮编译复用。

DataBinding 逻辑全部收敛在 `SourceCompiler` 的 DataBinding 子流程，不暴露到 `JuggCompiler` 主流程。

## 2. 编译流程

```text
SourceCompiler
  -> SourceDataBindingProcessor
     -> layout 变化或当前 source 含 adapter declaration 时触发
     -> DataBindingGenMapperCompiler
        -> DataBindingClasspathHelper 提供上轮 merged store；不存在时提供 Gradle baseline
        -> 一次 Java APT / Kotlin KAPT
           -> 当前声明加入 processor 内存 store
           -> 同轮 layout 直接使用当前声明
           -> 输出 current-module setter store
        -> DataBindingSetterStoreCache.merge(baseline, currentModuleStore)
        -> 有 layout 时继续生成 incremental Mapper / BindingImpl / BR
        -> adapter-only 时只更新 store，随后进入普通源码编译
  -> Kotlin / Java / Dex compile
```

同轮新增 adapter 和 layout 不依赖已落盘 merged store，因为 `ProcessExpressions` 直接消费当前 invocation 的内存 store。落盘 merge 只为后续轮次服务。

## 3. merge 模型

### 3.1 输入

- Gradle baseline：最近一次完整构建生成的 module setter store。
- previous merged：baseline hash 相同时，上一轮 Jugg 发布的 merged store。
- current-module store：本轮官方 processor 输出，只包含本轮送入 processor 的声明。

### 3.2 合并步骤

```text
base = valid previous merged ?: Gradle baseline
currentTypes = declaring types from current-module store
remove currentTypes from base
merge current-module store into base
validate
atomic publish
```

用当前声明类型替换已有记录，可以覆盖同一个 adapter class 内的属性新增、属性修改、参数或方法描述变化，同时保留其他 Gradle baseline 和历史增量声明。

Jugg 不解析 Java/Kotlin 方法签名，不自行构造 setter store；所有新记录均来自对应版本的官方 processor。

## 4. 缓存模型

缓存按 `project + module + variant` 隔离，并通过 Gradle baseline SHA-256 校验：

```text
setter_store_cache/<module-key>/
├── current
└── generations/
    └── <generation-id>/
        ├── baseline.sha256
        └── merged/
            └── <module-package>-setter_store.json
```

`current` 是原子发布的 generation 指针。新 generation 完整写入并校验成功后才替换指针；失败时保留上一版有效 store。

B1 不保存 DataBinding compiler jar 指纹：processor 已停止维护，当前已验证 setter store schema 为 `version = 5`。如未来 processor 恢复演进，再将 compiler 指纹加入 cache identity。

## 5. JSON 处理范围

merge 和 declaring type 替换覆盖：

- `adapterMethods`
- `renamedMethods`
- `conversionMethods`
- `untaggableTypes`
- `multiValueAdapters`
- `inverseAdapters`
- `inverseMethods`
- `twoWayMethods`

规则：

- `version` 和 `useAndroidX` 必须兼容。
- 完全相同 key/value 允许去重。
- 相同完整 key 对应不同 value 时判定冲突，不猜测文件顺序。
- 未知 schema、缺少必要字段、冲突或回读失败时不发布新 generation。

## 6. 支持边界

B1 支持：

- 同轮新增 Kotlin `@BindingAdapter` 与使用它的 layout。
- 同轮新增 Java `@BindingAdapter` 与使用它的 layout。
- adapter-only 编译后，下一轮 layout 复用 merged store。
- 同一声明类型仍存在时，使用 current-module store 替换该类型的历史记录。
- Gradle baseline 与其他增量 adapter 记录保留。

B1 不处理删除语义：

- 删除 adapter source。
- 当前 source 移除全部 DataBinding adapter declaration。
- adapter declaring class 改名，导致旧 declaring type 无法由当前 store 确定。

这些场景不扩展 `FileChangesHandler`、`JuggManager`、`DeployFileManager` 或源码删除模型，保持与其他增量编译删除限制一致。

## 7. fallback 与失败策略

- `JuggCompileHelper` 只移除 BindingAdapter declaration 的前置 Gradle fallback，使请求能够进入 `SourceCompiler`。
- processor 失败、未生成预期 current-module store、baseline 缺失、JSON 不兼容或 merge 冲突时，沿现有增量失败链路处理。
- adapter declaration 未变化且 cache 无效时，允许直接使用 Gradle baseline。
- 不改变 `SourceCompiler` 现有 Mapper fail-open/fallback 行为，避免扩大本功能语义范围。

## 8. 分阶段范围

### B1：当前 module

- 单次 APT/KAPT 同时处理 adapter 与 Mapper。
- 维护当前 module merged store。
- Java、Kotlin、同轮使用和跨轮复用测试。

### B2：project library 传播

- library merged store 变化后通知下游 module。
- 下游重新处理受影响的 DataBinding layout。
- 增加 application/library 组合测试。

### B3：影响范围与性能

- 属性到 layout 的反向索引。
- 仅重编译使用受影响属性的 layout 和 `<include>` 传播链。

## 9. 测试要求

### L1

- current-module store 合入 baseline 并保留无关记录。
- 相同 declaring type 的新记录替换旧记录。
- 多轮 generated store 累积结果稳定。
- 冲突时上一版 merged store 不被覆盖。

### L2

- 同轮 Kotlin adapter + layout。
- adapter-only 后下一轮 layout 复用。
- Java adapter + layout。
- 新 adapter 与 Gradle baseline 原有 adapter 同时可用。
- `JuggCompileHelper` 不再前置 fallback。

### L3

- 真实 demo 中新增 adapter 与 layout，不执行 Gradle build 即可编译并部署。

## 10. 非目标

- 不修改 DataBinding processor。
- 不自行解析 adapter 方法语义。
- 不增加独立 setter-store compiler stage。
- 不改造源码删除事件链路。
- B1 不实现 project library 向 application 的传播。
