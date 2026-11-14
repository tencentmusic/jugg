# SDK ConstraintLayout 纯 XML 替换后的 Jugg 运行时强转复现

> 后续修复：已采用“模块内联合生成 Binding、资源仍按 root 分批编译”，实现与验证见文末“修复实施”。下文前半部分保留修复前的复现记录。

## 已确认的结果

已按后续要求将场景落入 `android_demo_project` 并新增真实编译产物回归测试。demo 同样出现增量成功、页面强转崩溃、相同源码完整构建恢复；测试保留预期失败，未修复生产代码。具体步骤见下文“仓库 demo 与回归测试”。

已两次复现满足用户补充约束的 **Jugg 增量专属异常**：

1. 完整 Gradle 构建并运行正常。
2. 只修改 XML：将 SDK 自定义 ConstraintLayout 换成 AndroidX ConstraintLayout，同时修改另一资源目录中的一份 DataBinding XML。
3. Jugg 增量编译和部署成功，`isGradleCompile=false`。
4. 启动页面，在生成的 BindingImpl 中发生控件强转异常。
5. 保持 XML、Java 和 SDK JAR 不变，完整 Gradle 构建后运行恢复正常。

```text
Caused by: java.lang.ClassCastException:
  androidx.constraintlayout.widget.ConstraintLayout cannot be cast to repro.sdk.SdkConstraintLayout
  at repro.feature.databinding.OtherScreenBindingImpl.<init>(OtherScreenBindingImpl.java:28)
  at repro.feature.databinding.OtherScreenBindingImpl.<init>(OtherScreenBindingImpl.java:25)
  at repro.feature.DataBinderMapperImpl_Inc_1.getDataBinder(DataBinderMapperImpl_Inc_1.java:50)
  at repro.feature.DataBinderMapperImpl.getDataBinder(DataBinderMapperImpl.java:19)
  at androidx.databinding.MergedDataBinderMapper.getDataBinder(MergedDataBinderMapper.java:79)
  at androidx.databinding.DataBindingUtil.setContentView(DataBindingUtil.java:306)
  at repro.consumer.MainActivity.onCreate(MainActivity.java:5)
```

**本地复现的额外条件**：同一模块在一轮增量中修改两个不同资源目录下的 DataBinding 布局，SDK 控件所在布局位于先处理的资源批次。仅修改目标 XML 的对照正常。

原用户没有完整堆栈、工程结构或更多现场信息，因此已证实存在能解释全部已知症状的 Jugg 缺陷，但尚不能断言原用户必然命中这一额外条件。本轮按“先复现”的范围保全证据，未修改 Jugg 生产逻辑。

## 环境与本地材料

- 日期：2026-10-20，Asia/Shanghai。
- Jugg 运行时：`3.6.5-SNAPSHOT`。
- 设备：Pixel_6 模拟器，Android 15 / API 35，arm64，`emulator-5554`。
- 工具链：Gradle 7.4、AGP 7.3.1、JDK 17、compileSdk 34、ConstraintLayout 1.1.3。
- 独立应用：`repro.juggxmlcast`，与已有 demo / 业务应用分离。
- 可重放工程：`build/reproductions/jugg-sdk-xml-incremental/`。
- 分享包：`build/reproductions/jugg-sdk-xml-incremental.zip`，包含源码、SDK JAR 及源码、Gradle wrapper、XML 切换脚本、Jugg 配置与筛选后的证据。
- 完整原始日志和生成产物保留于工程的 `evidence/`；以上为本地生成文件，不随 Git 提交。

实验期间 IDE 曾选择后来连接的真机，那几轮模拟器观察不作为验证结论。下表中的有效复现均在 CLI `devices` 确认仅 `emulator-5554` 为 `isSelected=true` 后重跑；误装真机的独立实验包已移除，未读取真机业务日志。

## 最小结构

```text
app/
  src/main/java/repro/consumer/MainActivity.java
feature/
  libs/constraint-cast-sdk.jar
  src/main/res/layout/other_screen.xml
  src/secondary/res/layout/feature_screen.xml
```

SDK JAR 只有空控件及构造方法，没有资源或任何 Binding：

```java
package repro.sdk;
public class SdkConstraintLayout extends androidx.constraintlayout.widget.ConstraintLayout {
    public SdkConstraintLayout(android.content.Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
    }
}
```

