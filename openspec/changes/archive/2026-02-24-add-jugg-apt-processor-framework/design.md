## Context

当前源码编译链路由 `JuggCompiler` 调度，在 `source` 阶段进入 `SourceCompiler`。现有 `SourceCompiler` 主要流程是：
1. 组织 `CompileTask`
2. 处理 DataBinding mapper 生成（按触发文件判定）
3. Kotlin 编译（可带 KSP/KAPT 产出）
4. Java 编译
5. Dex / Minify

现状问题是：单文件增量编译并不可靠触发模块级注解聚合更新，特别是 `build/generated/...` 下的全局聚合文件（如 Kuikly 的 `KuiklyCoreEntry.kt`）可能保持旧状态，导致运行时缺少注册入口。该问题不是“编译器语法错误”，而是“聚合源码内容正确性”问题。

约束：
- 新方案不新增传统 apt/kapt/ksp 执行链路，仅对“已生成源码”进行增量修正。
- 需要复用现有 `CompileFile`/`CompileTask`/`SourceCompiler` 流程，避免引入新编译阶段类型。
- 处理器能力需可扩展，后续可以按业务新增注解处理规则。

## Goals / Non-Goals

**Goals:**
- 在 `SourceCompiler` 起始阶段增加 `JuggAptCompiler`（继承 `BaseCompiler`），通过一致的编译器接口先对 APT 生成源码做增量修正，再进入 Kotlin/Java 编译。
- 提供 `IJuggAptProcessor` 可扩展接口，支持多个处理器串行执行并统一返回“需要参与本轮编译的修改后源码”。
- 提供 `BaseJuggAptProcessor` 通用能力：注解命中判断（文本匹配版）、方法末尾插入模板、重复插入防重。
- 实现 Kuikly `@Page` 聚合修正处理器，确保 `triggerRegisterPages` 缺失项可自动补齐且幂等。

**Non-Goals:**
- 不重建完整 APT/KAPT/KSP 语义，不负责所有注解生态兼容。
- 不引入 PSI/AST 级别重写（首版采用文本改写策略）。
- 不处理跨模块聚合一致性，仅处理当前编译模块可定位到的生成文件。
- 不改动 `JuggCompiler` 全局阶段顺序（仍在既有 `source` 阶段内完成）。

## Decisions

### 决策 1：`JuggAptCompiler` 继承 `BaseCompiler` 并接入 `SourceCompiler` 起始位置
- **Decision**: 新增 `JuggAptCompiler : BaseCompiler`，在 `SourceCompiler.doModuleCompile` 一开始通过标准 `compile(task)` 入口执行，产出的源码再分流给 Kotlin/Java 编译任务。
- **Rationale**: 
  - 与需求“SourceCompiler 最开始流程增加 JuggAptCompiler”一致。
  - 继承 `BaseCompiler` 可以复用模块拆分、日志、取消态检查等基础能力，降低新框架接入成本。
  - 该位置同时掌握 Java/Kotlin 输入集合，最容易统一产出后续 `CompileTask`。
  - 不污染 `KotlinCompilerInvoker`/`JavaCompilerInvoker` 的职责边界。
- **Alternatives considered**:
  - 放在 `JuggCompiler` 层：会增加模块级拆分与类型分发复杂度。
  - 放在 `KotlinCompiler` 或 `JavaCompiler` 内：会造成语言偏置，且双端共享逻辑难复用。

### 决策 2：定义统一处理器契约 `IJuggAptProcessor`，`process` 显式接收 `ICompileContext`
- **Decision**: 处理器接口统一为 `process(context, module, allCompileFiles, generatedAptFiles): ProcessorResult`，其中 input 至少包含：
  - `ICompileContext`（用于读取模块、路径、构建目录、日志上下文等）
  - 本轮编译文件（全部 `CompileFile`）
  - 当前模块信息
  - 已发现的 APT 生成源码（`.kt`/`.java`）
- **Rationale**:
  - 满足“输入所有文件，输出需要编译的修改后的 APT 源码”的目标。
  - 处理器无需重复构建上下文依赖，减少隐式全局变量和硬编码路径。
  - 便于多处理器并行演进，单处理器只关注业务规则。
- **Alternatives considered**:
  - 每个处理器自行扫描磁盘：重复 IO 且行为不一致。
  - 仅输入变更文件：无法处理“全局聚合文件”场景。

### 决策 3：引入 `BaseJuggAptProcessor`，先采用文本规则引擎而非 AST
- **Decision**: `BaseJuggAptProcessor` 提供可复用工具：
  - 注解/关键字匹配
  - 方法体边界定位（按方法名 + 大括号平衡）
  - 片段插入与重复检测
- **Rationale**:
  - 研发成本低，适合首版快速覆盖业务痛点。
  - 复用逻辑减少各处理器重复实现。
- **Alternatives considered**:
  - Kotlin/Java AST 解析：准确度高但依赖重、性能和维护成本高。
  - 各处理器手写字符串逻辑：维护分散、质量不可控。

