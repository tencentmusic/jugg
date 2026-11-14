# Kotlin companion 常量变化漏跟编修复

## 调查范围与依据

用户要求在 report `ce89601b` 的 Java 接口隐式常量修复后，继续检查 Kotlin 是否有类似问题，沿用 TDD 流程。

已读取 `00_overview.md`、`99_index.md`、`98_code_map.md`、`09_plugin_runtime_debug.md`、`03_deploy_const_ref.md`、`06_testing.md`；检查 Wiki 的 `concepts/incremental-compile/const-ref.md` 和 `capabilities/compile/recompile-propagation.md` 两个中文页面。

Kotlin 使用显式 `const val` 收录定义，不存在 Java 那种隐式 `final` 检查问题。进一步检查 `KotlinConstParser`、`ConstRefModels` 和 SQLite 影响查询后发现：

1. 命名 companion 的常量只以外层类为 owner，引用候选 `Commands.Codes.CMD` 无法匹配外层 owner；限定引用和别名导入均漏跟编。
2. 解析跳过 companion 的递归，仅单独处理其直接属性，导致内部嵌套 object 的常量完全缺失。

使用本地 Kotlin 2.0.21 编译器编译最小 fixture，确认 `Commands.CMD`、`Commands.Codes.CMD`、别名导入、默认及命名 companion 内嵌套 object 的限定引用均合法。`javap -c -constants` 确认使用方静态初始化保存字面量，确有跟编需求。

## 最小修改

- 递归进入 companion，复用原有定义解析和嵌套 class/object 遍历。
- companion 的直接常量继续保留外层 owner；命名 companion 额外存一条 source owner 别名定义，沿用现有 definition key、SQLite 和 diff 流程，不增加数据实体或公共参数。
- 默认 companion 的直接常量保持既有索引形式；private 过滤保持不变。
- 缓存版本由 8 更新为 9，清除旧解析结果及其 mtime/checksum 复用记录。
- 不改变编译、部署编排及异常降级行为。

## 测试价值与 TDD

漏掉引用方会使 App 保留旧内联值；受影响源码集合可确定性断言，且无需测试专用生产接口，满足测试价值门禁。

- `ConstRefIntegrationTest`（L2）：真实 parser、engine、SQLite，验证命名 companion 的直接引用、外层简写和别名导入；验证默认及命名 companion 内嵌套 object 的直接引用和别名导入。每个场景同时断言只引用未变常量的文件不跟编。
- `ConstRefCacheDatabaseTest`（L1）：版本 7、8 的旧分析与 mtime/checksum 缓存均失效；保留正常数据库回归。
- 复用 `KotlinConstParserTest`、`JavaConstParserTest`、`ConstRefEngineTest`，保护既有 Kotlin 常量、Java 接口修复及 engine 行为。

### Red

生产代码修改前执行 Kotlin parser、integration、database 三个定向测试类，41 项中 4 项失败：

- 命名 companion 预期命中三个使用方，实际仅命中外层简写使用方。
- 默认 companion 内嵌套 object 预期命中两个使用方，实际为空。
- 版本 8 缓存没有失效。
- 新缓存版本预期 9，实际 8。

其余 37 项通过。失败 XML 已保存于本地临时目录。

### Green 与回归

```bash
./gradlew :main:test \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.KotlinConstParserTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.JavaConstParserTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefIntegrationTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefCacheDatabaseTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefEngineTest' \
  --console=plain
```

93 项全部通过，无失败、错误或跳过：Kotlin parser 8 项、Java parser 8 项、integration 10 项、database 23 项、engine 44 项。默认及命名 companion 的嵌套 object 场景均执行通过；未变常量使用方未进入跟编集合。未改动 compile/deploy 编排，不新增 L3 Flow 测试。

`git diff --check` 通过；未新增或修改日志调用。

## 文档与验证边界

已同步 ConstRef 知识库的 owner 规则、嵌套递归、缓存版本、排查入口和测试落点。Wiki 已承诺 Kotlin companion 与嵌套 class/object 常量跟编，本次修复兑现既有能力，用户入口和使用边界不变，无需修改 Wiki。

本次使用最小源码、真实解析器/SQLite 和 Kotlin 字节码验证，未在用户完整 Android 工程或设备上执行编译部署。
