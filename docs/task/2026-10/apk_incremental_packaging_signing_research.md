# AGP 与 Jugg 的 APK 增量打包、签名调研

## 状态与范围

- 调研日期：2026-10-25。
- 状态：完成源码调研，未实施优化，未进行同包性能测量。
- 问题：用户观察到 Gradle + AGP 在最后生成 APK 并签名时似乎比 Jugg 更快，确认是否存在专门的增量实现。
- 范围：仅比较已有编译产物之后的 APK 条目更新、对齐、签名与校验，不比较源码编译、资源编译、DEX 生成、Gradle 配置或设备安装耗时。
- Jugg 基线：`78cbfb25e1f831456a594dd73d68fb42b33994db`。
- 外部基线：调研当日读取 AOSP `tools/base` 的 `mirror-goog-studio-main`、`tools/apksig` 的 `main` 和 OpenJDK `jdk17u` 的 `master`。下文链接指向可变分支，复现时应记录实际提交及目标 AGP/JDK 版本；不能据此断言所有已发布 AGP 版本行为相同。

## 核心结论

AGP 的 APK 打包器具备专门的 ZIP 局部更新能力，并将对齐与签名集成到库调用中。Jugg 当前默认流程包含整包副本、ZIP 重写、独立对齐、独立签名和独立验签。因此，“大 APK 只改少量内容时，AGP 最后打包签名更快”有源码层面的解释，但尚未证明本次用户观察的实际耗时差由哪一步主导，也没有量化收益。

必须区分增量打包与增量签名：V1 存在条目摘要复用；本次检查的 V2/V3 调用链仍计算完整受保护区域的摘要，未发现跨构建复用未变化分块摘要的路径。

## 1. AGP 的最终打包流程

```text
输入产物的新增、修改、删除集合
  -> IncrementalPackager
  -> ApkFlinger
  -> Zipflinger 更新 ZIP 条目，写入时完成对齐
  -> SignedApk / apksig 生成签名元数据与签名块
```

### 1.1 ZIP 条目局部更新

`IncrementalPackager.updateFiles()` 汇总各类条目的变化，先删除被移除或替换的条目，再添加新增或变化的条目。`ApkFlinger` 通过 `ZipArchive` / `SignedApk` 操作已有 APK。

Zipflinger 通过中央目录识别条目位置，并用 `FreeStore` 管理 APK 内可用空间：

- 未变化条目的内容通常保留在原位置，无需重新输出整个归档。
- 删除条目释放空间；新增条目复用空位或追加，并更新中央目录。
- 必要的空洞可用虚拟 ZIP 条目填充，不能把它理解为简单删除文件中间的一段字节。
- 从其他 ZIP 导入条目时，在不需要改变压缩方式等条件下，可以直接传输压缩数据，避免解压再压缩；Zipflinger 对符合条件的传输使用 zero-copy。
- 需要压缩的普通文件可并行准备；具体收益取决于实际变化的条目。

这里的增量单位是打包条目及其对应的归档空间，不是对变化 DEX 内部进行字节差分。

### 1.2 对齐集成在写入阶段

`ApkFlinger.writeFile()` 和 `writeZip()` 根据条目的压缩方式与类型设置 alignment：普通未压缩条目使用 4 字节对齐，需直接加载的未压缩 native library 按配置页大小对齐。

因此，这条打包路径不需要完成 ZIP 后再运行独立 `zipalign` 重写一个 APK。具体 native library 页大小与目标 AGP 版本、配置有关，不把当前开发分支的默认值推广到所有工程。

## 2. 签名能复用什么

### 2.1 V1：可复用未变化条目的摘要

`ApkFlinger` 在增量构建时设置 `setV1TrustManifest(creationData.isIncremental)`。`SignedApk.initWithV1()` 读取旧 `META-INF/MANIFEST.MF`，通过签名引擎恢复已知条目的摘要状态。条目新增、修改、删除时通知签名引擎，完成时重新生成所需签名元数据。

