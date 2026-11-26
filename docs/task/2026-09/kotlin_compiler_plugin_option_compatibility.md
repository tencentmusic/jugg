# Kotlin compiler plugin 参数兼容修复

## 1. 背景

Jugg 增量 Kotlin 编译会加载项目 compiler plugin。此前只传递 `-Xplugin`，没有回放 Kotlin Gradle Plugin 为具体 compilation 解析出的 subplugin 参数，因此 MoshiX 等声明 required option 的插件会报错：

```text
required plugin option not present: dev.zacsweers.moshix.compiler:enabled
```

首轮修复已读取 `KotlinCompilerPluginData.options.arguments` 并转换为 Kotlin CLI `-P` 参数。进一步审查确认需要限制参数作用域并提供兼容降级，避免父子 compilation 参数混合或插件版本不匹配导致原本可编译的场景失败。

## 2. 已确认行为

- Kotlin CLI 对未加载 plugin ID 的 `-P` 参数直接忽略；Kotlin 1.9.23、2.2.10 实测编译成功。
- plugin 已加载但 option 未声明时，Kotlin compiler 返回 `unsupported plugin option` 并终止编译。
- compiler plugin option 可以声明 `allowMultipleOccurrences`，因此不能按 option 名全局去重。
- `getParentModules(module, true)` 按当前模块、最近父模块的顺序返回，适合为 KMP synthetic module 提供缺失回退。

## 3. 方案

### 3.1 compilation 级参数选择

- 当前模块存在 `kotlinPluginOptions` 时，以当前模块为唯一来源。
- 当前模块为空时，使用第一个具有参数的父模块。
- 保留 Gradle 参数的原始顺序和重复项，不合并多个模块的 option 列表。

### 3.2 参数注入边界

- 仅在当前编译加载项目 compiler plugin 时加入项目 `-P` 参数。
- 不预加载 `CommandLineProcessor` 做 option 白名单检查，避免额外的插件类加载和 Kotlin 版本兼容问题。
- 不硬编码 MoshiX 或其他插件参数。

### 3.3 unsupported option 降级

当编译错误明确为 `unsupported plugin option`，且该参数来自 Gradle-resolved `kotlinPluginOptions`：

1. 提取对应 plugin ID。
2. 仅移除该 plugin ID 的 Gradle-resolved 参数，保留 plugin JAR、其他插件参数和 `kotlinFreeCompilerArgs`。
3. 最多重试一次，恢复到增加参数回放能力之前的行为。
4. 仅在降级编译成功后，按 compiler toolchain、plugin ID 和原参数列表记录本次兼容结果，避免后续增量编译重复失败；toolchain 或参数变化后重新尝试。
5. 降级仍失败时保留最终异常，不继续扩大禁用范围。

required option 缺失仍沿用既有的单插件禁用回退。单次编译共享一次自动重试预算。

## 4. 非目标

- 不为 compiler plugin 建立静态白名单或配置中心。
- 不修改用户显式配置的 `kotlinFreeCompilerArgs`。
- 不改变 KAPT、KSP、Compose 和 Kotlin Android Extensions 的专用参数生成逻辑。
- 不处理插件自身执行阶段抛出的任意异常。

## 5. 测试与验证

| 层级 | Owner | 场景 | 预期 |
|---|---|---|---|
| L1 | `KotlinCompilerInvokerArgsTest` | 当前模块与父模块均有参数 | 只选择当前模块参数 |
| L1 | `KotlinCompilerInvokerArgsTest` | 当前模块无参数 | 回退最近父模块并保留重复 option |
| L1 | `KotlinCompilerInvokerArgsTest` | 未加载项目插件 | 不生成项目 `-P` |
| L1 | `KotlinCompilerInvokerArgsTest` | unsupported option 来自 resolved 参数 | 识别 plugin ID，只移除该插件的 resolved 参数 |
| L1 | `KotlinCompilerInvokerArgsTest` | unsupported option 仅存在于 free args | 不触发新增降级 |
| L1 | `GradleProjectInfoReaderKotlinOptionsTest` | KGP subplugin 参数读取 | 参数完整写入 ModuleInfo |
| L1 | `JuggProjectInfoSerializerAndroidTestTest` | project info 持久化 | 新旧快照兼容 |
| L2 | `SourceCompileTest.kotlinAndJavaCompile` | 普通 Kotlin/Java 增量编译 | 保持成功 |
| 替代集成 | Kotlin CLI 1.9.23、2.2.10 | 未加载 plugin ID 的 `-P` | 编译成功 |
| 替代集成 | Kotlin Serialization compiler plugin | 已加载插件的未知 option | 首次失败，移除 resolved 参数后成功 |

## 6. 完成标准

- [x] MoshiX required option 可随对应 compilation 传入。
- [x] 不混合当前模块与父模块的 compiler plugin 参数。
- [x] 支持同名多值 option，保持 Gradle 顺序。
- [x] 新增 resolved 参数不兼容时只局部降级一次。
- [x] 原有 free compiler args 和其他插件参数不被修改。
- [x] 定向测试、普通源码编译回归和 Kotlin 编译检查通过。

## 7. 实施结果

- `KotlinCompiler` 从 current-to-parent module 列表选择第一个非空 option 集合，不再扁平合并。
- `KotlinCompilerInvoker` 将 Gradle-resolved 参数与项目 plugin 加载门禁绑定。
- `unsupported plugin option` 只匹配本轮实际传入的 resolved 参数；命中后移除同 plugin id 参数并重试一次。
- 降级成功后按 compiler/plugin classpath、plugin id 和原参数列表保存 fingerprint，避免相同配置重复失败；最终失败不会固化本次尝试。
- `kotlinFreeCompilerArgs`、KAPT、KSP、Compose 与 Kotlin Android Extensions 参数链保持不变。

验证命令：

```bash
./gradlew :main:test \
  --tests "com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerInvokerArgsTest" \
  --tests "com.sickworm.intellij.jugg.gradle.script.GradleProjectInfoReaderKotlinOptionsTest" \
  --tests "com.sickworm.intellij.jugg.project.data.JuggProjectInfoSerializerAndroidTestTest" \
  --tests "com.sickworm.intellij.jugg.compiler.SourceCompileTest.kotlinAndJavaCompile" \
  :idea:compileKotlin
```

结果：`BUILD SUCCESSFUL`。另使用 Kotlin 1.9.23 + Serialization compiler plugin 验证：未知 option 首次失败，保留插件并移除该 resolved 参数后编译退出码为 0。
