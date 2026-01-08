# Jugg 技术文档 - 演进历史

> 创建时间: 2025-01-20
> 数据来源: Git 提交历史 + change_log
> 分析范围: 2024-2025 年度

---

## 一、项目概况

### 1.1 开发活跃度

- **2024 年提交数**: 1108+ 次
- **当前版本**: 2.6.13 (2025.12.26)
- **版本发布频率**: 平均每周 1-2 个版本
- **开发模式**: 持续迭代，快速响应

### 1.2 版本演进路线

```
2.1.0 (2025.01) - 热更新支持
    ↓
2.2.0 (2025.02) - Kotlin Compose 支持
    ↓
2.3.0 (2025.05) - 远程服务器应用
    ↓
2.4.0 (2025.08) - RPC 调用支持
    ↓
2.5.0 (2025.09) - Dynamic Feature Modules (AAB)
    ↓
2.6.0 (2025.11) - CI 支持
    ↓
2.6.13 (2025.12) - 持续优化
```

---

## 二、重大功能演进

### 2.1 核心功能里程碑

#### 2.1.1 热更新机制 (2025.01)

**版本**: 2.1.0-rc1

**功能**:
- 支持从服务器热更新插件
- 无需重启 IDE 即可更新

**技术实现**:
- `JuggLoader` 使用自定义 ClassLoader
- 动态加载 JAR 文件
- Proxy 模式跨 ClassLoader 调用

**影响**: 极大提升了插件迭代速度，用户可以快速获得新功能和 Bug 修复。

---

#### 2.1.2 Kotlin Compose 支持 (2025.02)

**版本**: 2.2.0-rc1

**功能**:
- 支持 Kotlin Compose 增量编译
- 支持 `@Parcelize` 注解
- 支持使用项目 Kotlin 编译器

**技术挑战**:
1. Compose 编译器插件集成
2. Kotlin 编译器版本兼容
3. 编译器参数传递

**解决方案**:
```kotlin
// 使用项目 Kotlin 编译器
val kotlinCompiler = module.kotlinPlugins?.find { 
    it.name.contains("kotlin-compiler") 
}

// 传递 Compose 编译器插件
val composePlugin = module.kotlinPlugins?.find { 
    it.name.contains("compose-compiler") 
}
```

**影响**: 支持了 Android 最新的 UI 框架，覆盖了更多的项目场景。

---

#### 2.1.3 远程编译服务器 (2025.05)

**版本**: 2.3.0-rc1

**功能**:
- 支持远程服务器应用
- 支持 SSH 同步
- 支持 IFT 同步

**使用场景**:
1. 本地机器性能不足
2. 需要在服务器上编译
3. 团队共享编译资源

**技术实现**:
- `JuggRemoteCompileApplier` 管理远程配置
- `RsyncCompatibleHelper` 处理文件同步
- `SimpleSshCommand` 执行远程命令

**影响**: 扩展了使用场景，支持了更复杂的开发环境。

---

#### 2.1.4 RPC 调用支持 (2025.08)

**版本**: 2.4.0-rc1, 2.4.0-rc2

**功能**:
- 支持 RPC 调用
- 支持外部工具集成
- 支持 CI/CD 集成

**API 设计**:
```kotlin
enum class RpcCommand {
    ECHO,  // 测试连接
    RUN,   // 执行编译和部署
}

data class RpcRequest(
    val cmd: RpcCommand,
    val params: Map<String, String> = emptyMap(),
)

data class RpcResponse(
    val result: RpcResult,
    val message: String,
)
```

**使用示例**:
```bash
# 测试连接
curl -X POST http://localhost:8888/rpc \
  -d '{"cmd":"ECHO"}'

# 执行编译
curl -X POST http://localhost:8888/rpc \
  -d '{"cmd":"RUN","params":{"config":"jugg:app"}}'
```

**影响**: 支持了自动化工作流，可以集成到 CI/CD 流程中。

---

#### 2.1.5 Dynamic Feature Modules (AAB) (2025.09)

**版本**: 2.5.0-rc1

**功能**:
- 支持 Dynamic Feature Modules
- 支持 AAB (Android App Bundle)
- 支持副作用检查