该复用依赖可信的构建产物和准确的变化集合，不能直接推广为信任任意外部 APK 的旧摘要。

### 2.2 V2 / V3：仍有完整受保护区域的摘要开销

本次核对的 `SignedApk.finishV2andV3()` 在 ZIP 更新完成后获取各区域位置，`v2andV3Sign()` 将签名块之前的内容、中央目录和 EOCD 交给 `signer.outputZipSections()`。

apksig 的摘要计算遍历传入区域的分块；支持的摘要路径可通过 executor 并行处理。当前 `SignedApk` 还包含特定平台和大小条件下的内存映射读取优化，但这些实现细节需按实际版本核对。

结论边界：

- 分块哈希和并行计算不等于跨构建缓存分块摘要。
- 未在本次调用链中发现只重算变化分块并复用上一轮其他分块摘要的实现。
- Zipflinger README 提及写入位置跟踪可支持增量 V2 签名，这一设计描述本身不足以证明当前 AGP 已接入该能力，应以实际签名调用链为准。
- APK 内容变化后仍需生成匹配当前内容的新签名块，不能直接保留旧 V2/V3 签名。
- V4 不是本次性能结论的依据；若目标构建启用 V4，后续测量需单独记录 `.idsig` 生成成本。

### 2.3 库内签名减少进程开销

AGP 通过 `SignedApk` 调用 apksig，签名在打包流程所在 JVM 中完成，不需要每次为 `apksigner sign` 启动独立 JVM。本次检查的收尾路径也没有 Jugg 那样独立执行的 `apksigner verify` 命令；这不代表整个 AGP 构建链没有其他校验。

## 3. Jugg 当前流程与开销

行为 owner 为 [ApkFileModifier.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/apk/ApkFileModifier.kt)，入口为 `insertAndResign()`：

```text
复制整个 APK 到同目录 working 临时文件
  -> updateFiles()
  -> zipalign -f 4，输出另一个临时 APK
  -> apksigner sign 或自定义签名脚本
  -> apksigner verify
  -> 原子替换原 APK（不支持原子移动时普通替换）
```

### 3.1 ZipFS 的条目 API 不代表局部磁盘写入

在 JVM 14+ 且插入项全部为内存数据时，Jugg 使用 `FileSystems.newFileSystem()` 更新 ZIP，并对新增内容使用 `STORED`。

OpenJDK 17u 的 `ZipFileSystem.sync()` 在有修改时创建临时 ZIP，遍历条目，将未变化条目的原始数据复制到新文件，写出中央目录后替换旧 ZIP。因此，通常无需重新压缩未变化条目，但仍有整包重写。其他 JDK/JBR 版本需要对应核对。

### 3.2 流式分支的额外成本

JVM 低于 14，或存在 file-backed 插入项时，`updateFiles()` 使用 `insertFileUnderJvm14()`。这条路径通过 `ZipInputStream` 读取旧条目并通过 `ZipOutputStream` 写出新归档；未变化的压缩条目也经过解压、重压缩。该分支与普通 ZipFS 分支应分开测量，不能混为同一种成本。

### 3.3 其他阶段

| 阶段 | 当前实现及影响 |
| --- | --- |
| 工作副本 | 首先复制整个 APK，保护失败时的原始产物 |
| ZIP 更新 | ZipFS 分支整包重写；流式分支还可能重新压缩未变化条目 |
| 对齐 | 启动独立 `zipalign`，输出另一个 APK |
| 默认签名 | 启动 `apksigner sign`，包含独立 JVM 启动及签名处理成本 |
| 自定义签名 | 使用项目脚本，耗时与语义由脚本决定，不能套用默认签名路径的结论 |
| 验签 | 另行启动 `apksigner verify`，增加 JVM 启动及内容读取、摘要验证成本 |
| 发布 | 全部成功后替换原 APK，保留现有失败保护契约 |

