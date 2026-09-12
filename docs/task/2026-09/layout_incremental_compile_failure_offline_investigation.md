# 修改布局后 Jugg 增量编译失败：离线排查指南

> 本文用于无法联网的 Android/Jugg 环境。只使用现场已有的历史日志、应用源码、构建配置、项目结构、版本匹配的 Jugg 源码、本地产物和设备状态；禁止搜索、下载、上传材料或创建外部复现工程。
>
> 本文是可独立复制传播的单文件交付物，已内置证据采集命令、Agent 执行清单、报告模板和脱敏检查，不依赖其它配套文档。

## 1. 结论边界

### 1.1 当前能确认的事实

- 用户现象只有“修改布局后，Jugg 增量编译报错”；尚未提供失败时间、错误文本、失败布局、项目版本或产物。
- 本地现有 10 份 `build/jugg/log/compile_*.log` 已完成只读盘点，未检索到 `isCompileSuccess=false`、`incremental compile error`、`aapt2 compile failed`、`loadTable failed` 或 `aapt2 link failed` 的布局失败现场。
- 现有日志包含 Jugg `3.4.3-release` 的正常对照：
  - `2026-09-10 11:19:51.925` 检测到一个 layout resource，`11:19:58.744` 完成增量编译；日志显示 layout 编译后继续编译受影响源码，最终 `failure: 0`。
  - `2026-09-10 17:00:40.318` 完成 layout、Java/Kotlin 源码编译和部署；部署数据包含 `resources.arsc` 与对应的 `res/layout` overlay。
- 当前主线源码证明布局输入会经过以下阶段，但当前主线不能自动代表用户现场版本：

```text
布局文件变化
  -> ResourceOverlayCompiler 按 APK 分流
  -> ResourceCompiler.processViewBinding
     -> 可选 DataBinding/ViewBinding split 与生成源码
  -> ResourceCompiler.aapt2Compile
     -> layout XML -> .flat
  -> ArscCompiler.loadTable / incLinkCompile
     -> .flat + APK 资源基线 -> resources.arsc / compiled res / R.java
  -> SourceCompiler
     -> 编译 R.java、Binding 生成源和受影响业务源码
  -> staging / deploy
```

### 1.2 当前推断

- 领先的边界假设是：失败发生在“layout 被识别为 Resource”到“资源产物交给源码阶段”之间，或发生在资源成功后触发的生成源码编译阶段。
- 该假设只有用户描述支持，置信度低。现有正常对照说明“修改 layout 必然失败”不成立，但不能排除特定 XML、DataBinding/ViewBinding、variant、APK 基线或插件版本条件。

### 1.3 必须补齐的未知项

- 失败运行的完整 `compile_*.log`，以及它之前的最后成功运行和首次异常运行。
- 失败 layout 的稳定别名、改动 diff、所属 resource root、模块、variant 和 APK owner。
- 用户现场的 Jugg、Android Studio、Gradle、AGP、Kotlin、JDK 和 Android SDK/设备版本。
- 模块是否启用 DataBinding/ViewBinding，layout 是否以 `<layout>` 为根，是否涉及 `<include>`、自定义属性、新资源 ID 或 Dynamic Feature。
- 报错前是否已重试、重启、Gradle build、clean、清缓存、重装或清数据。
- 现场 Jugg 源码是否与安装版本匹配。只有当前主线源码时，所有源码判断都只能作为定位地图。

调查目标不是猜测某个 AAPT2 或 DataBinding Bug，而是确定：最后正常运行、首次分歧和最终失败分别发生在哪个阶段，以及错误输入或状态由谁决定。

## 2. 现场保全

任何再次 Run、编译、部署、重启、Gradle build/clean、清缓存、重装或清数据之前，先初始化证据目录并保存现场。`EVIDENCE_ROOT` 必须位于工程目录之外，并且不可复用已有目录。

