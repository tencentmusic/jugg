# inclink 专题事实审核

> Purpose: Audit the native inclink topic against commits, current code, and maintainer statements.
> Decision: Retain attributable facts and remove author-added policies, speculative guidance, and future work plans.
> Impact: Documentation only; no native source or packaged binary changes.

## 审核范围与结论

用户要求委派 subagent 审核 `docs/ai/02_compile_aapt2_inclink.md`，区分自行扩展与提交依据，最终只保留事实。Subagent 独立检查源码、历史提交及访谈信息，主 Agent 核查并修改。以下原行号均指 `8e031edc3` 中的专题版本。

## 依据分类

| 保留事实 | 依据 |
|---|---|
| 导入提交记载的 AOSP 基线 | `6361ca80c`：`a707013b78cea3586fdadf9a2f04932e823d7504`；未做外部上游逐文件比对 |
| Android 14 AOSP 构建兼容配置 | `b5b59b2ac`，不作为重新导入上游的证据 |
| baseline 合入 final_table_、当前文件输出范围 | `84ee7999a`、`a5fbab9eb`、`72d36996f` |
| R 输出的 entry-count 条件及强制生成开关 | `5aa0b8bf8`、`94ba66c8c` |
| styleable 导入、public 可见性及属性命名空间修复 | `c655e3cfd`、`7a5dc3273`、`1d0fd7c3c`、`c85dde759` |
| feature split 与 Manifest 增量编译 | `fb05994ae`、`35a087668` |
| 资源名、属性符号及值引用反混淆 | `911da7b12`、`caa40509f`、`93314bfef` |
| layout-v22 旧内容和重复生成修复 | `4fcc2f32c`、`72d36996f` |
| 缓存、daemon 协议、上下文与 options 更新、失败恢复、映射 key 范围 | 当前 `Main.cpp`、`cmd/IncLink.cpp/h`、`cmd/Link.cpp`、`ArscCompiler.kt`；作为源码行为保留，不称为历史已复现故障 |
| 本仓库作为修改入口、AOSP 脚本或链接同步、构建延后 | 本轮维护者明确说明 |
| APK 加载约快 4–5 倍、直接读取最终表以保留 ID | 本轮维护者说明；倍数为历史测量，未记录测量条件 |
| 关注小场景兼容对主场景耗时及整体设计的影响 | 本轮维护者说明；不再扩展为具体架构禁令或测试政策 |

## 删除、压缩与纠正

| 原位置与内容 | 处理及去向 |
|---|---|
| §1 的固定架构边界要求、强制性能举证要求 | 删除作者新增的规范；保留维护者原始关注。现有验证政策仍在 `06_testing.md` |
| §2、§4、§5 的 investigate / preserve / do not / must account 等行动要求 | 删除自行推导的维护指令；同段源码事实及核心入口保留 |
| §4.1 feature 修改可能破坏引用的假设 | 删除假设；保留实际包名读写行为及 `fb05994ae` |
| §4.2 “Otherwise the previous Manifest is used” | 纠正：输入流报错才跳过替换；XML 解析或处理失败直接返回错误 |
| §3 表、Manifest、符号源笼统“累积” | 改为跨轮保留；区分表合并与 Manifest 替换 |
| §4.3 reload 的泛化风险与 direct client 要求 | 删除扩展；保留实际 delete / assign 控制流及 Jugg fresh-daemon 行为 |
| §5.3 “Treat these as one cross-file contract” | 删除作者制定的规范；实现关系和历史修复保留 |
| §6 “下一步检查什么”、假设性连续失败场景及结论前检查清单 | 改为精确诊断生产者和触发条件；Manifest、R、v22、恢复事实已在对应机制章节保留。Jugg 诊断及日志入口保留到 `02_compile_resource.md`、`09_plugin_runtime_debug.md` 的引用 |
| §7 本任务执行情况、专门覆盖不存在的泛化提示 | 执行情况移至本记录；现有测试文件导航保留，不推断其覆盖范围 |
| §7 未来测试矩阵、正常路径与性能举证要求 | 删除自行规划；保留 `06_testing.md` 引用 |
| §8 后续构建必做清单 | 删除自行展开的待办；保留用户要求的占位及现有预编译工具边界 |

## 验证

纯文档修改，不新增自动化行为测试。核对提交、关键代码分支、专题引用和现有测试路径；运行 `git diff --check`。本轮未构建 AAPT2、未复现历史故障。专题导航路径未变化；未更改产品行为或 Wiki 内容。