已有计时标签包括 `insertAndResign`、`insertFiles`、`alignApk`、`signApk`、`verifyApk` 和 `replaceApk`。最初的工作副本复制在总计时内，但不属于 `insertFiles`，只相加各子阶段可能遗漏该部分。

## 4. 后续候选方向与验证

以下是待评估方向，不是已经落地的实现方案。

优先评估以 Zipflinger / Signflinger 替换现有 ZIP 更新、独立对齐和默认签名组合。可以继续在工作副本上操作，以保留失败不破坏原 APK 的契约；即使保留一次整包复制，也有机会减少后续重写和进程启动。独立验签是否调整需单独评估，不能为了性能直接移除现有发布门禁。

实施前应确认实际 AGP/JDK 版本、依赖引入与宿主兼容性、所需签名方案和对齐配置，并保持自定义签名脚本入口、大文件处理及失败清理语义。

建议的最小测量与正确性验证：

1. 固定同一基线 APK、证书、签名方案、压缩与对齐要求，准备相同变化输入；每轮从相同副本开始，避免前轮修改污染对比。
2. 分别测量小 DEX 替换、资源条目替换，以及触发 file-backed 分支的大型 native library 替换；记录 APK 大小和变化条目大小。
3. 区分无变更跳过与实际发生变化的打包。AGP 记录最终 package 阶段，Jugg 记录复制、更新、对齐、签名、验签和总耗时，排除前置编译和安装。
4. 记录宿主、JDK、AGP、SDK Build Tools、磁盘和缓存状态，多轮采样报告中位数与波动；区分冷启动与预热状态。
5. 校验变化条目内容、未变化条目内容、压缩方式、对齐与证书/签名有效性；在目标设备验证安装结果。不能只以两个 APK 的整体字节相等作为判据。
6. 若进入实现，验证中断或失败时原 APK 保留、自定义签名路径及正常默认路径；依据 `06_testing.md` 选择有价值的自动化断言和既有回归 owner。

当前没有性能样本，不能承诺“快多少倍”，也不能将 ZIP 局部更新的耗时当成完整打包签名耗时。

## 5. 依据与本次验证记录

### 仓库资料

- [00_overview.md](../../ai_knowledge/00_overview.md)
- [99_index.md](../../ai_knowledge/99_index.md)
- [98_code_map.md](../../ai_knowledge/98_code_map.md)
- [05_utilities.md](../../ai_knowledge/05_utilities.md)
- [自定义 APK 签名脚本方案](../2026-09/custom_apk_sign_script_design.md)
- [ApkFileModifier.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/apk/ApkFileModifier.kt)：`insertAndResign`、`updateFiles`、两条 ZIP 更新分支、`alignApk`、`resignApk`、`verifyApk`。

### 外部一手资料

- [IncrementalPackager.java](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/build-system/builder/src/main/java/com/android/builder/internal/packaging/IncrementalPackager.java)：变化集合与打包调用。
- [ApkFlinger.kt](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/build-system/builder/src/main/java/com/android/builder/internal/packaging/ApkFlinger.kt)：ZIP、对齐与签名集成。
- [Zipflinger README](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/zipflinger/README.md)：空间管理、条目搬运和设计边界。
- [SignedApk.java](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/signflinger/src/com/android/signflinger/SignedApk.java)：V1 恢复和 V2/V3 签名调用。
- [ApkSigningBlockUtils.java](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/internal/apk/ApkSigningBlockUtils.java)：内容摘要计算。
- [OpenJDK 17u ZipFileSystem.java](https://github.com/openjdk/jdk17u/blob/master/src/jdk.zipfs/share/classes/jdk/nio/zipfs/ZipFileSystem.java)：`sync()` 与 `copyLOCEntry()`。

本次验证采用源码交叉核对和文档链接、差异检查；纯调研记录不新增自动化测试，不运行构建或设备操作。未改变功能、架构或用户操作说明，无需同步知识库和 Wiki。
