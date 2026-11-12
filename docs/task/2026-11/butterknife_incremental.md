# ButterKnife 增量适配

## 已确认范围
- 复用 IJuggAptProcessor 和 SourceCompiler；未声明 ButterKnife compiler 的模块直接返回，不读取源码。
- 本轮文本筛选后才做语法确认、类型读取和处理器执行；支持 Java 字段、方法注解与 Kotlin alias/use-site。
- 调用项目官方 ButterKnife processor，只输入本轮相关文件及必要的绑定后代，不全局收集业务源码。
- 复用已有绑定和 class 基线寻找继承影响；本模块后代一并处理。跨模块继承影响若不能沿既有流程可靠提交，明确失败而非静默使用旧生成物。
- R/R2 由官方符号转换；已有 R2 复用，新增符号局部补齐，保留占位值唯一性。
- Java APT 独立 javac，KAPT 显式使用现有隔离 JVM。正常编译链不受影响。
- 删除最后绑定、移除旧生成类暂明确失败，复用用户主动 Gradle 操作；新增绑定建议重启以清除运行时查找缓存。

## 变更清单
- 新增 ButterKnifeJuggAptProcessor 及其输入/生成辅助代码；ProcessorRegistration 注册。
- AnnotationProcessorRunner 增加按需隔离 KAPT 选项，默认原行为。
- 新增真实 processor 产物测试，保护快速跳过、字段/监听器、R2、继承和嵌套绑定删除；使用现有 SourceCompiler 做编译链验证。
- android_demo_project 新增可访问的 Java/Kotlin 绑定场景，声明 processor 依赖。
- 同步源码编译知识库和注解处理器能力 Wiki 中英文页面。

## 验证
先运行缺失能力的失败测试，再实施；运行定向测试、插件编译和 demo assemble/生成物检查。设备端 E2E 由用户执行。

## 执行记录
- 失败证据：注册链真实 processor 测试缺少 Screen_ViewBinding；实现后 JDK 17 暴露 javac.util.Name 访问错误，定向补充 exports/opens 后通过。
- 快速路径：未声明 compiler 的模块直接返回；有 compiler 的模块，普通文本或 @Override 不触发 AST/classpath/processor。仅直接引用标准包和 import alias/use-site；跨文件 typealias 未扩展。
- 验证：ButterKnife 13 项真实 processor、R2、继承、隔离 KAPT 和 SourceCompiler → DEX 定向测试通过；DeployDataGeneratorTest 29 项、ARouter KAPT 1 项、JuggCompilerTest 2 项（ButterKnife 注解修改 + 普通 Java 增量）通过，无跳过。
- demo：Java 继承资源字段、Java 字段/监听器、Kotlin alias/use-site/监听器均由完整 assembleDebug 生成；最终 --rerun-tasks 重建基线成功，:idea:buildPlugin 打包通过。首页 ButterKnife Demo 为手工入口。
- 文档：同步源码编译知识库、Code Map、注解能力 Wiki 中英文；Wiki 镜像/链接校验与 production build 通过。
- 审查：只读审查发现嵌套类最后绑定删除漏检；补充失败回归，并让旧绑定门禁识别官方二进制嵌套类名，复审通过。
- 未验证：设备安装、页面点击、热部署效果由用户 E2E 验证。首次新增 binding 需重启，最后绑定删除及跨模块绑定继承影响明确失败后由用户主动 Gradle 刷新。

## 处理器输出优化
- 独立 Java APT 添加 `-Xlint:-options`，关闭 source/target 引导类路径兼容提示。
- Java APT/KAPT 仅保留官方 `butterknife.minSdk` 与 `butterknife.debuggable` 参数，避免传入 ARouter 等其他处理器选项。
- 参考 ARouter，在真实 processor 开始和 finally 结束时打印 info 日志，耗时使用单调时钟及固定两位秒数；快速跳过路径保持静默。
- 验证 owner 为现有真实 processor / SourceCompiler 测试；参数控制生成行为补充回归，日志采用实际测试输出检查，不新增文案断言。
- 结果：14 项 ButterKnife 定向测试（含真实 Java/KAPT 与 SourceCompiler → DEX）通过，无失败或跳过；`:idea:compileKotlin` 通过；输出中两类已报告警告消失，开始/结束日志成对出现。
