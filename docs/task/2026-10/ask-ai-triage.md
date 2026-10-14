**Purpose:** Track Agent investigation and Issue handoff guidance.

**Decision:** Use version-matched existing evidence and concise conclusions.

**Impact:** Keeps external Android investigations read-only.

# ASK AI 调查分流与支持边界

## 问题与失败证据

2026-10-14：用户指出提示词及 Local Agent Investigation Guide 缺少 Jugg 背景，简单编译失败仍需长时间调查，已知不支持场景的信息不明确。修改前的中英文模板没有介绍 Gradle 基线与旁路增量关系，并要求所有问题先完整读知识库、核对源码版本；指南以 AGENTS/专题阅读开场，交付要求统一包含 behavior owner、竞争解释与反证，没有普通错误或已知边界的停止条件。

当前仓库 `compile_latest.log` 只显示插件初始化与 MCP 状态查询，不包含用户报告的失败现场，因此不能据此声称复现了具体调查耗时。模板和指南本身是本次缺失行为的直接证据。

## 调整

- 中英文提示词与指南先介绍 Jugg、Gradle 基线及 ASK AI 的职责。
- 先读 manifest/task-output，再定位同一任务的原始错误块与上下文，确认增量、Gradle、部署或 runtime 阶段。
- 普通语法或资源格式错误有充分直接证据时，给最小建议并停止；不先 clone、检索历史或读 APK/数据库。缺符号、生成物/classpath/增量状态和 compiler internal error 不能仅凭表面错误快速归因。
- 已知边界必须匹配版本、实际改动、触发条件和支持依据。指南提供注解器、KMP/Compose 资源与 Gradle 回退页面入口，避免复制易过时的支持矩阵。区分不支持、缺少前提、明确失败、忽略旧产物、自动回退和用户手动刷新；已知限制不默认建议 Bug Issue。
- 疑似 Jugg 问题或无法判定时才执行仓库指导、知识库和对应版本源码调查。已有只读边界、按需专用 clone/fetch 授权与 Issue 最终由用户提交的约束保持有效。
- 同步 IDE 知识库；编译失败 Wiki 原先声明 Agent 总会读源码，更新中英文镜像为先现场分流、按需深入调查。入口、采集与部署流程没有改变。

## 验证策略

本次只修改调查文本，没有新增诊断分类器或改变 compile/deploy 编排。Agent 的调查质量和耗时不能通过文案 contains 断言稳定证明，因此不新增锁定措辞的测试。复用 `InvestigationSnapshotBuilderTest`（L1）验证真实生成 prompt 的语言、插值、保存一致性与既有调查/Issue 契约；通过案例走查验证调查规则，执行 Wiki 镜像、链接与 production build，检查资源处理后的模板及 diff。

案例走查范围：明确语法错误可直接停止；缺 R/生成类必须进入区分；已核实的 Hilt 图更新限制给 Gradle 恢复而非 Bug；Hilt 普通方法修改不能判为不支持；未知 `unsupported`、版本不明、编译摘要缺原始错误不能快速定论；已自动回退成功不再报告为最终失败；runtime crash 不从本地产物推断设备已加载。

## 完成结果

- JDK 17 定向 `:main:test --tests 'com.sickworm.intellij.jugg.diagnostics.InvestigationSnapshotBuilderTest'` 通过，8 项，零失败/错误/跳过。没有新增锁定调查措辞的自动化测试。
- 对照上述案例逐项走查：直接语法错误不进入源码调查；缺 R/生成类和未知限制进入深入调查；已知限制须核对操作和版本，忽略改动不能伪造拒绝日志；自动回退与手动恢复分开表述；未验证建议与设备加载状态不声明成功。两种语言的流程、停止条件和只读约束一致。
- 构建处理后的两份 prompt 资源与源码字节一致；指南中的仓库 Raw 链接在本地均可解析。
- Wiki 镜像/链接检查、production `npm run build` 通过；中英文编译失败 HTML 均包含新的先现场分流说明。`git diff --check` 通过。
- 真实用户 Agent 耗时与端到端回答效果需要在新模板发给实际 Agent 后验证，本次不宣称已取得耗时改善数据。旧 `prompt.txt` 是现场副本，不自动重写；使用新模板需要更新插件后重新生成 prompt。
- 修改开始时 `main` 有弹窗计数调整等四个未提交文件；期间这些改动已独立提交为 `c269252b6`。本次仅提交调查分流文件，不重写或合并先前提交。