```bash
PROJECT_ROOT='<PROJECT_ROOT>'
EVIDENCE_ROOT='<EVIDENCE_ROOT>'
LAYOUT_RELATIVE_PATH='<MODULE_RELATIVE_LAYOUT_PATH>'
mkdir -p "$EVIDENCE_ROOT/log" "$EVIDENCE_ROOT/project" "$EVIDENCE_ROOT/jugg" "$EVIDENCE_ROOT/artifacts" "$EVIDENCE_ROOT/device"

git -C "$PROJECT_ROOT" rev-parse HEAD > "$EVIDENCE_ROOT/project/git_head.txt"
git -C "$PROJECT_ROOT" branch --show-current > "$EVIDENCE_ROOT/project/git_branch.txt"
git -C "$PROJECT_ROOT" status --short > "$EVIDENCE_ROOT/project/git_status.txt"
git -C "$PROJECT_ROOT" diff -- "$LAYOUT_RELATIVE_PATH" > "$EVIDENCE_ROOT/project/layout.diff"
if [ -f "$PROJECT_ROOT/$LAYOUT_RELATIVE_PATH" ]; then
  cp -p "$PROJECT_ROOT/$LAYOUT_RELATIVE_PATH" "$EVIDENCE_ROOT/project/layout.xml"
  shasum -a 256 "$EVIDENCE_ROOT/project/layout.xml" > "$EVIDENCE_ROOT/project/layout.sha256"
fi

cp -R "$PROJECT_ROOT/build/jugg/log" "$EVIDENCE_ROOT/log/original"
cp -R "$PROJECT_ROOT/build/jugg/database" "$EVIDENCE_ROOT/jugg/database"
cp -R "$PROJECT_ROOT/build/jugg/build/staging" "$EVIDENCE_ROOT/jugg/staging"
rg --files "$EVIDENCE_ROOT/log/original" | sort > "$EVIDENCE_ROOT/log/files.txt"
while IFS= read -r file; do
  wc -c "$file"
  shasum -a 256 "$file"
done < "$EVIDENCE_ROOT/log/files.txt" > "$EVIDENCE_ROOT/log/metadata_and_sha256.txt"
```

目录不存在时记录“确认不存在”；没有权限读取时记录“不可访问”。复制命令失败后只收口受影响材料，不继续执行会覆盖现场的动作。

至少保存：

- 全部 `build/jugg/log/compile_*.log`，不是只保存 `compile_latest.log`。
- 当前 Git commit、分支、dirty state、失败 layout 的 diff、模块和 variant。
- `build/jugg/database/`、`build/jugg/build/staging/`、相关 APK、R.jar、DataBinding 中间产物的副本或校验和。
- Jugg/IDE/JDK/Gradle/AGP/Kotlin/SDK 版本，以及失败前已经执行过的状态变更。
- 若编译实际上成功、错误发生在部署或运行阶段，再保存设备 PID、已安装 APK 和 overlay；纯编译失败不先扩大到设备排查。

证据目录必须位于工程目录之外。不能复制敏感文件时，至少记录稳定路径别名、大小、修改时间和 SHA-256。

## 3. 先建立历史日志时间线

### 3.1 日志清点

输入：现场保全后的 `$EVIDENCE_ROOT/log/`。变量初始化方式见第 2 节。

搜索目标：

```bash
rg -n 'Jugg compile started|Compile files:|resource: \[|Processing view binding|Compile DataBinding failed|process view binding failed|aapt2 daemon command|aapt2 compile failed|loadTable failed|aapt2 invoke failed|aapt2 link failed|Compile finished|incremental compile error|SEVERE' "$EVIDENCE_ROOT/log"
```

预期观察与诊断作用：

| 观察 | 诊断作用 |
| --- | --- |
| 失败 layout 出现在 `resource: [...]` | 文件识别已完成，继续定位资源阶段的首个异常 |
| layout 没有出现在任何 `Compile files` | 优先排查文件事件、resource root、模块/variant 归属，不进入 AAPT2 猜测 |
| 先出现 `Compile DataBinding failed` | 第一跳进入第 5.1 节，最终 AAPT2 文案可能只是上层收口 |
| `compile --legacy` 后出现 `output: ... error:` 或 `aapt2 compile failed` | 第一跳进入第 5.2 节 |
| `loadTable failed` 早于 link | 第一跳进入第 5.3 节，先查 APK/资源表基线和 invoker 状态 |
| `aapt2 invoke failed` / `aapt2 link failed` | 第一跳进入第 5.4 节，保留完整 errorOutput |
| layout 显示编译完成，随后 Java/Kotlin/Dex 失败 | 第一跳进入第 5.5 节，检查 R/Binding 生成源码和受影响业务源码 |
| 编译成功，部署或运行失败 | 本文的编译假设被削弱，转到 staging、deploy 和设备加载边界 |