`feature` 启用 DataBinding，明确配置两个资源目录：

```groovy
android {
    namespace 'repro.feature'
    sourceSets.main.res.srcDirs += 'src/secondary/res'
    buildFeatures { dataBinding true; viewBinding false }
}
dependencies {
    api files('libs/constraint-cast-sdk.jar')
    api 'androidx.constraintlayout:constraintlayout:1.1.3'
}
```

正常基线的 `other_screen.xml`：

```xml
<layout xmlns:android="http://schemas.android.com/apk/res/android">
    <repro.sdk.SdkConstraintLayout
        android:id="@+id/sdk_container"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
</layout>
```

另一目录的 `feature_screen.xml` 是带 `<layout>` 的普通 TextView，`android:text="baseline"`。Activity 只有页面加载，没有访问 SDK 控件：

```java
androidx.databinding.DataBindingUtil.setContentView(this, repro.feature.R.layout.other_screen);
android.util.Log.i("CastRepro", "page ready");
```

这次验证不要求 SDK AAR、SDK 生成代码、业务强转、XML 表达式或自定义 BindingAdapter。library 模块是已验证的工程结构，尚未断言它是触发缺陷的必要条件。

## 重放步骤

1. 配置 JDK 17 和 Android SDK 34，单独在 Android Studio 打开复现工程，启用包内 Jugg 配置。用 `jugg devices` 确认选中实验模拟器。
2. `python3 switch_xml.py baseline`；执行 `jugg gradle-build`，启动 `repro.juggxmlcast/repro.consumer.MainActivity`，确认 `page ready` 且 crash buffer 为空。
3. `python3 switch_xml.py both`，只产生两份 XML 的修改：目标控件标签 SDK → AndroidX；另一份布局文字 baseline → changed。
4. 执行 `jugg deploy`。日志必须显示仅这两份 XML 为编译输入，处理两个 res root，增量编译、部署成功。
5. 打开同一 Activity，出现上述 `ClassCastException`。保全此时原始日志、布局信息、生成源码和 crash，之后才能进行完整构建。
6. 不改任何源码，执行 `jugg gradle-build`，再次打开页面，应正常。对照 SHA-256 确认两份 XML、Activity 和 SDK JAR 没有变化。
7. 单文件对照：重新执行步骤 2，执行 `python3 switch_xml.py target-only`、`jugg deploy`，页面正常，生成的 `OtherScreenBindingImpl` 使用 AndroidX 类型。

所有 Jugg CLI 命令显式传入本工程的 `--project-dir`，所有 adb 命令使用 `-s emulator-5554`。编译和部署成功不能代替打开目标页面后的运行结果。

若处理顺序不同，须依据日志判断目标布局是否位于先处理的批次，再调整布局名称/位置和页面加载目标、重建完整基线。不要把未满足批次条件的正常结果当成此缺陷不存在。

## 失败边界与代码依据

`ResourceCompiler.doModuleCompile()`（43～63 行）按 `CompileFile.baseDir` 将资源分组，再逐组调用 `compileResSet()`；每组独立调用 `DataBindingGenBaseClassesCompiler`。

`DataBindingGenBaseClassesCompiler.doModuleCompile()` 第 51 行每次调用 `argsManager.reset()`。`DataBindingArgsManager` 的 `tempCompileDir`（第 29 行）仅按模块区分；`reset()`（140～142 行）删除该模块整个临时目录。因此第二资源批次清除了第一批的 Base Binding 和 layout-info。

本轮日志与产物相互印证：

- 两个 XML 都完成拆分、aapt2 编译，目标 stripped XML 已是 AndroidX 控件，并保留 `layout/other_screen_0` tag。
- DataBinding Mapper 阶段的 layout-info 仅剩最后一批的 `feature_screen-layout.xml`。
- Java / DEX 输出有 `FeatureScreenBinding[Impl]`，没有 `OtherScreenBinding[Impl]`；编译仍能引用完整构建留下的后者。
- 页面最终执行旧 `OtherScreenBindingImpl`，第 28 行仍为 `(repro.sdk.SdkConstraintLayout) bindings[0]`，对新的 AndroidX View 强转失败。
- 相同源码完整 Gradle 构建重新生成 `OtherScreenBindingImpl`，强转变为 `(androidx.constraintlayout.widget.ConstraintLayout) bindings[0]`，页面正常。

