# Style 增量修改不生效：调查交接与 demo 复现

Purpose: Investigate style edits that do not take effect after incremental resource linking.
Decision: Separate confirmed accumulating merge semantics from the unverified numeric-ID/name mismatch hypothesis; provide isolated manual demo probes before changing the linker.
Impact: Adds a demo Activity and resource fixtures. No native linker behavior is changed by this investigation task.

## 1. 问题与当前状态

用户反馈：inclink 修改 style 经常不生效。目前没有具体的修改前后 XML、原始 APK、同次 Run 日志或设备表现，因此尚未确认这些现象的共同根因。本次保留源码证据和可重复操作的最小 demo，供下一会话继续。

本次只新增复现资源与观察页面，不修复合并逻辑，也不把源码推断视为设备复现。各项以完整 Gradle 构建后的结果为对照，区分编译输出错误、配置选择和部署/进程状态。

## 2. 已确认的源码行为

### 2.1 kKeepOriginal 不表示修改被丢弃

`aapt2-inclink/source/link/TableMerger.cpp` 的 `ResolveMergeCollision()` 对 style 调用 `existing_style->MergeWith(incoming_style, pool)`，原地修改旧对象后返回 `kKeepOriginal`。返回值决定保留哪个对象；需要查看合并后的内容判断新值是否进入。

### 2.2 style items 累积合并，缺项不删除

`aapt2-inclink/source/ResourceValues.cpp` 的 `Style::MergeWith()` 按 item 的 `key.name` 排序与匹配。名称相同时新项覆盖旧项；输入没有提供的旧项继续保留。因此删掉子 style 的 item 后，旧值可能继续覆盖父 style，而无法恢复预期继承效果。

### 2.3 parent 只在输入提供时替换

同一方法仅在 `other->parent` 存在时写入新 parent。若 compiled flat 不带 parent，旧 parent 继续保留。XML 删除显式 parent 后是否存在推导 parent，必须检查真实编译结果。本 demo 的 style 名称不含点，排除按点号推导父 style 的干扰。

### 2.4 configuration 和 product 分开合并

`TableMerger::DoMerge()` 使用 `FindValue(config, product)` 寻找目标。修改默认配置不会删除或替换同名 v31、night 等配置。运行设备可能继续选择更匹配的旧配置；这需要先与完整 Gradle 构建对照。

Jugg 的 `ArscCompiler.incLinkCompile()` 当前没有传入 `--override-styles-instead-of-overlaying`。该开关存在于 native 命令定义中；不要在未检查多来源 overlay 语义前直接全局启用。

## 3. 重点假设：基线属性只有 ID，本轮属性带名称

这条路径可能解释“只是修改已有 item 的值也不生效”，尚未完成复现：

1. `format/binary/BinaryResourceParser.cpp` 的 `ParseStyle()` 将二进制 item key 读为数字 ID 引用。
2. `ReferenceIdToNameVisitor` 只在解析器的 `id_index_` 中找到目标时补回名称；需确认 framework/外部 AAR 属性在实际基线加载后是否仍缺失名称，以及后续是否还有其他规范化步骤。
3. `Style::MergeWith()` 按可选 `key.name` 匹配。若旧 key 无名称、新 key 有名称，即使最终 ID 相同，也可能保留旧项并增加新项。
4. 后续链接可能让两项具有相同属性 ID。需要检查序列化输出是否确实有重复项；运行时如何消费这些项也须取证，不能预先认定旧值一定胜出。

优先比较 framework 属性 `android:textColor` 和项目自定义属性 `styleProbeToken`，两者使用相同的修改流程，以区分输入选择、名称恢复与合并问题。

## 4. 与 Issue 77 修复的关系

提交 `049176737` 只改变引用校验范围：本轮输入未触及的普通资源配置值保留已编译内容，styleable 继续原有处理。本轮涉及的 style 仍整体检查，未做 parent/item 字段级来源追踪。

因此，只修改 item 而继承旧坏 parent 时仍可链接失败；现有 `StyleItemOnlyMergeStillChecksInheritedParent` 单元测试明确期待失败。这与“链接成功但 item 显示旧值”应分开调查。此前说的“第一版”就是该提交，并非已经被字段级修复替代的历史实现。

截至本次交接读取，HEAD 为 `f0beae8a3`，相关各平台工具已由后续任务更新，详见 [预编译工具交付记录](./issue-77-release-prebuilts.md)。下一会话重新核对 HEAD、实际运行工具和 SHA-256，勿继续引用此前“尚未替换预编译工具”的旧状态。

## 5. Demo 入口与观察方式

Activity：`com.sickworm.jugg.demo.testcase.style.StyleMergeProbeActivity`。

```bash
adb shell am start -n com.example.myapplication/com.sickworm.jugg.demo.testcase.style.StyleMergeProbeActivity
adb logcat -s StyleMergeProbe:I '*:S'
```

入口没有替换 launcher。页面包含五组预览，并输出每组的 textColor、textSize 和 styleProbeToken。诊断使用空白 Theme 加载目标 style，排除应用主题回退的干扰；预览使用 ContextThemeWrapper，可与诊断并排观察。尺寸显示 px，会随 density/fontScale 变化。按钮只重新创建 Activity、重新读取资源，不会触发编译或部署。

资源位于 `android_demo_project/app/src/main/res/`；页面源码位于 `app/src/main/java/com/sickworm/jugg/demo/testcase/style/`。每个场景独立 values XML，减少同一 flat 带入其他场景的干扰。

