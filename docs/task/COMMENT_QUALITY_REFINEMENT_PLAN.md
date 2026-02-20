# 注释质量治理执行方案（面向 a38a75b9 后续收敛）

> 适用范围：`main/src/main/java/com/sickworm/intellij/jugg/**`  
> 背景提交：`a38a75b9`（注释补齐）  
> 约束：若文档与代码冲突，以代码为准。

## 1. 目标与非目标

### 1.1 目标
- 保留“可快速理解职责”的注释价值，去掉模板化冗余。
- 消除注释与声明错配（类名/角色对不上的情况）。
- 让 `Role / Collaboration / Data Contract` 从“全量铺设”变为“按类型分层使用”。
- 建立可重复执行的自动检查命令和验收门槛。

### 1.2 非目标
- 不改业务逻辑，不改接口行为。
- 不追求每个类都必须有三段式注释。
- 不做英文文案润色工程（只做信息密度与准确性治理）。

## 2. 当前基线（已确认）

### 2.1 覆盖度
- 变更文件：`166`（`kt/java`）
- `Role` 行数：`308`
- `Collaboration` 行数：`308`
- `Data Contract` 行数：`308`

### 2.2 已发现硬错误（必须先修）
1. `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt:207`  
   注释写 `ClassDeployItem`，实际声明为 `DeployItem`（`.../JuggDeployData.kt:211`）。
2. `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/RsyncCommand.kt:108`  
   注释写 `RsyncFetchChangedLibraryCommand`，实际声明为 `RsyncFetchClasspathCommand`（`.../RsyncCommand.kt:112`）。

### 2.3 模板化占比（本轮治理核心问题）
- 模板化 `Collaboration`：`274 / 308`
- 模板化 `Data Contract`：`274 / 308`
- 模板化 `Role`（带模块口号式短语）：`97`
- `data class`：`105`，其中带完整三段式 `101`
- `private class`：`35`，其中带完整三段式 `34`

## 3. 质量原则（强约束）

1. **准确性优先**  
   注释不能与类名、职责、调用关系冲突。
2. **信息密度优先**  
   不写“Public members define contract”这类空泛句。
3. **按类型分层**  
   越靠近编排边界/稳定协议，越需要完整注释；越内部临时，越应简短。
4. **可验证**  
   Data Contract 只写“可从代码验证”的约束（默认值、空值、状态转换、失败条件、边界值）。

## 4. 分层注释策略（执行标准）

## 4.1 L1：核心编排/边界类（保留三段式）
- 适用：`JuggCompiler`、`DeployDataDatabase`、`McpToolInvoker`、`Local/RemoteGradleCompileClient`、`BaseCompileContext` 等。
- 要求：
  - `Role`：说明“负责什么 + 在流程中的位置”。
  - `Collaboration`：列出 2~4 个真实上下游（类/关键方法）。
  - `Data Contract`：列出输入/输出或状态约束，不得空话。

## 4.2 L2：普通业务类/工具类（默认两段式）
- 适用：`*Helper`、`*Manager`、`*Invoker`、`*Modifier` 等非核心编排但有独立行为的类。
- 要求：
  - 必须有 `Role`。
  - `Collaboration` 或 `Data Contract` 二选一，按需补充。
  - 若两段都写不出信息增量，则只保留 `Role`。

## 4.3 L3：数据载体与内部类型（默认一段式）
- 适用：`data class`、`enum class`、`private/internal class`、DTO、局部中间结构。
- 要求：
  - 默认仅 `Role` 一行。
  - 仅当存在关键约束时追加 `Data Contract`（例如字段互斥、空值语义、单位约定）。
  - 一律不写模板化 `Collaboration`。

## 5. 三段字段写法规范

## 5.1 Role（必须）
- 推荐句式：`Role: <类名> + 核心职责 + 边界/场景。`
- 禁止：
  - 仅复述类名（如 `X handles X operations`）。
  - 只写模块口号（如 `in the incremental compilation pipeline`）但无职责信息。

## 5.2 Collaboration（按需）
- 只写真实协作，至少出现 1 个“具体上游/下游类或方法”。
- 禁止：
  - `Used by ... flows through [ClassName]` 模板句。
  - 无具体对象的流程口号描述。