这将本地缺陷定位到 **资源目录分批处理与模块级 DataBinding 临时状态生命周期不一致**，无需假设 Java/Kotlin 业务代码存在显式强转。后续修复应覆盖同模块多资源目录的联合生成，避免通过修改 SDK 控件或业务代码掩盖问题。

## 有效验证记录

| 验证 | Job ID | 实际结果 |
|---|---|---|
| 正常 SDK 标签完整基线 | `5bcd9d97-3044-4716-ac9b-db647fe3cdba` | 编译、部署成功；页面正常 |
| 两个资源目录 XML 同轮修改 | `4a9da59d-e006-45cc-aaf4-429f24e4d6cd` | 增量、部署成功；22:11:14 / 22:11:17 目标控件强转崩溃 |
| 保持上述源码完整构建 | `6b3fda89-d473-4725-9653-70694e0e6f61` | 页面正常；输入 SHA-256 不变；强转更新为 AndroidX |
| 重建基线后仅修改目标 XML | `32fcc9bb-150b-473b-ac1a-a077843bbde1` | 增量、部署成功；页面正常；目标 Binding 已更新 |
| 再次重建基线、重复双目录修改 | `eb8511f8-3d80-4299-9fcd-a9b597d8665f` | 增量、部署成功；22:14:05 / 22:14:08 再现相同强转崩溃 |
| 保持第二次失败的源码完整构建 | `f6a72b04-6e78-49f1-b9d4-ae6c572a46c9` | 编译、部署成功；页面再次恢复正常 |

CLI 顶层文案个别轮次出现 `No pending file changes`，但同一 job 的 detail 明确记录了实际编译输入、生成源码、DEX 和部署内容；这里以本轮 job 明细、原始日志、设备堆栈和生成产物共同判断。

对应证据目录：`evidence/confirmed/`、`evidence/single-xml/`、`evidence/repeat/`。其中 `before-full-sha256.json` 保存首次失败后、完整构建前的输入哈希。

## 排除与范围

前一轮的“app 覆盖 SDK AAR 同名布局，SDK 预编译 ViewBinding 强转”用例在完整 Gradle 构建后也崩溃，**不符合用户明确补充的完整构建正常约束，已排除**。旧本地产物保留在 `build/reproductions/sdk-constraintlayout-cast/`，不要将其当成本次 Jugg 缺陷复现。

本轮只做复现及证据整理；使用真实 SDK JAR、AGP 生成代码、Jugg 增量部署和模拟器进行 L3 等价手工验证。没有生产代码改动，不新增假定根因的单元测试或测试 seam。修复时应按测试价值门禁，为多资源目录下完整的资源 / DataBinding 编排选择回归 owner。

上述独立工程阶段没有改动原 `android_demo_project`，仅更新任务记录。后续按用户要求迁入 demo 和测试的结果见下一节。两阶段均没有功能或架构改动，没有更改用户操作、支持范围或恢复步骤，因此不修改 AI 知识库和 Wiki。

## 仓库 demo 与回归测试

### 场景结构

- `android_demo_project/app/libs/constraint-layout-sdk.jar`：只含 SDK 控件类，没有资源、Binding 或业务代码；可重建源码和命令在同模块 `sdkfixtures/`，源码不属于 app source set。
- `app/src/main/res/layout/activity_data_binding_multi_root.xml`：`<layout>` 包裹 SDK 控件，带 `sdk_container` ID。
- `app/src/debug/res/layout/data_binding_multi_root_peer.xml`：另一资源目录的 DataBinding TextView，文字为 `baseline`。
- `MultiRootDataBindingActivity`：只调用 `DataBindingUtil.setContentView()` 并记录 `page ready`，不访问 SDK 控件。Manifest 注册独立导出页面，便于显式启动；不更改已有页面入口。

使用 main 与 debug 的既有资源目录，无需修改 Gradle 配置。最初尝试 `main/res` 与 `main/res2` 时，`FileChangesHandler.kt:323` 的字符串前缀匹配把后者归入前者，同轮仅生成一个资源批次，因此页面正常。此结果已保留，不能当成多批次故障的复现。最终 fixture 使用互无前缀关系的两个资源 root，日志确认分别处理。

### 重放命令

