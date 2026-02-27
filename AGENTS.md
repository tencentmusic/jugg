# Jugg AI 知识库

## ⚠️ AI 必读：强制工作流

在回答任何项目问题前，必须执行以下流程。

### 1) 新会话首次必读

- [00_overview.md](docs/ai_knowledge/00_overview.md)
- [97_ai_usage.md](docs/ai_knowledge/97_ai_usage.md)

### 2) 按任务深挖（按需）

- 先查路径/类名： [98_code_map.md](docs/ai_knowledge/98_code_map.md)
- 需要总导航： [99_index.md](docs/ai_knowledge/99_index.md)
- 再按 `97_ai_usage.md` 的“推荐检索顺序”展开单个专题文档（禁止一次性全量加载）

### 3) 响应用户（必须包含）

1. 已读取的文档列表（文件名）
2. 答案依据的小节定位（如：`98_code_map.md` 某节）

### 4) 文档更新（按需）

如果本次任务涉及功能或架构改动，同步更新 [ai_knowledge](docs/ai_knowledge) 文档

## 📋 质量门槛（回答前自检）

1. 不能只读本页 README 就回答。
2. 不能猜测类路径或接口能力，必须有文档或代码依据。
3. 若文档与代码冲突，明确写出“以代码为准”。
4. 引用必须带文件名，尽量带章节。
