# DataBinding setter store 增量合并方案（方案 B）

## 1. 背景与结论

方案 A 已支持 layout 增量编译时复用最近一次 Gradle 完整构建生成的 module setter store，并加载全部 AAR setter store。但新增或修改 `@BindingAdapter` 后，后续 layout 仍需要看到本轮 processor 生成的新声明。

Java adapter 可以在 Mapper APT 的单次 invocation 内直接对同轮 layout 可见。但真实 Android Studio 2025.3.4 E2E 证明，Kotlin 1.7.21 KAPT 在宿主 JBR 中会因 `jdk.compiler` module access 失败；KAPT 失败后的 Java APT 无法处理 Kotlin source，也不会从 classpath class 重新发现 `@BindingAdapter`。

因此 Kotlin adapter 采用条件慢路径：

```text
Gradle JVM 子进程 KAPT（空 layoutInfoDir）
  -> 官方 processor 扫描 Kotlin adapter stub
  -> 输出 current-module setter store
普通 Kotlin compile
  -> 生成 adapter class
merge baseline/history/current
Mapper Java APT
  -> 使用 merged store 生成 BindingImpl / Mapper / BR
```

默认路径仍只有一次 Mapper APT；只有 Kotlin adapter declaration 变化时增加一次隔离 KAPT。隔离逻辑收敛在 DataBinding/SourceCompiler 子流程，不暴露到 `JuggCompiler`、文件变化或部署主流程。

DataBinding 逻辑全部收敛在 `SourceCompiler` 的 DataBinding 子流程，不暴露到 `JuggCompiler` 主流程。

## 2. 编译流程

```text
SourceCompiler
  -> SourceDataBindingProcessor
     -> layout 变化或当前 source 含 adapter declaration 时触发
     -> DataBindingGenMapperCompiler
        -> DataBindingClasspathHelper 提供上轮 merged store；不存在时提供 Gradle baseline
        -> Kotlin adapter 变化：Gradle JVM 子进程 KAPT 生成 current-module store
        -> Kotlin adapter class compile
        -> DataBindingSetterStoreCache.merge(baseline, currentModuleStore)
        -> Java Mapper APT 使用 merged store
        -> 有 layout 时继续生成 incremental Mapper / BindingImpl / BR
        -> adapter-only 时只更新 store，随后进入普通源码编译
  -> Kotlin / Java / Dex compile
```

Java adapter 同轮仍可由一次 APT 处理。Kotlin adapter 同轮先生成临时 store，adapter class 编译成功后才完成 merge；Mapper APT 与后续轮次统一消费同一份 merged store。

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
- 隔离 KAPT、processor、Kotlin class compile、baseline、JSON 或 merge 任一步失败时，停止 Mapper APT并沿现有增量失败链路处理。
- adapter declaration 未变化且 cache 无效时，允许直接使用 Gradle baseline。
- 不改变 `SourceCompiler` 现有 Mapper fail-open/fallback 行为，避免扩大本功能语义范围。

## 8. 分阶段范围

### B1：当前 module

- 默认 Mapper APT；Kotlin adapter declaration 变化时增加一次隔离 KAPT store phase。
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

- Gradle JVM选择和 javac module flags。
- current-module store 合入 baseline 并保留无关记录。
- 相同 declaring type 的新记录替换旧记录。
- 多轮 generated store 累积结果稳定。
- 冲突时上一版 merged store 不被覆盖。

### L3

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
- 不增加暴露到主流程的 setter-store compiler。
- 不改造源码删除事件链路。
- B1 不实现 project library 向 application 的传播。