所有 Jugg 命令显式指定 demo 工程；执行前先通过 IDE 设备选择器选择实验模拟器，再用 `devices` 确认，避免部署到其他已连接设备。以下假定 `jugg` 已在 PATH 中，Android SDK 的 `adb` 可用：

```sh
jugg --project-dir "$PWD/android_demo_project" --console=json devices
jugg --project-dir "$PWD/android_demo_project" --console=json gradle-build
adb -s emulator-5554 shell am start -W -n com.example.myapplication/com.sickworm.jugg.demo.testcase.databinding.MultiRootDataBindingActivity
```

1. 保持提交中的 SDK 标签与 `baseline`，完整构建并打开页面，确认正常。
2. 仅修改两份 XML：目标控件标签改成 `androidx.constraintlayout.widget.ConstraintLayout`，peer 的文字改成 `changed`。Java 和 JAR 均不变。
3. 执行 `jugg --project-dir "$PWD/android_demo_project" --console=json deploy`。本次实测输入顺序为目标布局、peer，日志出现两次资源 / Binding 处理，增量编译和部署成功。
4. 再执行上述 `adb ... am start`，读取 `adb -s emulator-5554 logcat -b crash -d`，应出现下述堆栈。若资源批次顺序不同，须先检查日志中的 `res root` 与 Binding 输出，不以编译成功判定复现。
5. 保持源码不变执行 `gradle-build`，再次打开页面应正常。
6. 重放结束将两份 XML 恢复为 SDK 标签和 `baseline`，重新完整构建基线。

本次有效 demo 证据：

| 验证 | Job ID | 结果 |
|---|---|---|
| main/debug SDK 基线 | `3527f824-4161-4be1-aaf2-238e9aa21849` | 编译、部署和页面正常 |
| 仅两份 XML 的增量 | `22b391d0-2ca8-407e-a32c-4a104491575f` | 编译、部署成功；22:47:26 页面崩溃 |
| 相同源码完整构建 | `9c713a09-a378-437d-ab9a-963c4e45f360` | 22:48:30 页面正常，crash buffer 为空，四份输入 SHA-256 不变 |
| 恢复 fixture 后最终完整构建 | `c092f250-16c8-4e5e-b533-53b221864296` | 编译、部署和页面正常 |

```text
Caused by: java.lang.ClassCastException: androidx.constraintlayout.widget.ConstraintLayout
  cannot be cast to com.sickworm.jugg.demo.sdk.SdkConstraintLayout
  at com.example.myapplication.databinding.ActivityDataBindingMultiRootBindingImpl.<init>(ActivityDataBindingMultiRootBindingImpl.java:28)
  at com.example.myapplication.DataBinderMapperImpl_Inc_1.getDataBinder(DataBinderMapperImpl_Inc_1.java:104)
  at androidx.databinding.DataBindingUtil.setContentView(DataBindingUtil.java:284)
  at com.sickworm.jugg.demo.testcase.databinding.MultiRootDataBindingActivity.onCreate(MultiRootDataBindingActivity.java:14)
```

原始日志、CLI 结果、crash、BindingImpl 前后对照、输入哈希及 JUnit XML 保存在本地 `build/reproductions/demo-sdk-constraint-cast-evidence/`，不提交构建产物。`debug-root-incremental-compile.log` 显示目标与 peer 分两批，Mapper 阶段仅生成 peer Binding；`baseline-BindingImpl.java` 保留 SDK 强转，`full-BindingImpl.java` 已变为 AndroidX 强转。

### 自动化失败用例与正常路径

Owner：`main/src/test/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingResourceRootsCompileFlowTest.kt`，保护 `JuggCompiler` 的资源 / Binding 生成契约。使用真实 demo、AGP 基线和编译器，通过 `ApkParser` 检查本轮输出 DEX 中 Binding、BindingImpl 和 `sdkContainer` 字段类型。缺失生成物会让新 XML 继续使用旧 Binding，是稳定且有实际影响的可观察行为，通过测试价值门禁；没有新增生产 seam、源码字符串守卫或临时目录实现断言。该测试是编译产物回归；完整用户链路由上述模拟器 L3 等价复现覆盖。

```sh
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.databinding.DataBindingResourceRootsCompileFlowTest'
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest'
```

