# Jugg AI 知识库 - 使用协议

> **项目**: Android 增量编译与热部署插件  
> **版本**: 2.6.13 | **文档版本**: v1.0 | **更新**: 2026-01-27  
> **代码覆盖**: 236/236 核心文件 (100%)

---

## ⚠️ AI 必读：强制工作流

**在回答任何问题前,你必须按以下步骤执行:**

### 步骤 1: 意图识别 (必须完成)
根据用户问题,确定查询类型并**立即读取**对应文档:

```
IF 用户问题包含 "在哪" / "找不到" / "类名" / "路径" 
   → 立即执行: read_file("docs/ai_knowledge/98_code_map.md")
   
IF 用户问题包含 "怎么做" / "如何实现" / "步骤" / "教程"
   → 立即执行: read_file("docs/ai_knowledge/07_cookbook.md")
   
IF 用户问题包含 "架构" / "设计" / "为什么" / "原理"
   → 立即执行: read_file("docs/ai_knowledge/00_overview.md")
   → 然后执行: read_file("docs/ai_knowledge/01_architecture.md")
   
IF 用户问题包含具体功能关键词 (如 "增量编译" / "热部署" / "JVMTI")
   → 立即执行: read_file("docs/ai_knowledge/99_index.md")
   → 根据索引结果读取对应专题文档
   
IF 以上都不匹配
   → 默认执行: read_file("docs/ai_knowledge/98_code_map.md")
```

### 步骤 2: 深度查询 (按需执行)
如果步骤 1 的文档未完全解答问题,根据提示读取专题文档:

| 触发关键词 | 必读文档 | 补充文档 |
|-----------|---------|---------|
| Java/Kotlin/Dex 编译 | `02_compile_source.md` | `02_compile_core.md` |
| 资源编译/AAPT2 | `02_compile_resource.md` | `02_compile_core.md` |
| DataBinding/ViewBinding | `02_compile_databinding.md` | - |
| Manifest/混淆 | `02_compile_manifest_obfuscation.md` | - |
| 自定义编译器/UI | `02_compile_custom_ui.md` | `02_compile_core.md` |
| 热部署/JVMTI/Overlay | `03_deploy_core.md` | `03_runtime_jvmti.md` |
| 完整部署流程 | `03_deploy_complete.md` | `03_deploy_core.md` |
| Gradle/依赖解析 | `04_engineering_project.md` | - |
| IDE 插件/UI | `04_engineering_ide.md` | - |
| 版本兼容/CI/命令行 | `04_engineering_compat.md` | - |
| APK/Git/Logger/RPC | `05_utilities.md` | - |
| 历史演进/技术债 | `06_evolution.md` | - |

### 步骤 3: 响应用户 (必须包含)
在回答中**明确说明**:
1. ✅ 已读取的文档列表
2. 📍 答案来源的具体章节 (如 "根据 98_code_map.md § 2.1 编译系统")
3. 🔗 相关文档链接 (如需深入了解)

---

## 📚 文档索引 (仅供参考)

<details>
<summary>点击展开完整文档列表</summary>

### 核心导航
- `98_code_map.md` - 模块/类/路径速查表
- `99_index.md` - 功能/关键词总索引
- `07_cookbook.md` - 常见任务操作手册

### 架构设计
- `00_overview.md` - 项目概览
- `01_architecture.md` - 架构设计

### 编译系统 (02_compile_*)
- `02_compile_core.md` - 编译器架构与增量策略
- `02_compile_source.md` - Java/Kotlin/Dex 编译
- `02_compile_resource.md` - AAPT2 资源编译
- `02_compile_databinding.md` - DataBinding/ViewBinding
- `02_compile_manifest_obfuscation.md` - Manifest 与混淆
- `02_compile_custom_ui.md` - 自定义编译器与 UI

### 部署与运行时 (03_*)
- `03_deploy_core.md` - JVMTI/Overlay 热部署
- `03_deploy_data_generator.md` - 增量影响分析与类结构变更检测
- `03_deploy_complete.md` - 完整部署流程
- `03_runtime_jvmti.md` - JVMTI Agent 实现

### 工程化 (04_engineering_*)
- `04_engineering_project.md` - Gradle 集成与依赖解析
- `04_engineering_ide.md` - IDE 插件层
- `04_engineering_compat.md` - 版本兼容/命令行/CI

### 辅助模块
- `05_utilities.md` - APK/Git/Logger/RPC
- `06_evolution.md` - 技术演进历史

</details>

---

## 🎯 典型场景示例

### 场景 1: 用户问 "ResourceCompiler 在哪个包?"
```
1. 识别为 "查找类/路径" 类型
2. 执行 read_file("docs/ai_knowledge/98_code_map.md")
3. 在 § 2.2 资源编译 中找到答案
4. 回答: "根据 98_code_map.md § 2.2,ResourceCompiler 位于 
   io.github.lizhangqu.plugin.compiler.resource 包"
```

### 场景 2: 用户问 "如何新增一个编译器?"
```
1. 识别为 "操作步骤" 类型
2. 执行 read_file("docs/ai_knowledge/07_cookbook.md")
3. 在 § 1.1 新增编译器 中找到步骤
4. 如需深入,补充读取 02_compile_core.md
5. 回答: "根据 07_cookbook.md § 1.1,需要以下 4 步..."
```

### 场景 3: 用户问 "增量编译的原理是什么?"
```
1. 识别为 "架构/原理" 类型
2. 执行 read_file("docs/ai_knowledge/00_overview.md")
3. 执行 read_file("docs/ai_knowledge/01_architecture.md")
4. 补充读取 02_compile_core.md § 2 增量策略
5. 回答: "根据 01_architecture.md § 3.2 和 02_compile_core.md § 2..."
```

---

## 🚫 禁止行为

1. ❌ **禁止直接回答** 而不读取文档
2. ❌ **禁止仅读取 README.md** 就停止
3. ❌ **禁止猜测** 文档内容或代码位置
4. ✅ **必须明确引用** 文档章节和路径

---

## 📝 版本历史

- **v1.0** (2026-01-27): 初始版本,强制工作流设计
