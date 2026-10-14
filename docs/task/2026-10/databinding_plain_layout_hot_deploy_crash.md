**Purpose:** Track plain-layout hot-deploy crashes in DataBinding modules.

**Decision:** Require an actual layout declaration before generating a DataBinding trigger.

**Impact:** Preserves normal ViewBinding and mapper behavior.

# DataBinding 模块普通布局增量部署崩溃修复

## 用户场景与证据边界

作者反馈：模块开启 `dataBinding=true` 且包含普通 findViewById 布局时，完整 baseline 安装正常；Jugg 增量部署修改过的普通布局后，进入无关页面崩溃，基础库 DataBinding 控件 `inflate(...)` 返回 null 后发生 NPE。删除/重建 Jugg 资源缓存可临时恢复。

已确认的代码问题：`DataBindingArgsManager.isUseDataBinding()` 原先在模块开关开启时直接返回 true，使传入具体布局时的 `<layout>` 判断失效，普通 XML 也生成 DataBinding processor trigger，导致不必要的 Mapper 重新生成。

作者反馈在真实工程中应用本 PR 后 crash 消失；目前没有现场堆栈、生成 Mapper 或报告材料，不能将“Mapper 重新生成”认定为原始 crash 的完整根因。官方解析器将普通 XML 排除在 DataBinding 布局之外，但不保证官方构建流程不生成 Mapper；跳过这类布局的 trigger 是 Jugg 的增量编排策略。

## 修复范围

1. `DataBindingArgsManager.kt`：传入具体布局时模块开关不再短路，逐文件判断是否含 `<layout>`；不传布局或传空列表的模块级查询保持原语义；保留 Gradle kapt 输出目录探测。
2. `DataBindingGenBaseClassesCompiler.kt`：在不生成 DataBinding trigger 的分支中，将生成的 ViewBinding Java 保存到 `incrementalBaseClassOutDir`，不再按模块级 DataBinding 开关跳过保存。否则同时开启 DataBinding/ViewBinding 时，Kotlin 编译只能读取旧 baseline Binding，新增字段出现 `Unresolved reference`。
3. `isUseViewBinding(moduleInfo)` 保持原有模块级判断。真实 DataBinding 布局、混合布局和 adapter declaration 的 processor 路径保持原有职责。
4. 同步 DataBinding 知识库的布局判断和生成源保存契约；用户操作、支持范围及公开入口不变，无需修改 Wiki。

Mapper 包装类 `collectDependencies()` 可能遗漏原 Mapper 的依赖，是独立发现的存量问题，不在本 PR 扩大修复范围；本 PR 不能证明该问题在所有 DataBinding 场景中被修复。

## 测试价值与落点

- `DataBindingArgsManagerTest`（L1，6 用例）保护模块查询与本轮布局判定契约；它不能单独证明后续编译或 crash 根因。
- 新增 `ViewBindingCompileFlowTest`（L2 编译子流程，非真机 L3），保护资源生成到 Kotlin 编译的子流程：调用真实 base class 生成器，使用正常 `getGeneratedSourcePaths()`，交给真实 Kotlin compiler 解析新增 Binding 字段。已有 `SourceCompileDataBindingTest` 聚焦 Mapper/adapter processor，本场景由独立 ViewBinding 编译 Flow owner 负责，无生产代码测试 seam。
- 覆盖同时开启 DataBinding/ViewBinding 的失败场景、纯 ViewBinding 正常路径，以及真实 `<layout>` 仍输出 processor trigger；不以源码字符串或 mock 调用代替编译结果。

## 验证结果

```text
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.databinding.DataBindingArgsManagerTest" --tests "com.sickworm.intellij.jugg.compiler.databinding.ViewBindingCompileFlowTest" --tests "com.sickworm.intellij.jugg.compiler.databinding.LegacyViewBindingLookupTest"
./gradlew :idea:buildPlugin
```

- 修复前失败证据：原 PR 版本中，同时开启两种 binding 的新增字段编译失败，报 `Unresolved reference: newTitle`；纯 ViewBinding 对照通过，真实 DataBinding 布局仍输出 trigger。
- 补齐后的定向测试 12 用例全部通过（6 个布局判断、3 个编译 Flow、3 个 legacy lookup）；`./gradlew :idea:buildPlugin` 成功，包含 Kotlin/Java 编译、插件打包及第三方合规检查。验证环境为 JDK 17、Android SDK platform 33。
- 作者真实工程验证（2026-10-11）：工程混用 DataBinding、ViewBinding 和 findViewById；基线编译后换用原 PR 插件，增量修改 findViewById XML 后，无关页面不再崩溃。此次维护者补丁未在该工程或设备上重新验证。