**技术挑战**:
1. 多 APK 管理
2. Feature APK 资源包名处理
3. 副作用检查

**解决方案**:
```kotlin
data class ApkFileUnit(
    val applicationId: String, 
    val moduleName: String,  // Feature 模块名
    val apkFile: File
) {
    val isBaseApk get() = moduleName.isEmpty()
    val isFeatureApk get() = moduleName.isNotEmpty()
    
    // Feature APK 的资源包名
    val resourcePackage get() = if (isBaseApk) {
        applicationId
    } else {
        "$applicationId.$moduleName"
    }
}
```

**影响**: 支持了 Google Play 的 AAB 格式，覆盖了更多的发布场景。

---

#### 2.1.6 CI 支持 (2025.11)

**版本**: 2.6.0

**功能**:
- 支持在 CI 环境运行
- 支持命令行模式
- 支持无 GUI 环境

**命令行工具**:
```bash
# 基础构建
java -jar jugg-cmdline.jar \
  cmd=buildGradleBase \
  baseBuildProjectDir=/path/to/project \
  gradleCompileTask=assembleDebug \
  gradleOutputApkPath=app/build/outputs/apk/debug/*.apk \
  outputApkDir=/path/to/output

# 增量编译
java -jar jugg-cmdline.jar \
  cmd=buildIncrementalApk \
  baseBuildJuggRootDir=/path/to/base/build/jugg \
  sourceProjectDir=/path/to/source \
  outputApkDir=/path/to/output \
  changedFiles=/path/to/file1.kt:/path/to/file2.kt
```

**影响**: 支持了 CI/CD 流程，可以在服务器上自动化构建。

---

### 2.2 Android Studio 版本兼容演进

| 时间 | 版本 | Android Studio 版本 | 关键变化 |
|------|------|---------------------|---------|
| 2025.03 | 2.2.0-rc12 | Meerkat | 新增 Meerkat 支持 |
| 2025.04 | 2.2.0-rc25 | Narwhal | 新增 Narwhal 支持 |
| 2025.08 | 2.4.0-rc4 | Narwhal FD | 新增 Narwhal Feature Drop 支持 |
| 2025.09 | 2.4.0-rc8 | Narwhal 4 FD | 新增 Narwhal 4 Feature Drop 支持 |
| 2025.12 | 2.6.9 | Otter 2 FD | 新增 Otter 2 Feature Drop 支持 |

**兼容策略**:
- 继承链设计，新版本继承旧版本
- Proxy 模式，自动降级到兼容版本
- 反射处理 API 变化

---

### 2.3 Kotlin 版本兼容演进

| 时间 | 版本 | Kotlin 版本 | 关键变化 |
|------|------|-------------|---------|
| 2025.02 | 2.2.0-rc1 | Kotlin 2.0 | 支持 Kotlin Compose |
| 2025.11 | 2.5.0-rc11 | Kotlin 2.2 | 支持 Kotlin 2.2 |

**兼容挑战**:
1. Kotlin 编译器 API 变化
2. Kotlin 插件版本兼容
3. `.kotlin_module` 格式变化

**解决方案**:
- 使用项目 Kotlin 编译器
- 动态加载 Kotlin 插件
- 兼容多种 `.kotlin_module` 格式

---

## 三、关键技术决策

### 3.1 混淆支持 (2024.12)

**背景**: 用户需要在 Release 构建中使用 Jugg

**挑战**:
1. R8 混淆后的类名映射
2. 内联方法检测
3. 删除类检测

**技术方案**:
```kotlin
// 读取 mapping.txt
class MappingReader {
    fun read(mappingFile: File): Map<String, String> {
        // 解析混淆映射
        // com.example.MainActivity -> a.b.c:
        //     void onCreate() -> a
    }
}

// 检测内联方法
class InlineMethodDetector {
    fun detect(classFile: File): List<InlinedMethod> {
        // 使用 ASM 分析字节码
        // 检测方法调用是否被内联
    }
}

// 重新编译受影响的类
class EffectedSourceRecompiler {
    fun recompile(inlinedMethods: List<InlinedMethod>) {
        // 查找调用了内联方法的类
        // 重新编译这些类
    }
}
```

**影响**: 支持了 Release 构建，覆盖了生产环境调试场景。