不要只截取最后一条异常。对首个异常向前、向后至少扩展到本次 `Jugg compile started` 与最终任务状态。

### 3.2 三点时间线

必须从全部历史日志中确定：

| 运行 | 最低判定要求 |
| --- | --- |
| 最后正常 | 同类 layout 修改完成资源编译；如本次目标包含部署，还要确认 overlay/部署成功 |
| 首次分歧 | 第一次出现阶段、输入、模块/APK owner、基线、告警、跳过或重试顺序变化；即使最终成功也要记录 |
| 最终失败 | 用户可见错误所在运行，并记录它消费的下层结果 |

每个运行记录：时间、变更文件、模块、variant、APK owner、DataBinding/ViewBinding 状态、AAPT2 命令阶段、基线来源、生成产物、重试/回退和最终结果。

若历史日志发生轮转、截断、空文件或时间断层，标记为“未收集”或“内容截断”，不能写成“此前没有异常”。

## 4. 确认应用源码和项目拓扑

目标链路：

```text
layout 定义位置
  -> 实际 resource root
  -> 编译模块
  -> variant
  -> APK owner（base / feature / test APK）
  -> Gradle/Jugg 资源基线
  -> staging
  -> 最终产物或设备加载对象
```

### 4.1 layout 源码

输入：失败 layout 的保全副本和 Git diff。

检查：

- XML 是否可解析；Android resource 语义仍以现场 AAPT2 输出为准。
- 本次是新增、修改、重命名还是删除。Jugg 当前删除语义不会生成资源移除数据，重命名只把新路径作为新增/修改输入。
- 是否新增 ID、styleable、自定义属性、资源引用、`<include>`、DataBinding 表达式或 ViewBinding 需要生成的新类。
- 同名文件是否存在于多个 `res.srcDirs`、source set 或依赖模块。
- 失败是否只发生在某个 qualifier，例如 `layout-vNN`、语言、尺寸或产品渠道目录。

可执行检查：

```bash
git -C "$PROJECT_ROOT" diff -- "$LAYOUT_RELATIVE_PATH"
rg -n '<layout|<include|@[+]?id/|\?attr/|@[A-Za-z0-9_.-]+/' "$PROJECT_ROOT/$LAYOUT_RELATIVE_PATH"
xmllint --noout "$PROJECT_ROOT/$LAYOUT_RELATIVE_PATH"
```

`xmllint` 不存在时记录“工具不可用”；它成功只证明 XML 语法成立，不证明 Android resource link 一定成功。

### 4.2 模块与 variant

输入：`settings.gradle*`、模块 `build.gradle*`、version catalog、Jugg project info 和完整构建记录。

检查命令：

```bash
rg --files "$PROJECT_ROOT" -g 'settings.gradle*' -g 'build.gradle*' -g 'gradle.properties' -g 'libs.versions.toml' |
  while IFS= read -r file; do
    rg -n 'includeBuild|include\(|com.android.application|com.android.library|com.android.dynamic-feature|namespace|applicationId|dynamicFeatures|sourceSets|res.srcDirs|buildFeatures|dataBinding|viewBinding' "$file"
  done
```

预期观察：

- layout 所在目录必须属于本次 variant 的真实 resource root。
- library 或 included build 中的资源必须能映射到实际 APK owner，不能只凭目录名推断。
- Dynamic Feature 要记录 base APK 和 feature APK 的资源依赖。
- DataBinding/ViewBinding 开关、namespace/packageName 和 Gradle 中间产物必须对应同一模块、同一 variant。

文件清单为空时，先核对 `$PROJECT_ROOT`，不要把“搜索路径错误”写成“工程没有构建配置”。

## 5. 按首个异常进入最短源码路径

### 5.1 DataBinding/ViewBinding 预处理失败

搜索词：

```text
Processing view binding
DataBindingGenBaseClassesCompiler error
Compile DataBinding failed
process view binding failed
Layout info files not generated
Package name not found
data binding is not enabled
```

检查目标：

- layout 是否真的需要 DataBinding/ViewBinding；普通 layout 不应误入 mapper 流程。
- `packageName`/namespace、`isUseDataBinding`、`isUseViewBinding` 是否与当前 variant 一致。
- `tempDataBindingLayoutXmlDir` 是否生成 layout info。
- stripped XML、base class 或 trigger source 是否存在且非空。
- AGP 对应的 DataBinding 中间产物候选路径是否存在，是否混入另一 variant 的旧文件。
- `<include>` 相关失败是否来自被引用 layout，而不是用户最后修改的文件本身。

