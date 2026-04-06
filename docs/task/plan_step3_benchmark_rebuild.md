# Step 3：Benchmark 重建

> 方向文档，不含具体实现方案。执行前需与 agent 重新讨论实现细节。

## 目标

从零构建可重现的 4 级 Benchmark，用于量化评估新三工具模型的 LLM 执行成功率。

## 方向

### 测试中心

以 `android_demo_project` 中的 `McpTestActivity` 为固定测试页面，所有测试用例均基于该页面的固定 widget 布局编写。

### 四级结构

| 级别 | 名称 | 用例数方向 | 目标 |
|------|------|-----------|------|
| L1 | Smoke | ~5 | 工具可调通、基本返回正确 |
| L2 | Unit | ~30（每工具 ~10） | 单工具各场景覆盖 |
| L3 | Integration | ~15 | 多工具组合、真实验证流程 |
| L4 | Adversarial | ~10 | 边界输入、错误处理、LLM 抗干扰 |

### 用例格式（Prescriptive）

每条用例必须包含：
- 预置条件（设备状态、页面位置）
- 输入（LLM 收到的指令）
- 期望调用序列（精确到工具名和关键参数）
- 期望输出行为（通过/失败判定标准）
- 评分 rubric（满分 5 分，按步骤拆分）

旧格式（描述性、无调用序列）全部废弃。

### 覆盖维度

- `view_locate`：文本匹配、resourceId 匹配、多候选、不存在元素
- `figma_layout_verify`：正常验证、dpr 不匹配告警、部分节点无法匹配
- `view_inspect`：基础 getter、链式表达式、自定义 KuiklyView getter
- 集成：页面导航 Gate + 验证 + 结果判定完整流程

## 验收方向

Benchmark 可被独立 agent 复现执行，每次产出量化评分，可横向对比不同 Skill 版本。