---

### 3.2 Configuration Cache 兼容 (2025.12)

**背景**: Gradle 7.0+ 引入了 Configuration Cache

**挑战**:
1. Gradle 脚本不能在配置阶段执行任务
2. 需要兼容新旧 Gradle 版本

**技术方案**:
```kotlin
// 检测 Configuration Cache
val isConfigurationCacheEnabled = gradle.startParameter.isConfigurationCache

if (isConfigurationCacheEnabled) {
    // 使用新的 API
    tasks.register("readProjectInfo") {
        doLast {
            // 在执行阶段读取项目信息
        }
    }
} else {
    // 使用旧的 API
    project.afterEvaluate {
        // 在配置阶段读取项目信息
    }
}
```

**影响**: 兼容了最新的 Gradle 特性，避免了警告和错误。

---

### 3.3 AppComponentFactory 支持 (2025.12)

**背景**: Android 9.0+ 引入了 `AppComponentFactory`

**挑战**:
1. 需要在 Manifest 中声明
2. 需要在兼容模式下工作
3. ClassLoader 需要正确设置

**技术方案**:
```kotlin
// 在 Manifest 中声明
<application
    android:appComponentFactory="com.sickworm.jugg.JuggAppComponentFactory">
</application>

// 实现 AppComponentFactory
class JuggAppComponentFactory : AppComponentFactory() {
    override fun instantiateApplication(
        cl: ClassLoader,
        className: String
    ): Application {
        // 使用正确的 ClassLoader
        val juggClassLoader = getJuggClassLoader()
        return super.instantiateApplication(juggClassLoader, className)
    }
}
```

**影响**: 支持了 Android 9.0+ 的新特性，避免了 ClassLoader 错误。

---

### 3.4 嵌入到 APK (2025.11)

**背景**: Android RemoteViews 需要从 APK 加载资源

**挑战**:
1. 增量资源无法被 RemoteViews 加载
2. 需要将增量资源嵌入到 APK

**技术方案**:
```kotlin
// 创建资源 APK
class ResourceApkModifier {
    fun createResourceApk(overlays: List<DeployItem>) {
        // 创建独立的资源 APK
    }
}

// 嵌入到主 APK
class ApkFileModifier {
    fun insertAndResign() {
        // 1. 插入资源文件
        // 2. 对齐 APK
        // 3. 重新签名
    }
}
```

**使用场景**:
- Widget
- Notification
- RemoteViews

**影响**: 支持了 RemoteViews 场景，覆盖了更多的应用类型。

---

## 四、性能优化历程

### 4.1 APK 修改性能优化 (2024)

**问题**: 使用标准 ZIP API 修改 APK 需要 10-60 秒

**优化方案**:
```kotlin
// JDK 14+ 使用 FileSystems API
private fun insertFileJvm14(apkFile: File): File {
    val zipProperties = mapOf(
        "create" to "false", 
        "compressionMethod" to "STORED"
    )
    FileSystems.newFileSystem(zipDisk, zipProperties).use { zipFileSystem ->
        // 直接修改 ZIP 文件，无需解压缩
    }
}
```

**效果**: 性能提升 90%，从 10-60 秒降低到 1-2 秒

---

### 4.2 AAPT2 守护进程模式 (2024)

**问题**: 每次调用 AAPT2 都需要启动进程，耗时 1-2 秒

**优化方案**:
```kotlin
class Aapt2DaemonInvoker {
    private var process: Process? = null

    fun init() {
        // 启动守护进程
        process = Runtime.getRuntime().exec("$aapt2 daemon")
    }

    fun invoke(params: String): Aapt2Result {
        // 复用守护进程
        process.outputStream.write("${params}\n\n".toByteArray())
        return outputReader?.read()
    }
}
```

**效果**: 避免了重复启动开销，提升了资源编译速度

---

### 4.3 Gradle 依赖缓存 (2025.03)

**问题**: 每次读取 Gradle 依赖都需要执行 Gradle 任务

**优化方案**:
```kotlin
// 缓存依赖信息
class DependencyCache {
    private val cache = mutableMapOf<String, List<Dependency>>()

    fun get(moduleId: String): List<Dependency>? {
        return cache[moduleId]
    }

    fun put(moduleId: String, dependencies: List<Dependency>) {
        cache[moduleId] = dependencies
    }
}
```