## 5.3 Data Contract（按需）
- 只写可验证约束，例如：
  - 字段默认值/空值语义。
  - 成功失败判定条件。
  - 状态机转换条件。
  - 边界值或格式要求。
- 禁止：
  - `Constructor properties are the payload contract ...`
  - `Public members define usage contract ...`
  - `Implementations must satisfy ...`（除非接口文档确实在定义行为契约，且写出具体行为）

## 6. 自动检查与门禁命令

> 说明：以下命令默认在仓库根目录执行。

### 6.1 检查注释类名与声明名错配（阻断级）
```bash
rg --files main/src/main/java/com/sickworm/intellij/jugg | while read -r f; do
  perl -ne '
    if (/Role:\s*([A-Z][A-Za-z0-9_]*)/) { $role=$1; $line=$.; }
    if (/^\s*(?:public\s+|private\s+|protected\s+|internal\s+|open\s+|abstract\s+|sealed\s+|data\s+)*(?:enum\s+class|class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)/) {
      $decl=$1;
      if (defined $role && defined $line && $. - $line <= 8 && $role ne $decl) {
        print "$ARGV:$line role=$role decl=$decl\n";
      }
      undef $role; undef $line;
    }
  ' "$f"
done | sort
```

### 6.2 检查模板化 Collaboration / Data Contract（收敛指标）
```bash
rg -n '\* Collaboration: Used by .* flows through' main/src/main/java/com/sickworm/intellij/jugg | wc -l
rg -n '\* Data Contract: (Public members of|Public functions and constants on|Constructor properties are the payload contract|Implementations must satisfy|Each enum entry represents)' main/src/main/java/com/sickworm/intellij/jugg | wc -l
```

### 6.3 检查模板化 Role（收敛指标）
```bash
rg -n '\* Role: .*in the incremental compilation pipeline|\* Role: .*in the deploy and runtime patch pipeline|\* Role: .*in Gradle and remote build orchestration|\* Role: .*in the MCP tool runtime|\* Role: .*for project model resolution' main/src/main/java/com/sickworm/intellij/jugg | wc -l
```

### 6.4 编译回归检查（提交前）
```bash
./gradlew :main:compileKotlin --no-daemon
```

## 7. 执行阶段（新会话按此顺序）

## 7.1 Phase 0：冻结基线
- 记录当前三类计数（6.2、6.3）。
- 记录错配清单（6.1）。

## 7.2 Phase 1：先修阻断项
- 修复第 2.2 节两处硬错误。
- 复跑 6.1，确保错配为 `0`。

## 7.3 Phase 2：结构性瘦身（先 L3 后 L2）
- 先处理 `data class`、`private/internal` 类：
  - 默认降到一段 `Role`，必要时保留 `Data Contract`。
  - 删除模板化 `Collaboration`。
- 再处理 L2 类：
  - 删除无信息增量的模板段落，保留职责信息。

## 7.4 Phase 3：L1 定点增强
- 对核心入口类进行人工精修（小批次）。
- 要求写出真实上下游、边界条件和失败判定。

## 7.5 Phase 4：回归与验收
- 跑 6.1、6.2、6.3、6.4。
- 生成结果摘要（变更文件数、模板计数变化、编译结果）。

## 8. 验收标准（建议）

### 8.1 必须满足
- 注释错配（6.1）=`0`
- 编译通过（6.4）

### 8.2 建议目标
- 模板化 `Collaboration`：`<= 20`
- 模板化 `Data Contract`：`<= 20`
- 模板化 `Role`：`<= 15`
- `private/internal/data class` 的三段式占比显著下降（目标 < 30%）

## 9. 提交建议

- 建议拆为 3 个提交，便于回滚与评审：
1. `fix(comments): correct role/decl mismatches`
2. `refactor(comments): simplify data/internal class comments`
3. `docs(comments): enrich orchestration-layer contracts`

---

## 附：优先处理模块顺序（按收益）
1. `compiler`
2. `deploy`
3. `mcp`
4. `gradle`
5. `project`

