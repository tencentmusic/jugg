# Issue #80：合并项目信息时采用 Gradle 路径

## 问题与证据

Panda 现场 IDE 快照把多个模块根目录读为 main。文件检测仍将 Welcome.java 归到 main，但 BaseCompiler 按相同目录分组后选择 dep，javac 使用 dep 的 sourcepath，导致 MainMessage 无法解析。同源码 Gradle 构建成功，core 改名对照增量编译成功。现场资料：`/Users/wormchen/Downloads/issue80-demos-and-investigation-2026-11-08/`。

## 范围与实现

- 匹配到非 Unknown 的 Gradle module 时，moduleRootDir、projectRootDir、buildPathInfo 使用 Gradle 值，buildPathInfo 保留现有 merged variant。
- manifestFile 使用 Gradle 非空值，否则保留 IDE 值。
- 缺少 Gradle module、Unknown、关闭 Gradle 读取以及 Application 特殊冲突分支维持原有流程。
- 不修改 AS API 读取、BaseCompiler 分组或 CompileContextManager 历史基线处理；AS 模型读取由其他工作单独推进。

## classpath 边界

Merger 只接收 IDE/Gradle 原始快照。ClasspathBackupHelper 将输出副本的 buildPathInfo 映射到备份目录后，由 CompileContextDb 保存。CompileContextManager 在合并后应用自定义配置，再由 buildEffectiveModules 优先恢复全量记录中的路径。因此 merger 可使用 Gradle buildPathInfo，无需保留 IDE 根目录保护 classpath。错误的历史全量路径不由本补丁自动修复。

## 测试价值与验证

路径来源优先级决定真实源码、Manifest 和编译输出归属，是稳定且可自动判定的合并契约。复用 L1 owner JuggProjectInfoMergerAndroidTestTest，新增较新 IDE 快照错误路径纠正、Gradle Manifest 缺失回退、Unknown/缺失模块保持路径的回归；无测试专用生产 seam。

- 修改前：定向执行 17 项 merger 测试，新增路径优先级用例失败，实际仍为 /wrong-project；其余 16 项通过。
- 修改后：`./gradlew --offline :main:test --tests "com.sickworm.intellij.jugg.project.merger.JuggProjectInfoMergerAndroidTestTest"`，17 项全部通过。
- 保留全量 classpath 路径：`ANDROID_HOME=/Users/wormchen/Library/Android/sdk ./gradlew --offline :idea:test --tests "com.sickworm.intellij.jugg.project.CompileContextManagerBuildPathInfoTest"`，1 项通过，同时完成相关 IDE 编译。首次运行因未设置 ANDROID_HOME 初始化失败，指定现存 SDK 后通过。
- `git diff --check` 通过。在线配置曾被 IntelliJ 插件 GitHub 版本查询阻断，改用离线模式；main 测试输出目录曾出现缺失 class 的缓存错误，删除该测试生成目录及对应增量缓存后恢复。
- 真正 Panda 单文件增量 demo 与错误历史基线修复未在本轮执行。

## 文档

同步 04_engineering_project.md 的路径合并契约与 classpath 生命周期。未改变用户操作入口、支持范围或恢复步骤，不更新 Wiki。
