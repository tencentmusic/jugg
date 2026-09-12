# 修改布局后增量编译失败：离线证据采集清单

> 本清单配合[主排查指南](layout_incremental_compile_failure_offline_investigation.md)使用。它只定义现场采集动作，不重复诊断流程。

## 1. 初始化稳定别名

在离线环境中先填写真实路径。`<EVIDENCE_ROOT>` 必须位于工程目录之外，并且不可复用现有目录。

```bash
PROJECT_ROOT='<PROJECT_ROOT>'
EVIDENCE_ROOT='<EVIDENCE_ROOT>'
LAYOUT_RELATIVE_PATH='<MODULE_RELATIVE_LAYOUT_PATH>'
mkdir -p "$EVIDENCE_ROOT/log" "$EVIDENCE_ROOT/project" "$EVIDENCE_ROOT/jugg" "$EVIDENCE_ROOT/artifacts" "$EVIDENCE_ROOT/device"
```

- [ ] 已确认 `PROJECT_ROOT` 是发生问题的 Android 工程，不是 Jugg 源码工程或另一个同名 checkout。
- [ ] 已记录 `<MODULE_A>`、`<VARIANT_A>`、`<APK_A>`、`<APP_ID>`、`<DEVICE_A>` 等稳定别名。
- [ ] 已记录用户在失败前做过的重试、重启、Gradle build/clean、清缓存、重装和清数据。

## 2. 保存项目状态与布局输入

```bash
git -C "$PROJECT_ROOT" rev-parse HEAD > "$EVIDENCE_ROOT/project/git_head.txt"
git -C "$PROJECT_ROOT" branch --show-current > "$EVIDENCE_ROOT/project/git_branch.txt"
git -C "$PROJECT_ROOT" status --short > "$EVIDENCE_ROOT/project/git_status.txt"
git -C "$PROJECT_ROOT" diff -- "$LAYOUT_RELATIVE_PATH" > "$EVIDENCE_ROOT/project/layout.diff"
if [ -f "$PROJECT_ROOT/$LAYOUT_RELATIVE_PATH" ]; then
  cp -p "$PROJECT_ROOT/$LAYOUT_RELATIVE_PATH" "$EVIDENCE_ROOT/project/layout.xml"
  shasum -a 256 "$EVIDENCE_ROOT/project/layout.xml" > "$EVIDENCE_ROOT/project/layout.sha256"
fi
```

- [ ] 已记录模块、variant、Run Configuration、local/remote 执行方式和最近一次完整构建命令。
- [ ] 已区分 layout 是新增、修改、重命名还是删除。
- [ ] 若文件已删除，已从 Git、IDE local history 或其它本地保全材料恢复“失败时输入”；无法恢复则标记未收集。

## 3. 保存全部 Jugg 历史日志与状态

```bash
cp -R "$PROJECT_ROOT/build/jugg/log" "$EVIDENCE_ROOT/log/original"
rg --files "$EVIDENCE_ROOT/log/original" | sort > "$EVIDENCE_ROOT/log/files.txt"
while IFS= read -r file; do
  wc -c "$file"
  shasum -a 256 "$file"
done < "$EVIDENCE_ROOT/log/files.txt" > "$EVIDENCE_ROOT/log/metadata_and_sha256.txt"
```

- [ ] 已确认保存的是全部 `compile_*.log`，没有只复制 `compile_latest.log`。
- [ ] 已标记空文件、轮转、截断、时间断层和缺失区间。
- [ ] 已保存 `build/jugg/database/` 和 `build/jugg/build/staging/` 的副本；敏感文件不能复制时已记录大小、修改时间和 hash。
- [ ] 已保存相关 APK、R.jar、DataBinding layout info、stripped XML、生成源码或对应校验和。

建议先复制整个目录，后续只分析由首个分歧指向的文件：

```bash
cp -R "$PROJECT_ROOT/build/jugg/database" "$EVIDENCE_ROOT/jugg/database"
cp -R "$PROJECT_ROOT/build/jugg/build/staging" "$EVIDENCE_ROOT/jugg/staging"
```

目录不存在时记录“确认不存在”；没有权限读取时记录“不可访问”，不要把两者混写。

## 4. 记录版本边界

- [ ] Jugg version、compile timestamp、release build id。
- [ ] Android Studio 完整版本和 build number。
- [ ] IDE JBR/JDK 版本。
- [ ] Gradle、AGP、Kotlin、compileSdk、buildTools/AAPT2 版本。
- [ ] 目标 Android API、ABI；纯编译失败时设备信息可降为辅助项。
- [ ] 本地版本匹配 Jugg 源码的 tag/commit/来源；没有匹配源码时明确记录。