**效果**: 避免了重复读取，提升了启动速度

---

### 4.4 备份 Classpath 优化 (2025.09)

**问题**: 备份 Classpath 需要复制大量文件，耗时长

**优化方案**:
```kotlin
// 默认关闭备份 Classpath
JuggSettings.isEnableBackupClasspath = false

// 仅在需要时启用
if (isNeedBackupClasspath) {
    JuggSettings.isEnableBackupClasspath = true
}
```

**效果**: 清理项目时速度更快

---

## 五、稳定性提升

### 5.1 依赖缺失自动修复 (2025.03)

**问题**: Gradle 清理依赖后，增量编译失败

**解决方案**:
```kotlin
class DependencyMissingResolver {
    fun resolve(compileResult: CompileResult): Boolean {
        if (compileResult.hasError("unresolved reference")) {
            // 检测依赖是否缺失
            val missingDependencies = detectMissingDependencies()
            if (missingDependencies.isNotEmpty()) {
                // 重新执行 Gradle 任务
                executeGradleTask("assemble")
                return true
            }
        }
        return false
    }
}
```

**效果**: 自动恢复依赖，避免了手动清理

---

### 5.2 ADB 自动重启 (2025.05)

**问题**: ADB 无响应时，部署失败

**解决方案**:
```kotlin
class AdbRestarter {
    fun restartIfNotResponding() {
        if (!isAdbResponding()) {
            // 重启 ADB
            Runtime.getRuntime().exec("adb kill-server")
            Thread.sleep(1000)
            Runtime.getRuntime().exec("adb start-server")
        }
    }
}
```

**效果**: 自动恢复 ADB，避免了手动重启

---

### 5.3 部署超时重试 (2025.04)

**问题**: 部署超时后，用户需要手动重试

**解决方案**:
```kotlin
class DeployRetryStrategy {
    fun deploy(device: IDevice, apk: File) {
        var retryCount = 0
        while (retryCount < 4) {
            try {
                doDeployment(device, apk)
                return
            } catch (e: TimeoutException) {
                retryCount++
                if (retryCount == 3) {
                    // 第三次超时，重新安装 APK
                    reinstallApk(device, apk)
                } else if (retryCount == 4) {
                    // 第四次超时，降级到 Gradle 编译
                    fallbackToGradleCompile()
                }
            }
        }
    }
}
```

**效果**: 自动重试和降级，提升了成功率

---

## 六、Bug 修复统计

### 6.1 高频 Bug 类型

| Bug 类型 | 数量 | 占比 |
|---------|------|------|
| 兼容性问题 | 45+ | 40% |
| 依赖缺失 | 25+ | 22% |
| 资源编译 | 20+ | 18% |
| 部署失败 | 15+ | 13% |
| 其他 | 8+ | 7% |

### 6.2 典型 Bug 案例

#### 案例 1: findViewById 返回 null (2025.04)

**问题**: macOS 升级到 15.4 后，findViewById 返回 null

**原因**: rsync 版本不兼容，文件同步失败

**解决方案**:
```kotlin
// 使用内嵌的 rsync (version 3.4.1)
val embeddedRsync = copyResource("/tools/darwin/rsync-3.4.1")
```

**影响**: 修复了 macOS 15.4 的兼容性问题

---

#### 案例 2: R.styleable 找不到 (2025.04)

**问题**: 资源增量编译后，R.styleable 找不到

**原因**: 项目启用了 split R 逻辑，R.styleable 在不同的包中

**解决方案**:
```kotlin
// 检测 split R 逻辑
val isSplitR = project.android.buildFeatures.androidResources == false

if (isSplitR) {
    // 生成完整的 R.java
    generateFullRJava()
}
```

**影响**: 修复了 split R 项目的兼容性问题

---

#### 案例 3: Gradle 依赖 JAR 缺失 (2025.12)

**问题**: AAR 包含多个 JAR 时，只读取了第一个

**原因**: 代码逻辑错误，只处理了第一个 JAR