使用 JDK 17，并设置 `ANDROID_HOME`。新增 fixture 后必须删除 `~/.jugg/test_flag/skip_assemble` 或重建 demo，不能使用旧 APK。测试通过现有 `TestGlobal.clearBuild()` / `AssembleAndroidProjectOnce` 重建基线，结束时恢复 XML。

- `xmlTypeChangeUpdatesBinding`：只替换目标 XML，**通过**，本轮 DEX 包含新 Binding 和 BindingImpl，字段为 AndroidX。
- `xmlTypeChangeAcrossResourceRootsUpdatesBinding`：同轮修改目标和 peer，编译成功，但**预期失败**于“Updated XML must deploy its updated Binding DEX”，本轮 DEX 仅含 peer 的 Binding。两次执行均为 2 tests / 1 failure，最终执行 51 秒。
- 既有 `DeployDataGeneratorTest`：**28 tests / 0 failures / 0 errors**，耗时 2 分 47 秒。
- 测试失败保持启用，未反转断言、忽略测试或修改生产逻辑；此提交用于后续修复前的稳定复现。

本次只提交新 fixture、测试、Manifest 中新页面注册和本记录；保留 demo 原有未提交改动。结束时 XML 与完整构建已恢复正常 SDK 基线，关闭本次启动的模拟器。没有产品能力或架构变更，不涉及 AI 知识库和 Wiki 操作说明同步。

## 修复实施：模块内联合生成 Binding

### 改动边界

`ResourceCompiler.doModuleCompile()` 先展开本轮文件和目录输入，将同一模块各资源 root 的变更布局一次性交给 `DataBindingGenBaseClassesCompiler`。模块工作区仍由后者在生成前 reset，完整的 layout-info 保留到 Mapper 阶段；生成源或 trigger 只返回一次。

Binding 处理成功后，资源仍按原有规范化 root 路径分组，各自替换 stripped XML 并调用 aapt2，保留 `.flat` 输出隔离和顺序。Binding 失败时直接返回失败。没有扫描所有未变更布局，没有修改 Mapper、SDK 控件、部署逻辑或资源输入分类。

联合生成沿用原 root 批次顺序，同一相对路径仅保留最后一份布局（保留 `layout-*` 限定目录差异）。官方 `ResourceBundle` 的源布局集合按文件名和配置去重并保留首份，直接传重复布局会导致最终 XML 来自后 root、Binding 类型却来自前 root；新增回归已确认该失败，因此在输入边界统一覆盖选择。

跨模块物理路径嵌套造成工作区递归清理范围重叠，是讨论中发现、尚未完成运行复现的独立边界。本次只修复已复现的同模块多资源 root 生成遗漏，不修改工作区路径与缓存格式。

### 回归与验证安排

- 复用 `DataBindingResourceRootsCompileFlowTest` 的真实 demo / AGP / DEX 产物断言，双 root 用例同时要求两个布局的 Binding 与 BindingImpl 完整输出；补充反向输入、DataBinding 与已有普通布局混合、目录输入及同名布局覆盖的编排回归。
- 复用 `ViewBindingCompileFlowTest`、`ResourceCompileTest` 和 `DataBindingTemplatesTest`，检查普通 ViewBinding、资源 root 输出隔离与 Mapper 行为。
- `TopLevelFlowTest.testDeployDataBindingAcrossResourceRoots` 覆盖真实 baseline 安装、仅两份 XML 增量编译、部署、打开目标页面及运行时 View 类型 / crash 检查。
- 验证移至独立 worktree，使用仓库已提交的 demo 基线，避免与原工作区另一路 demo 修改及构建互相干扰；设备回归显式选择本次专用模拟器。

### 验证结果（2026-10-21）