分支判断：

- `Compile DataBinding failed` 出现在任何 AAPT2 command 之前：behavior owner 优先是 `DataBindingGenBaseClassesCompiler` 或 `DataBindingArgsManager`。
- split XML 已生成，但 AAPT2 编译 split 文件失败：同时保留原 XML 与 split XML，对比后转第 5.2 节。
- layout 不需要 DataBinding/ViewBinding，却进入该流程：检查项目快照和 `<layout>` 猜测条件。

### 5.2 AAPT2 flat compile 失败

搜索词：

```text
aapt2 daemon command: compile --legacy
output: ... error:
aapt2 compile failed
res file compile to flat failed
res dir compile to flat failed
```

检查目标：

- 完整 `compile --legacy` 参数，尤其是实际输入文件是原 layout 还是 stripped XML。
- errorOutput 指向的文件、行列、资源名和引用目标。
- 预期 `.flat` 名称及其文件是否存在、大小是否大于 0。
- 多 resource root 是否落入独立输出目录；不要把另一 root 的同名 flat 当成本轮产物。

分支判断：

- 同一保全 XML 用现场相同 AAPT2 和相同参数稳定失败：增强 XML/resource 输入问题。
- 原 XML 成功、stripped XML 失败：增强 DataBinding split 产物问题。
- 命令返回成功但 flat 缺失或为空：增强输出路径、命名或文件系统问题。
- 无修改直接重试即成功：降低稳定 XML 语义错误的权重，转查 daemon、并发、临时目录或状态复用。

### 5.3 APK 资源表基线加载失败

搜索词：

```text
aapt2 loadTable start
isNeedLoadLatestResApk
generateStyleableFile failed
loadTable failed
no cache data found, run with --load first
```

检查目标：

- `--load` 使用的是 Gradle 基线 APK，还是“已部署 `resources.arsc` + manifest”组成的最新 res APK。
- APK、manifest、`resources.arsc` 是否属于同一 variant 和 APK owner。
- `android.jar` 是否存在。
- styleables 和 ResGuard mapping 的生成失败是否与本次新增引用直接相关。两者是 Best-effort 输入，失败可能不会立即终止 `loadTable`。
- 失败的 invoker 后续是否被 release；不能把进程存活等同于资源表已加载。

分支判断：

- `loadTable failed` 后同一 invoker 仍继续 link：优先调查状态机或版本差异。
- 重新创建 invoker 后仍对同一 res APK 稳定失败：增强 APK/arsc/manifest 基线不一致。
- 只在已部署 arsc 路径失败，而原始 APK 基线成功：增强 deploy history 或最新 res APK 组装问题。

### 5.4 AAPT2 inclink 失败

搜索词：

```text
aapt2 daemon command: inclink
aapt2 invoke failed
aapt2 link failed
makeResApk failed
multiply apk load not supported
```

检查目标：

- 当前 APK owner、resource package、base/feature 关系和 link 输入 flat 列表。
- Dynamic Feature 是否同时接收到 base 本轮更新的 flat。
- 本次是否新增 styleable、attr 或 release 资源名；对应 styleables/mapping 是否有效。
- `resources.arsc`、compiled layout、`R.java` 是否全部缺失，还是只缺其中一类。
- 路径含空格时，日志展示的一行命令仅用于阅读；实际 daemon 协议应当每个参数独占一行，不能据展示文本推断发生了空格拆参。

分支判断：

- flat 编译成功但 link 稳定失败：XML 语法问题权重下降，优先查资源引用、基线和 APK owner。
- base APK 成功、feature APK 失败：优先查 base/feature 资源 ID 同步和 package 配置。
- link 返回失败后下一轮重新 load 成功：记录为状态相关证据，但仍需用 errorOutput 和产物校验定位触发条件。

### 5.5 资源成功后的生成源码失败

搜索词：

```text
R.java
RJavaFixer
DataBinderMapper
BindingImpl
cannot find symbol
unresolved reference
Compile classes to DEX
```

检查目标：