## 6. 逐项复现矩阵

每个用例单独执行：恢复仓库中的原始 fixture → 为 demo 做完整 Gradle 构建并安装，建立基线 → 打开页面记录初始值 → 仅修改对应 XML → Jugg 增量编译部署 → 重新打开页面并记录值 → 保持相同修改做完整 Gradle 构建对照。开始下一项前恢复资源并重新建立基线，避免累积状态污染。

| 场景 | 修改文件（相对于 res）与操作 | 基线 | 完整构建对照 | 待检查的增量结果 |
|---|---|---|---|---|
| 1 Framework 同项改值 | `values/style_probe_framework.xml`：textColor 从 `#FFFF0000` 改为 `#FF0000FF` | 红 | 蓝 | 是否仍红；是否同一属性 ID 重复 |
| 2 Local 同项改值 | `values/style_probe_local.xml`：token 从 101 改为 202 | 101 | 202 | 是否与 framework 场景不同 |
| 3 删除 item | `values/style_probe_delete.xml`：只删除 textColor item，保留 textSize | 红、22sp | 继承绿色、22sp | 是否旧红色 item 残留 |
| 4 移除 parent | `values/style_probe_parent.xml`：移除整个 parent 属性，保留 textSize | 继承绿色、token=10 | token 应 unset；textColor 是否 unset 需看编译输出；22sp | 是否仍保留旧 parent 和 token=10 |
| 5 默认/v31 配置 | `values/style_probe_configuration.xml`：红改蓝，v31 文件不改 | API 31+ 紫；低版本红 | API 31+ 仍紫；低版本蓝 | 是否符合配置选择；再单独修改 v31 文件做更新对照 |

场景 3、4 是源码已确认的合并语义风险。场景 1、2 是重点调查对照，不预设它们必然复现。场景 5 用于识别正常配置选择，不能把符合完整构建结果的紫色判为增量缺陷。

不要通过切换字符串资源颜色来代替直接改 item 常量；直接常量有助于隔离 style item 本身的替换行为。不要在资源复现中同时修改 Activity 源码。

## 7. 下一会话取证顺序

1. 先执行项目必读流程，阅读 `02_compile_resource.md`、`02_compile_aapt2_inclink.md`；涉及设备/Run 时阅读 `09_plugin_runtime_debug.md`，遵循 `06_testing.md`。
2. 先跑场景 1 和 2。保存基线 APK、输入 XML、compiled flat、增量输出 resources.arsc、完整构建对照 APK，以及同次 Run 日志。记录 APK owner、SDK、variant、工具 SHA-256 和设备 configuration。
3. 在 load 后、合并前后、引用链接后依次检查 style item key 的 name/id 和值；重点查同一最终属性 ID 是否重复。调试采用现有 LLDB/Debug 构建流程，不先修改生产逻辑。
4. 先比较资源表。如果输出已错误，问题在合并/链接/序列化；如果输出正确，再查部署的 arsc、实际命中的 configuration、Activity 重建与进程缓存。
5. 再跑删除 item 和 parent 场景，确认需求是单来源替换还是跨来源累积。考虑同名 style 跨模块/source set 的合法组合后，再决定修复边界。
6. 有稳定复现后，在 native 行为 owner 添加失败断言，再实施最小修复。不要用纯粹手工构造且已经带完整名称的 style 测试代替真实 binary load → flat merge 路径。

## 8. 验证状态

本次提供手工复现夹具和交接文档。源码对合并规则的检查已经完成；未运行五项资源编辑/部署矩阵，未确认用户现场根因。

2026-11-11，在 `android_demo_project/` 使用 JDK 17 和本机 Android SDK 执行 `./gradlew :app:assembleDebug --offline --no-daemon`，结果 `BUILD SUCCESSFUL in 53s`，117 项任务中 19 项执行。已覆盖新增 Activity、Manifest 注册和资源的编译与打包；APK 位于 `android_demo_project/build/app/outputs/apk/debug/app-debug.apk`。按用户要求，仅验证 gradlew 构建，不部署、不执行设备矩阵。

此前尝试的 Jugg compile 返回外层 Jugg 仓库的远程构建日志，并因 Maven 429 失败，不能作为本 demo 的编译证据。用户未打开 demo 工程，因此本次不调整 Runtime 绑定，后续直接用上述独立 gradlew 命令验证。

## 9. 相关入口

- `aapt2-inclink/source/ResourceValues.cpp`：KeyNameComparator、Style::MergeWith。
- `aapt2-inclink/source/link/TableMerger.cpp`：ResolveMergeCollision、DoMerge。
- `aapt2-inclink/source/format/binary/BinaryResourceParser.cpp`：ParseStyle、ReferenceIdToNameVisitor、id_index_。
- `aapt2-inclink/source/link/ReferenceLinker.cpp`：当前值选择、style item 链接和属性检查。
- `aapt2-inclink/source/format/binary/TableFlattener.cpp`：style parent、item 排序与写回。
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ArscCompiler.kt`：工具调用、基线选择、失败后 daemon 释放。
- `aapt2-inclink/scripts/test_retained_resources.py`：已有真实 daemon 回归的组织方式；它尚未覆盖本交接的 style 改值问题。
- [引用校验修复记录](./issue-77-retained-resource-linking.md)、[native 构建指南](../../ai/02_compile_aapt2_build.md)。