### 决策 4：`JuggAptCompiler` 统一做源码发现、处理器编排、去重聚合
- **Decision**: `JuggAptCompiler` 维护处理器列表并串行执行，最终输出去重后的 `CompileFile` 集合；同一路径只保留最后一次修改版本。
- **Rationale**:
  - 集中编排便于控制执行顺序与冲突策略。
  - 可以统一记录日志与耗时，便于定位问题。
- **Alternatives considered**:
  - 处理器互相调用：耦合高，顺序不可控。
  - 分散在 `SourceCompiler` 手动拼接：后续扩展成本高。

### 决策 5：Kuikly `@Page` 首版按“定位聚合文件 + 幂等补齐注册片段”实现
- **Decision**: 新增 Kuikly 处理器，逻辑为：
  1. 根据本轮编译文件筛选包含 `@Page` 的候选页面源码（文本匹配，风格参考 `KotlinCompiler#analyzeSource` 的轻量文本扫描策略）。
  2. 定位已生成聚合文件（优先模块 `build/generated/ksp/<variant>/kotlin/KuiklyCoreEntry.kt`，并兼容 Jugg 临时生成目录）。
  3. 在 `triggerRegisterPages` 中检测是否已存在该页面注册（按 router/class token 双重防重）。该方法无重载/多定义，按唯一目标处理。
  4. 缺失则在方法末尾插入模板：
     `BridgeManager.registerPageRouter("<route>") { <FQCN>() }`
  5. 首版同时支持 Kotlin/Java 聚合文件改写；Java 路径先落地能力并保留 `TODO`，暂不增加专项测试。
  6. 将被修改的聚合文件按真实类型（`CompileFile.Type.Kotlin` 或 `CompileFile.Type.Java`）返回。
- **Rationale**:
  - 直击当前业务问题，且可以保证多次编译结果幂等。
- **Alternatives considered**:
  - 每次全量重建聚合文件：代价高且依赖完整注解扫描。
  - 继续依赖传统 KSP 触发：在单文件增量链路下不稳定。

### 决策 6：失败策略采用“处理器级告警 + 框架级 fail-open”
- **Decision**:
  - 当处理器判定应处理但改写失败（文件不存在/方法定位失败/写回异常）时，仅记录 `logger.warn`，并跳过该处理器的输出，不阻塞后续编译流程。
  - 当处理器判定不适用（未命中注解/聚合文件不存在）时，返回 no-op，不影响编译。
- **Rationale**:
  - 注解聚合改动频率低，主编译链路是高频且已充分验证流程，优先保证可用性与稳定性。
  - 以告警暴露风险，避免因为处理器缺陷放大为整体编译失败。
- **Alternatives considered**:
  - 处理器级 fail-fast：错误可见性高，但会阻塞主流程，收益不及风险。

## Risks / Trade-offs

- [文本匹配误判] 代码格式、注释或字符串字面量可能触发误命中 → 通过“注解 token + 类名 token + 方法体边界校验”降低误判，并在后续迭代引入可选 AST 实现。
- [插入点定位失败] `triggerRegisterPages` 签名变化可能导致插入失败 → 记录 `logger.warn` 并输出目标文件和方法名，继续后续编译；通过日志巡检与回归样例兜底。
- [多处理器冲突] 多个处理器修改同一文件可能产生覆盖顺序问题 → 统一由 `JuggAptCompiler` 管理顺序，并对同路径采用“后处理器覆盖前处理器结果”且记录冲突日志。
- [性能开销] 扫描 generated 目录可能增加 IO 成本 → 首版限制扫描范围（模块 `build/generated` + 受支持后缀），后续可加增量缓存。
- [行为差异] 与 Gradle 全量构建的聚合时机仍可能不同 → 文档明确该能力是“增量修正”而非“完整注解处理替代”。

## Migration Plan

1. 新增框架骨架（`JuggAptCompiler : BaseCompiler`、`IJuggAptProcessor`、`BaseJuggAptProcessor`），默认仅注册空列表，确保对现有编译行为无影响。
2. 在 `SourceCompiler` 起始阶段接入 `JuggAptCompiler`，将返回的 Kotlin/Java 源码分别并入 Kotlin/Java 编译输入集合。
3. 增加 Kuikly `@Page` 处理器并注册到默认列表，开启日志与耗时打点。
4. 增加最小验证：
   - 单页面增量编译后，`KuiklyCoreEntry.kt` 新增注册片段
   - 二次编译不重复插入（幂等）
   - 未命中 `@Page` 时无改写
   - Java 聚合改写能力先落地并保留 `TODO`，暂不增加专项测试
5. 回滚策略：若出现兼容性问题，仅需从注册列表移除 Kuikly 处理器（或临时跳过 `JuggAptCompiler` 调用），不影响其他编译阶段。

## Open Questions

- 当前无阻塞性开放问题。
- 已确认项：
  - `@Page` 路由提取首版采用轻量文本实现，参考 `KotlinCompiler#analyzeSource` 的扫描方式。
  - `triggerRegisterPages` 视为单一权威方法（无重载/多定义）。
  - 首版同时支持 Java/Kotlin 聚合改写，Java 测试后补（`TODO`）。
  - 处理器失败策略固定为 `logger.warn` + 不阻塞主流程。