- 日志是否已经明确 layout 编译完成。
- `R.java` 是否含本次新增或修改的资源字段，修正后文件是否可编译。
- ViewBinding base class、DataBinding trigger/mapper/BR/BindingImpl 是否生成，并属于正确 package/module。
- 失败业务源码是否由本次资源或 binding 变化触发，而不是独立的同时修改。
- Git 补检或依赖刷新重试是否改变了输入，并且重试最多一次。

分支判断：

- layout、flat、arsc 均成功，但生成源码缺字段/缺类：behavior owner 位于生成源码或源码 classpath，不应归类为 AAPT2 compile 失败。
- 生成源码正确，只有业务源码失败：继续定位业务引用和模块依赖。
- Gradle 同 variant 也稳定失败：增强项目源码/配置问题；Gradle 成功而 Jugg 失败才增强旁路编译差异。

### 5.6 layout 未进入资源编译

检查目标：

- 文件变化日志和 `Compile files` 是否包含该 layout。
- 文件是否位于实际 Gradle build directory、被排除目录、未注册 source set 或不属于当前 variant。
- 重命名/删除是否只留下不存在的旧路径。
- 本轮 remote/local projectDir 是否选中了另一个工程。
- Jugg project info、module build path 和 APK ownership 是否对应当前完整构建基线。

如果 layout 从未成为 `CompileFile.Type.Resource`，AAPT2 和 DataBinding 不是第一行为 owner。

## 6. 使用版本匹配的 Jugg 源码

先从现场日志记录安装版本、compile timestamp 或 release build id。优先使用本地已有的对应 tag、commit、源码归档或插件产物；禁止联网获取。

若本地 Jugg Git 仓库包含对应 ref，使用 `git show <VERSION_REF>:<PATH>` 只读查看，避免 checkout 改变现场。最短源码链为：

| 角色 | 当前主线定位地图 | 关键分支/日志 |
| --- | --- | --- |
| 资源总控 | `ResourceOverlayCompiler.doApkCompile()` | manifest -> flat -> arsc；失败对外收口为 `aapt2 link failed` |
| layout 预处理与 flat | `ResourceCompiler.processViewBinding()` / `aapt2Compile()` | `process view binding failed`、`aapt2 compile failed` |
| Binding base class | `DataBindingGenBaseClassesCompiler.doModuleCompile()` | `Compile DataBinding failed` |
| APK 资源表与 link | `ArscCompiler.loadTable()` / `incLinkCompile()` | `loadTable failed`、`aapt2 invoke failed` |
| daemon 协议 | `Aapt2DaemonInvoker.invoke()` | `aapt2 daemon command`、`output: ... error:` |

版本匹配源码需要记录：症状 owner、behavior owner、关键条件、最短调用链，以及现场确实执行该分支的日志证据。若只有当前主线源码，报告必须写明“源码版本不匹配，仅作搜索地图”。

## 7. 在首个分歧点检查本地产物

只检查由日志首个分歧选择的材料：

| 分歧边界 | 本地材料 | 只读检查 | 诊断作用 |
| --- | --- | --- | --- |
| 文件识别 | project info、compile context、失败 layout | 路径、module、variant、resource root 映射 | 判断输入是否进入正确编译单元 |
| DataBinding/ViewBinding | temp DataBinding 目录、layout info、stripped XML、生成源 | 文件存在性、大小、hash、原/stripped diff | 区分预处理与下游 AAPT2 |
| AAPT2 compile | 实际 XML、`.flat` 输出目录 | 对齐命令输入、flat 名称、大小、hash | 区分输入错误和输出丢失 |
| load/link | 基线 APK、已部署 arsc/manifest、styleables、mapping | variant/APK owner、hash、APK entries | 区分基线和当前 flat |
| 源码编译 | `R.java`、Binding 源、class、R.jar | 字段、package、class owner、hash | 区分资源成功与生成源码失败 |
| 部署/运行 | staging、overlay、设备已安装 APK | 仅在编译成功时检查 | 防止把部署错误误报为编译错误 |

不要先全量导出数据库或所有 APK。若某项工具不可用，保留其它有效证据并标记“未检查”，禁止伪造成功结果。

## 8. 假设与反证

在取得失败日志前，只保留低置信度边界假设，不宣称根因：

