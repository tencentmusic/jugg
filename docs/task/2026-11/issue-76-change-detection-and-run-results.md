# Issue #76：CLI 编译与部署信息修正

## 证据与原因

Issue：https://github.com/tencentmusic/jugg/issues/76；报告 f4e60a85，3.5.5-release。
11 月 3 日 13:23 实际日志为 nothing to deploy、deployedFiles: 0，MCP 却返回 Compiled files (total: 6)。原实现将执行前的候选列表当作实际编译结果。

## 实施范围

- compile 消息使用本轮实际成功编译输入；deploy 显示 Deployed files (total: N)，使用本轮成功提交的新快照，包括此前已编译、本次才部署的文件。零文件时保留既有历史提示。
- 复用 LastChangedDeployRegistry，比较本次执行前后快照，防止历史部署误计入本次。
- MCP action 显式选择既有字段构造 JSON；RunResult 内部文件列表只用于消息，不包含序列化注解。不新增公开字段、查询 schema 或异步状态透传；原有成功字段保留流程成功语义。
- 消息在任务结束后生成，同步与异步轮询一致。同步 MCP/CLI 知识库和 CLI 中英文参考。

## 验证

- `CompileAndDeployMcpToolActionTest` 23 项通过：实际编译/部署消息、无变更、同步/异步以及既有 JSON 字段集合、字段值、null 省略。
- `JuggRunningTaskTest` 21 项通过：真实编译部署、空操作、编译成功部署失败、此前编译本轮部署、旧部署不误计入本次。
- Wiki 校验、production build、HTML 消息说明和定向 diff 检查通过。
- 完整虚拟设备 Flow 未进入用例：补齐 ANDROID_HOME 后，demo assembleDebug 在 D8 3.3.83 中发生 NullPointerException。此验证限制保持记录，未调整 demo 或 D8。

## 范围审查

只保留准确消息所需的内部信息、协议边界映射、行为测试和直接相关文档；多仓库识别修复另行提交。
本次拆分仅调整 Git 历史和任务方案，生产代码及测试内容与已验证版本一致，保留后续控制面板提交及已有未提交改动。