版本信息优先从保全日志和现有构建输出读取。不得为了收集版本触发新的 Sync、依赖下载或远程访问。

## 5. 生成历史日志索引

```bash
rg -n 'Jugg compile started|Compile files:|resource: \[|Processing view binding|Compile DataBinding failed|process view binding failed|aapt2 daemon command|aapt2 compile failed|loadTable failed|aapt2 invoke failed|aapt2 link failed|Compile finished|incremental compile error|SEVERE' "$EVIDENCE_ROOT/log/original" > "$EVIDENCE_ROOT/log/layout_compile_index.txt"
```

- [ ] 已定位最后正常运行。
- [ ] 已定位首次分歧运行，包括最终成功但阶段/输入已变化的运行。
- [ ] 已定位最终失败运行。
- [ ] 已保存三个运行从 `Jugg compile started` 到最终状态的完整上下文。
- [ ] 已记录第一个异常的时间、`[ClassName]`、phase、输入、前一正常阶段和后一失败阶段。

## 6. 项目结构证据

```bash
rg --files "$PROJECT_ROOT" -g 'settings.gradle*' -g 'build.gradle*' -g 'gradle.properties' -g 'libs.versions.toml' | sort > "$EVIDENCE_ROOT/project/build_files.txt"
while IFS= read -r file; do
  rg -n 'includeBuild|include\(|com.android.application|com.android.library|com.android.dynamic-feature|namespace|applicationId|dynamicFeatures|sourceSets|res.srcDirs|buildFeatures|dataBinding|viewBinding' "$file"
done < "$EVIDENCE_ROOT/project/build_files.txt" > "$EVIDENCE_ROOT/project/topology_index.txt"
```

- [ ] 已建立 `layout -> resource root -> module -> variant -> APK owner -> baseline -> staging` 映射。
- [ ] 已检查 application、library、dynamic feature、included build 和 test APK 关系。
- [ ] 已检查同名 layout、多 `res.srcDirs`、local AAR/JAR 和重复依赖来源。
- [ ] 已确认 Jugg project info 与本次完整构建/Run Configuration 属于同一 variant。

若 `build_files.txt` 为空，先核对 `PROJECT_ROOT`；不得把路径错误解释为工程没有构建配置。

## 7. 首个分歧产物

只勾选命中的分支：

- [ ] 文件识别：保存 project info、compile context、失败 layout 路径映射。
- [ ] DataBinding/ViewBinding：保存 layout info、stripped XML、base class、trigger、mapper/BR/BindingImpl。
- [ ] AAPT2 compile：保存实际输入 XML、完整命令、errorOutput、flat 输出目录。
- [ ] load/link：保存基线 APK、已部署 arsc/manifest、styleables、ResGuard mapping、当前 flat。
- [ ] 生成源码：保存 `R.java`、Binding 源、相关 class/R.jar 和源码错误上下文。
- [ ] 部署/运行：仅在编译成功后保存 staging、overlay、已安装 APK 和设备加载状态。

所有保全产物记录大小、修改时间和 SHA-256。不可解析的二进制仍应保存 hash，不能写成“内容正常”。

## 8. 状态变更门禁

执行任何实验前确认：

- [ ] 原始日志、数据库、staging、APK 和失败 layout 已保全。
- [ ] 本次实验只改变一个条件，并能区分至少两个假设。
- [ ] 已定义预期观察、停止条件和失败后保留项。
- [ ] 没有执行全工程 clean、删除整个 `build/jugg`、清 IDE 缓存、重装或清数据。
- [ ] 已知可恢复重试最多一次；第二次仍失败时停止并保留异常。

## 9. 输出前脱敏

- [ ] 绝对路径替换为 `<PROJECT_ROOT>`、`<EVIDENCE_ROOT>` 等稳定别名。
- [ ] 内部域名/IP、账号、设备 ID、业务包名替换为稳定别名。
- [ ] token、secret、cookie、个人信息、业务数据和无关源码正文已移除。
- [ ] 只保留支持结论的最小日志上下文。
- [ ] Jugg/Gradle/AGP/JDK/Android 版本、method descriptor、phase、相对时间和 checksum 已保留。
- [ ] 无法自动判断的内容已列入“提交者人工复核”。