- 先验证失败：独立工作区使用修复前的 `ResourceCompiler`，单 XML 对照通过，双 root、反向输入、混合普通布局、目录输入四项均失败，表现为目标 Binding / BindingImpl 缺失或编译失败。联合生成初版还由新增同名布局回归确认了错误字段类型（预期 AndroidX，实际 SDK），按 root 覆盖顺序去重后通过。
- 最终定向编译回归 **19 项全部通过**：`DataBindingResourceRootsCompileFlowTest` 6 项、`ViewBindingCompileFlowTest` 3 项、`DataBindingTemplatesTest` 5 项、资源编译 5 项。同模块两份布局的 Binding / BindingImpl 和字段类型均从本轮 DEX 检查，覆盖稳定可观察行为，不添加测试专用生产接口。
- L3 `TopLevelFlowTest.testDeployDataBindingAcrossResourceRoots` **1 项通过、无跳过**：专用 Android 11 模拟器上安装 SDK 基线，仅改两份 XML，增量编译并兼容部署后打开 RecyclerView 页面，View dump 包含 `ConstraintLayout`、不含 `SdkConstraintLayout`，目标启动时间窗无 `ClassCastException`。最终编译和运行使用已提交的 RecyclerView fixture（`5e0ac4a5f`）。
- 设备验证限制：测试使用的旧版 Android installer 在 Android 15 与 Android 11 的原生部署路径都出现资源未生效，页面仍显示 SDK 控件；生成和部署日志已确认新的 AndroidX Binding DEX 存在。这不作为本次修复成功证据，也不修改部署生产逻辑。L3 测试显式使用现有兼容部署路径并在结束后清理设备兼容记录。
- 旧 `ResourceCompileTest.compileResourceDirOverlay` 在修复前后均为固定数量断言失败（expected 33，actual 35，与已有 fixture 数量不符），本次保留原测试；最终资源定向回归选择 `compileSameNamedValuesFromDifferentResourceRoots`、`compileResourceDirOverlayWithOldRes`、`compileResAll`、`compileResourceOverlay`、`compileArscStopsWhenLoadTableFails` 五项，均通过。
- `:idea:compileKotlin`、两模块测试源码编译、修改文件 IDE 错误诊断和 `git diff --check` 通过。原工作区分支保持 `main`，未推送或创建 PR。

验证命令使用 Java 17、`ANDROID_HOME=/Users/wormchen/Library/Android/sdk`；所有 `:main:test` 均带上述类或方法的 `--tests` 过滤。设备回归命令：

```bash
JUGG_TEST_DEVICE_SERIAL=emulator-5560 ANDROID_SERIAL=emulator-5560 \
  ./gradlew --no-parallel :idea:test \
  --tests 'com.sickworm.intellij.jugg.manager.TopLevelFlowTest.testDeployDataBindingAcrossResourceRoots'
```

原始红绿 JUnit XML、构建日志和设备日志保存于本地忽略目录 `build/reproductions/sdk-constraintlayout-fix-evidence/`。知识库已同步模块联合生成、同名覆盖选择与 root 分批编译的职责边界；本次恢复既有布局修改能力，不改变用户入口、配置或操作步骤，不更新 Wiki。

本轮另读依据：`docs/ai_knowledge/02_compile_resource.md`、`docs/ai_knowledge/04_engineering_ide.md`；其余基础文档、DataBinding、部署、验证规范、跨模块任务记录及 dev-loop 技能参考见下列依据。

## 阅读与定位依据

- `docs/ai_knowledge/00_overview.md`
- `docs/ai_knowledge/99_index.md`
- `docs/ai_knowledge/98_code_map.md`
- `docs/ai_knowledge/09_plugin_runtime_debug.md`
- `docs/ai_knowledge/02_compile_databinding.md`
- `docs/ai_knowledge/03_deploy_core.md`
- `docs/ai_knowledge/06_testing.md`
- `docs/ai_knowledge/08_cli_tools_list.md`
- `docs/task/2026-10/databinding_plain_layout_hot_deploy_crash.md`
- `docs/task/2026-10/databinding_cross_module_include_incremental_crash.md`
- `docs/task/2026-09/024addca_android_demo_reproduction_handoff.md`（仅参考历史，历史任务限制不适用于本次复现）
- `/Users/wormchen/.codex/skills/jugg-android-dev-loop/SKILL.md`、`references/flow_compile_deploy.md`、`references/policy_incremental_compile_limits.md`
- `ResourceCompiler.kt`、`DataBindingGenBaseClassesCompiler.kt`、`DataBindingArgsManager.kt`、`DataBindingGenMapperCompiler.kt`、`LayoutIncludeAnalyzer.kt`、`DataBindingTemplates.kt`。
- demo 与测试补充：`FileChangesHandler.kt:323`、`TestGlobal.kt`、`DataBindingResourceRootsCompileFlowTest.kt`、`DeployDataGeneratorTest` 的 28 条既有回归。
- 本地复现工程、上述 job 的编译 / 部署日志、模拟器 crash、XML / BindingImpl 对照和 SHA-256。
