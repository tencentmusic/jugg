# Panda 模块目录读取修复

## 问题与证据

Panda 4 2025.3.4 Patch 1（AI-253.32098.37.2534.15336583）在 issue80 demo 的 2026-11-09 13:00:00 日志中，将 app.main、dep.main、buildSrc.main 的 DSL 根目录全部解析为 main；dep 项目模块还返回未解析的 settingsDir/libs/dep。相同 16 个模块的 External System project path 全部正确。

## 方案

仅修改 Panda：反射调用 ExternalSystemApiUtil.getExternalProjectPath，非空绝对路径优先使用；读取失败或结果不可用时保留既有 DSL 和 content roots / module file 回退。真实 IDE 验证完成后移除全部临时探针日志，仅保留 API 失败时的 debug 降级原因。其余 AS、merge 和 BaseCompiler 不变。

## 验证

这是 IDE 外部模型读取适配，mock 固定 API 返回只能重复实现，无法保护真实 source-set 解析行为，因此不新增自动化测试或生产测试 seam。使用已保存的真实错误日志作为失败证据，定向编译和插件打包验证接线，由用户在同一 Panda 工程验证目录和增量行为。

用户重跑：安装插件包并重启 Panda，Gradle Sync 后核对 app.main→app、dep.main→libs/dep、buildSrc.main→buildSrc；确认 buildSrc 不再进入业务模块集合。建立 Jugg Gradle 基线，编辑 Welcome 方法体并保存，再运行 Jugg compile，核对实际模块 main 和增量成功；core 对照仍应成功。API 不可用时沿原流程回退，需保留对应 debug 原因。

## 结果

`./gradlew :idea:compileKotlin :idea:buildPlugin` 通过，包含第三方发行合规检查。`git diff --check` 通过，已检查新增日志格式。真实 Panda 已确认模块快照路径正确、buildSrc 被排除。`compile_2026-11-09_13-29-26.0.log` 显示 Welcome.java 实际交给 main 编译，sourcepath 为 main，生成 Welcome.class，JuggCompiler.isAllSuccess=true，最终 Jugg HOT_FIX SUCCESSFUL。本次修复后的 core 对照未重跑。临时日志移除后重新编译和打包确认。

已同步兼容层知识库。用户操作、公开支持范围不变，无需修改 Wiki。
