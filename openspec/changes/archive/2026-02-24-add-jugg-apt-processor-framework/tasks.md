## 1. APT 编译框架骨架

- [x] 1.1 新增 `JuggAptCompiler` 并继承 `BaseCompiler`，完成类注释与基础结构（`supportedTypes`、`doModuleCompile`）
- [x] 1.2 设计 `IJuggAptProcessor` 接口，定义 `process(context, module, allCompileFiles, generatedAptFiles)` 契约与结果模型
- [x] 1.3 新增 `BaseJuggAptProcessor`，实现通用文本能力：注解命中、方法末尾插入、重复片段检测
- [x] 1.4 在 `JuggAptCompiler` 中实现处理器注册与顺序执行，确保输出按文件路径可去重

## 2. SourceCompiler 流程接入

- [x] 2.1 在 `SourceCompiler.doModuleCompile` 起始阶段接入 `JuggAptCompiler.compile(task)`
- [x] 2.2 将 `JuggAptCompiler` 产出按类型并入 Kotlin/Java 编译输入，保证同一轮编译生效
- [x] 2.3 确认接入后不改变既有 DataBinding、Dex、Minify 执行顺序
- [x] 2.4 处理取消态与空输入场景，确保与 `BaseCompiler` 生命周期行为一致

## 3. Kuikly @Page 增量聚合处理器

- [x] 3.1 实现 Kuikly 处理器，按轻量文本扫描识别 `@Page` 候选源码并提取 route/class 信息（参考 `KotlinCompiler#analyzeSource` 风格）
- [x] 3.2 实现聚合入口发现逻辑，覆盖 `build/generated/ksp/<variant>/kotlin/KuiklyCoreEntry.kt` 与编译上下文等价目录
- [x] 3.3 在 `triggerRegisterPages` 内实现注册片段追加逻辑：缺失时追加、已存在时跳过
- [x] 3.4 支持 Kotlin/Java 聚合文件改写输出；为 Java 路径标记测试 TODO（本次不新增专项测试）

## 4. 失败策略与日志

- [x] 4.1 将处理器执行异常统一为 `logger.warn`，不中断后续处理器与下游 Kotlin/Java 编译
- [x] 4.2 为关键分支补充日志：处理器命中、目标文件定位、插入成功/跳过、异常上下文
- [x] 4.3 验证 fail-open 行为：单处理器失败时主流程继续且可得到编译结果

## 5. 验证与收尾

- [x] 5.1 增加/补充单元或集成验证：新增页面时可自动补齐 `triggerRegisterPages` 注册片段
- [x] 5.2 验证幂等性：重复编译不会重复插入同一 route/class 注册代码
- [x] 5.3 验证未命中 `@Page` 时无额外改写，且多模块场景不跨模块污染
- [x] 5.4 更新必要文档（至少 `docs/ai_knowledge/98_code_map.md` 与相关编译专题）并标注新增类与入口