| 假设 | 支持证据 | 缺失/冲突证据 | 直接反证 |
| --- | --- | --- | --- |
| H0：失败位于 layout 资源链或它生成的源码链 | 用户描述修改 layout 后失败 | 没有失败日志；只有本地 Jugg 3.4.3 的正常 layout 增量对照，未知是否匹配用户版本 | 失败运行中 layout 未进入 `resource`，或首个异常发生在资源阶段之前 |
| H1：DataBinding/ViewBinding 预处理产生错误输入 | layout 会先经过可选 split/base class 生成 | 未知模块是否启用相关能力 | AAPT2 使用原 XML，且预处理未执行或完整成功 |
| H2：特定 XML/resource 使 AAPT2 compile 失败 | 修改 layout 可直接影响 flat compile | 没有 AAPT2 errorOutput；本地正常对照不是用户现场 | 同一输入和参数成功生成非空 flat，失败先发生在其它阶段 |
| H3：APK/arsc 基线或 inclink 状态不一致 | link 依赖有状态 daemon 和 APK scoped 基线 | 不知道 `loadTable`/link 是否失败 | flat/link 在保全基线上稳定成功，或首个异常早于 load/link |
| H4：资源成功后 R/Binding/业务源码失败 | layout 可能生成 R 或 Binding 源码并触发跟编 | 未知资源阶段是否成功 | 日志在生成源码前已经失败，或生成源码和源码编译全部成功 |
| H5：module/variant/APK owner 错配 | 多 resource root、library、included build、feature 会改变归属 | 未收集项目拓扑 | project info、命令、产物和 APK entries 全部指向同一正确 owner |

确定领先根因前，至少连接两层证据：首次历史分歧 + behavior owner 源码，或首次历史分歧 + 对应产物。高置信度结论应同时连接日志、版本匹配源码和产物。

## 9. 有界离线实验

实验必须在第 2 节现场保全完成后执行，并且每次只改变一个条件。

| 顺序 | 单一变量 | 操作 | 观察 | 诊断分支 |
| --- | --- | --- | --- | --- |
| 1 | 无状态变化 | 解析保全日志、diff、project info 和产物 hash | 找到首个异常和缺失产物 | 选择第 5 节分支，通常无需继续实验 |
| 2 | 运行次数 | 不修改源码，以同一 Jugg 配置直接重试一次 | 稳定失败 / 无修改恢复 | 稳定失败增强确定性输入；恢复增强 daemon/临时状态/竞态 |
| 3 | XML 输入 | 在证据目录使用现场同一 AAPT2、同一 `compile --legacy` 参数编译保全 XML | 原 XML/stripped XML 的 flat 结果 | 区分 XML、split 产物和 Jugg 调度 |
| 4 | 编译器路径 | 同模块、同 variant、同源码执行最小 Gradle resource task，不 clean | Gradle 与 Jugg 是否同时失败 | 同时失败增强项目输入；仅 Jugg 失败增强旁路差异 |
| 5 | 基线 | 保全后执行一次同 variant 完整 Gradle build，再重复同一 layout 修改 | 基线刷新前后差异 | 仅刷新基线后恢复，增强 project info/APK/arsc/DB 基线假设 |
| 6 | 最小状态 | 只清理由直接证据指向的最小 Jugg 状态 | 是否只需局部恢复 | 记录被清对象；禁止直接全工程 clean 或删除整个 `build/jugg` |

每次实验保存新的完整日志、命令、退出码、产物清单和 SHA-256。已知且可恢复的重试最多一次；第二次仍失败时保留最终异常，不继续盲目重试。

## 10. 完成条件

- 已得到最后正常、首次分歧、最终失败三点时间线。
- 已确认 layout 的 module、variant、resource root、APK owner 和基线链。
- 已用版本匹配源码定位症状 owner、behavior owner 和最短调用链；若版本不匹配已明确限制。
- 领先结论有可观察证据和明确反证，冲突证据已解释。
- 任何状态变更前都已保存现场，并且只执行能区分假设的单变量实验。
- 输出报告已使用第 12 节模板完成脱敏检查。

## 11. Offline Agent 执行清单

- [ ] 已按第 2 节保存原始环境，记录不能复制或读取的材料。
- [ ] 已清点全部历史日志，标记轮转、截断、空文件和缺失区间。
- [ ] 已按第 3 节确定最后正常、首次分歧和最终失败。
- [ ] 已按第 4 节确认 layout、resource root、module、variant、APK owner、baseline 和 staging 的关系。
- [ ] 已按第 5 节从首个异常进入唯一匹配的最短分支，没有从最终异常倒推根因。
- [ ] 已按第 6 节使用版本匹配的 Jugg 源码；只有主线源码时已明确版本限制。
- [ ] 已按第 7 节只检查首个分歧所需的本地产物。
- [ ] 已按第 8 节记录领先假设、竞争假设和可直接推翻它们的观察。
- [ ] 只有现有证据仍不能区分假设时，才按第 9 节执行单变量实验。
- [ ] 所有状态变更均发生在现场保全之后，且已记录动作与影响。
- [ ] 已使用第 12 节报告模板输出结论，并完成 Agent 侧脱敏。

