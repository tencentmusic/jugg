# DataBinding 跨模块 `<include>` 增量编译后崩溃修复

> 写入时间：2026-10-18
> 写入目标：为「跨模块 DataBinding 布局 `<include>` 的增量编译崩溃」修复提供复现与验证说明（随 PR 提交）

## 现象与证据

工程开启 DataBinding，且 app 侧一个带 `<layout>` 的布局 `<include>` 了另一个模块的 DataBinding 布局时：

1. 完整 baseline 编译并安装，应用正常；
2. 只给 app 侧那个带 `<layout>` 的布局加一个 `android:id`，执行 Jugg 增量编译与部署；
3. 打开该页面即崩溃。

```text
java.lang.NullPointerException: Attempt to invoke virtual method
'void ...databinding.LibraryCrossToolbarBinding.invalidateAll()'
on a null object reference
  at ...ActivityCrossModuleIncludeBindingImpl.invalidateAll(...BindingImpl.java:54)
  at ...ActivityCrossModuleIncludeBindingImpl.<init>(...BindingImpl.java:46)
  at com....DataBinderMapperImpl_Inc_1.getDataBinder(...Inc_1.java:52)
  at com....DataBinderMapperImpl.getDataBinder(...)
  at androidx.databinding.MergedDataBinderMapper.getDataBinder(...)
```

崩溃点不是 `DataBindingUtil.inflate` 返回 null，而是父 Binding 的 include 字段为 null，在构造期 `invalidateAll()` 抛出 NPE。

## 根因

Jugg 的增量编排会把根 Mapper 包装成 `DataBinderMapperImpl`，聚合两类产物：

- `DataBinderMapperImpl_Inc_N`：本轮增量产物，`collectDependencies()` 只声明本轮涉及的依赖，常见为**空的非 null 列表**；
- `DataBinderMapperImpl_Full`：baseline 全量产物，声明工程依赖的全部库 Mapper。

原实现中生成的 `collectDependencies()` 只要遇到第一个非 null 的 inc 列表就 `return`。因此当 `_Inc_N` 返回空表时，**baseline 的库 Mapper 列表被整份丢掉**。`MergedDataBinderMapper` 因而不会登记库模块 Mapper，框架无法为跨模块 `<include>` 的库布局造出子绑定，父 Binding 的 include 字段保持 null，构造期 `invalidateAll()` 触发 NPE。

这也解释了「baseline 正常、增量才崩」：baseline 轮设备上还没有 `_Inc_N`，走 baseline 分支；增量轮一部署 `_Inc_N` 即命中短路。

## 修复范围

1. `DataBindingTemplates.kt`：AndroidX 与旧 Support 两套模板中，生成的 `collectDependencies()` 由「取首个非空即返回」改为「合并增量全链依赖 + baseline 依赖，去重保序」；`getDataBinder` / `getLayoutId` / `convertBrIdToString` 的「首个命中」查表语义保持不变。新增纯函数 `mergeDependencies(incLists, originList)` 作为该合并算法的可测等价实现。
2. `LayoutIncludeAnalyzer.kt`：依赖模块的 layout-info 目录原先在 backup 目录与 Gradle 输出目录之间二选一，改为按序探测两者（新增 `findLayoutInfoInDirs`，按目录绝对路径去重、返回首个非空命中），避免跨模块 `<merge>` 布局只存在于其中一个目录时被漏查、生成残缺 Mapper；补充分析开始/结束与跨模块解析成功日志，并把所有未解析的 include 名汇总为结尾一条 warn。
3. `DataBindingGenMapperCompiler.kt`：新增增量 Mapper 热替换诊断日志标签 `DataBindingMapperHotSwap`，记录 `fullMapperNewlyGenerated`、holder 内 Inc Mapper 列表与提升后的 Inc Mapper 名。纯日志，无行为改动。

## 测试价值与落点

- `DataBindingTemplatesTest`（L1）用「模板生成 → javac 编译 → 反射断言」验证生成代码的真实语义，不依赖 `ANDROID_HOME`：`delegateMergesEveryLibraryMapperFromIncChainAndBaseline` 在修复前必失败，修复后通过；另三例覆盖 `mergeDependencies` 的空表、去重保序与回退 baseline。
- 仓库内 `android_demo_project` 新增同形态复现工程：库模块侧 `common_toolbar.xml`（DataBinding 布局）、app 侧跨模块 `<include>` 布局与 `CrossModuleDataBindingActivity`，首页新增入口。用它可手工走同一操作路径。

## 验证结果

```text
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.databinding.DataBindingTemplatesTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.databinding.DataBindingArgsManagerTest"
```

- 定向单测通过：`DataBindingTemplatesTest` 5/5、`DataBindingArgsManagerTest` 6/6，failures 0、errors 0。
- 手工复现（外部示例工程，app + 一个库模块，跨模块 `<include>`；jugg 3.5.2 复现崩溃，本地构建的 3.5.4 + 本修复通过）：修复前 baseline 安装正常 → 给跨模块布局加一个 `id` → 增量部署 → 打开该页 NPE；修复后同一路径不再崩溃。修复后重新生成的根 Mapper `collectDependencies()` 已同时包含 baseline 的库 Mapper。
- 证据边界：仓库内 `android_demo_project` 的复现工程已随本 PR 提供，但本 PR 未在其上做真机增量验证；它与外部工程的模块数量、AGP 版本不完全一致。