**解决方案**:
```kotlin
// 读取 AAR 中的所有 JAR
fun readJarsFromAar(aar: File): List<File> {
    val jars = mutableListOf<File>()
    ZipFile(aar).use { zipFile ->
        zipFile.entries().asSequence().forEach { entry ->
            if (entry.name.endsWith(".jar")) {
                jars.add(extractJar(zipFile, entry))
            }
        }
    }
    return jars
}
```

**影响**: 修复了多 JAR AAR 的依赖缺失问题

---

## 七、社区反馈与改进

### 7.1 用户反馈驱动的功能

1. **Clean and Reset Jugg** (2025.04)
   - 用户反馈: 无法降级时需要手动清理
   - 解决方案: 提供一键清理功能

2. **导出增量 APK** (2025.11)
   - 用户反馈: 需要分享增量 APK 给测试人员
   - 解决方案: 支持导出增量 APK

3. **自定义编译器** (2025.02)
   - 用户反馈: 需要在编译流程中插入自定义逻辑
   - 解决方案: 提供自定义编译器扩展点

### 7.2 用户体验优化

1. **自动创建运行配置** (2024)
   - 自动检测 Android 运行配置
   - 自动创建 Jugg 运行配置

2. **依赖变化确认对话框** (2024)
   - 显示依赖变化详情
   - 用户确认后再执行 Gradle 编译

3. **编译进度显示** (2024)
   - 显示编译进度
   - 显示编译耗时

---

## 八、未来展望

### 8.1 计划中的功能

1. **更好的 Compose 支持**
   - 支持 Compose Preview
   - 支持 Compose 调试

2. **更好的 KMM 支持**
   - 支持 Kotlin Multiplatform Mobile
   - 支持 iOS 增量编译

3. **更好的性能**
   - 优化编译速度
   - 优化部署速度

### 8.2 技术债务

1. **代码重构**
   - 简化兼容层代码
   - 统一错误处理

2. **测试覆盖**
   - 增加单元测试
   - 增加集成测试

3. **文档完善**
   - 完善用户文档
   - 完善开发者文档

---

## 九、总结

### 9.1 关键成就

1. **持续迭代**: 2024 年 1108+ 次提交，平均每周 1-2 个版本
2. **广泛兼容**: 支持 Android Studio Chipmunk ~ Otter 2 FD
3. **功能丰富**: 支持 Kotlin Compose、Dynamic Feature、CI/CD
4. **性能优化**: APK 修改性能提升 90%
5. **稳定可靠**: 自动修复依赖缺失、自动重启 ADB

### 9.2 技术亮点

1. **热更新机制**: 无需重启 IDE 即可更新插件
2. **版本兼容**: 继承链 + Proxy 模式适配多版本
3. **混淆支持**: 支持 R8 混淆的 Release 构建
4. **远程编译**: 支持 SSH 和 IFT 同步
5. **RPC 调用**: 支持外部工具和 CI/CD 集成

### 9.3 经验教训

1. **兼容性优先**: 新功能必须兼容旧版本
2. **自动化优先**: 尽量自动修复问题，减少用户操作
3. **性能优先**: 性能是用户体验的关键
4. **快速迭代**: 快速响应用户反馈，快速修复 Bug
5. **文档重要**: 良好的文档可以减少用户困惑

---

## 附录：版本发布时间线

### 2025 年

| 月份 | 版本 | 关键功能 |
|------|------|---------|
| 01 | 2.1.0 | 热更新支持 |
| 02 | 2.2.0 | Kotlin Compose 支持 |
| 03 | 2.2.0-rc12 | Android Studio Meerkat 支持 |
| 04 | 2.2.0-rc25 | Android Studio Narwhal 支持 |
| 05 | 2.3.0 | 远程服务器应用 |
| 06 | 2.3.0-rc4 | 稳定性提升 |
| 07 | 2.3.0-rc13 | Java 21 支持 |
| 08 | 2.4.0 | RPC 调用支持 |
| 09 | 2.5.0 | Dynamic Feature Modules (AAB) |
| 10 | 2.4.0-rc14 | Gradle includeBuild 兼容 |
| 11 | 2.6.0 | CI 支持 |
| 12 | 2.6.13 | 持续优化 |

### 2024 年

- 持续迭代和优化
- 1108+ 次提交
- 多个版本发布