## 12. 最终调查报告模板

### 12.1 结论与证据边界

- 结论：`<已确认根因 / 最小失败边界 / 证据不足>`
- 置信度：`<高 / 中 / 低>`
- 用户可见现象：`<...>`
- 首个分歧：`<时间 + phase + owner + 输入/状态>`
- behavior owner：`<类/方法/分支>`
- 最小修复或下一步：`<...>`
- 已读取材料：`<日志、源码、项目结构、数据库、产物、设备状态>`
- 未收集：`<...>`
- 尚未读取：`<...>`
- 不可访问：`<...>`
- 确认不存在：`<...>`
- 内容截断：`<...>`
- 版本边界：`<App / Jugg / Android Studio / Gradle / AGP / Kotlin / JDK / Android>`
- Jugg 源码来源：`<匹配版本 ref / 当前主线仅作定位地图>`

### 12.2 历史时间线

| 运行 | 时间 | 输入/动作 | 首要 tag/phase | module/variant/APK owner | 状态或产物 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 最后正常 | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |
| 首次分歧 | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |
| 最终失败 | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |

首个异常的最小日志上下文：

```text
<只保留必要上下文，使用稳定别名>
```

与最后正常运行的差异：`<输入、阶段顺序、基线、owner、告警、跳过、重试或产物差异>`

### 12.3 项目拓扑与源码定位

```text
<LAYOUT_A>
  -> <RESOURCE_ROOT_A>
  -> <MODULE_A>
  -> <VARIANT_A>
  -> <APK_A: base/feature/test>
  -> <BASELINE_A>
  -> <STAGING_A>
  -> <OUTPUT_OR_DEVICE_OBJECT_A>
```

- layout 改动类型及关键 XML/resource 变化：`<...>`
- DataBinding/ViewBinding、include 和生成源：`<...>`
- 依赖来源、重名资源、多个 `res.srcDirs`：`<...>`
- 症状 owner：`<展示最终错误的位置>`
- behavior owner：`<决定错误输入、状态或产物的位置>`
- 最短调用链和关键分支：`<entry -> branch -> artifact/error>`
- 现场执行该分支的证据：`<...>`

### 12.4 产物、假设与实验

| 分歧边界 | 本地材料 | 检查方法 | 观察结果 | 对诊断的影响 |
| --- | --- | --- | --- | --- |
| `<识别/DB/VB/flat/load/link/source/deploy>` | `<...>` | `<只读命令或工具>` | `<存在性/大小/hash/内容摘要>` | `<增强/削弱哪个假设>` |

| 假设 | 支持证据 | 冲突/缺失证据 | 反证条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| 领先假设 | `<...>` | `<...>` | `<...>` | `<保留/推翻/降级>` |
| 竞争假设 | `<...>` | `<...>` | `<...>` | `<...>` |

| 单一变量 | 已保全材料 | 操作 | 结果 | 诊断变化 |
| --- | --- | --- | --- | --- |
| `<未执行则删除本表>` | `<...>` | `<...>` | `<...>` | `<...>` |

- 已确认事实：`<...>`
- 基于证据的推断：`<...>`
- 已排除项：`<...>`
- 尚不能确认项：`<...>`
- 已执行的状态变更及影响：`<没有则写“未执行”>`

### 12.5 脱敏检查

- [ ] 绝对路径已替换为稳定别名。
- [ ] 内部域名/IP、账号、设备标识和业务包名已替换。
- [ ] token、secret、cookie、个人信息和业务数据已移除。
- [ ] 源码与日志只保留支持结论的最小上下文。
- [ ] 版本、descriptor、phase、相对时间、checksum 和别名关系已保留。
- [ ] 未把“未收集”“尚未读取”“不可访问”“确认不存在”混为一谈。

Agent 已完成的脱敏类别：`<...>`

仍需提交者人工复核：`<...>`
