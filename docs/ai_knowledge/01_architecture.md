# Jugg 架构设计

> 文档版本: v1.0
> 更新时间: 2025-01-20
> 项目版本: 2.6.13

---

## 一、整体架构

### 1.1 分层架构

```
┌─────────────────────────────────────────────────────────┐
│                    IDE Platform                         │
│  (IntelliJ IDEA / Android Studio)                       │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│              IDE 插件层 (idea/)                          │
│  • JuggLoader (热更新)                                   │
│  • JuggRunConfiguration (运行配置)                       │
│  • JuggManager (核心管理器)                              │
└──────────────────────┬──────────────────────────────────┘
                       │ (Proxy 跨 ClassLoader)
┌──────────────────────▼──────────────────────────────────┐
│              核心逻辑层 (main/)                          │
│  ┌─────────────┬─────────────┬─────────────┐            │
│  │  compiler/  │   deploy/   │  project/   │            │
│  │  (编译系统)  │  (部署系统)  │ (项目管理)   │            │
│  └─────────────┴─────────────┴─────────────┘            │
│  ┌─────────────┬─────────────┬─────────────┐            │
│  │    apk/     │    git/     │   logger/   │            │
│  │ (APK操作)    │ (Git集成)    │  (日志)      │            │
│  └─────────────┴─────────────┴─────────────┘            │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│              兼容层 (deploy_compat/)                     │
│  • ChipmunkAsDeployerCompat                             │
│  • GiraffeAsDeployerCompat                              │
│  • ... (多版本 Android Studio 兼容)                      │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│              平台 Mock 层 (platform_compat/)             │
│  • Logger, Project, Disposable (IntelliJ API Mock)      │
│  • IDevice, Apk (Android SDK API Mock)                  │
└─────────────────────────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│              运行时层 (jvmti_agent/)                     │
│  • JVMTI Agent (C++)                                    │
│  • 热修复支持 (Android 8.0+)                             │
└─────────────────────────────────────────────────────────┘
```

### 1.2 模块职责

| 模块 | 职责 | 关键技术 |
|------|------|---------|
| **idea/** | IDE 集成 | IntelliJ Platform API |
| **main/compiler/** | 增量编译 | Kotlin Compiler, ASM, D8 |
| **main/deploy/** | 热修复部署 | JVMTI, Android Deployer |
| **main/project/** | 项目管理 | Gradle API, JGit |
| **deploy_compat/** | 版本兼容 | Reflection, Proxy |
| **platform_compat/** | 平台 Mock | Mock 实现 |
| **jvmti_agent/** | 运行时支持 | JVMTI, C++ |
| **cmd_line/** | 命令行工具 | 独立运行 |

---

## 二、核心流程

### 2.1 编译流程

```
用户修改代码
    ↓
FileChangesDetector 检测文件变化
    ↓
FileChangesHandler 过滤可编译文件
    ↓
DeployStateManager 判断编译模式
    ↓
┌─────────────────┬─────────────────┐
│  增量编译        │  全量编译        │
│  (Jugg)         │  (Gradle)       │
└────────┬────────┴────────┬────────┘
         │                 │
         ↓                 ↓
    JuggCompiler      LocalGradleCompileClient
         │                 │
         ↓                 ↓
    CompileTask       Gradle Task
         │                 │
         ↓                 ↓
    [Java/Kotlin]     [assembleDebug]
    [Resource]             │
    [Manifest]             │
         │                 │
         ↓                 ↓
    CompileResult     APK 文件
         │                 │
         └────────┬────────┘
                  ↓
            DeployFileManager
                  ↓
            准备部署数据
```

### 2.2 部署流程

```
CompileResult
    ↓
DeployFileManager.getDeployData()
    ↓
┌─────────────────┬─────────────────┬─────────────────┐
│  首次安装        │  增量部署        │  嵌入到 APK      │
└────────┬────────┴────────┬────────┴────────┬────────┘
         │                 │                 │
         ↓                 ↓                 ↓
    JuggDeployer      JuggDeployer      IncrementalDeployHelper
    .install()        .codeSwap()       .updateApk()
         │                 │                 │
         ↓                 ↓                 ↓
    安装 APK          推送 Overlay       修改 APK
         │                 │                 │
         ↓                 ↓                 ↓
    启动 App          JVMTI 热修复      重新签名
                          │                 │
                          ↓                 ↓
                    重定义类/资源        安装 APK
```

---

## 三、关键设计模式

### 3.1 策略模式 - 编译器

不同的编译策略：JavaCompiler, KotlinCompiler, ResourceCompiler, ManifestCompiler

### 3.2 责任链模式 - 编译流程

编译器按顺序执行，支持中断和回滚

### 3.3 代理模式 - 版本兼容

自动降级到兼容版本，无需修改调用代码

### 3.4 观察者模式 - 事件监听

解耦事件源和事件处理，支持多个监听器

### 3.5 工厂模式 - 编译器创建

支持 SPI 机制，自动发现编译器

---

## 四、关键技术点

### 4.1 增量编译

**核心思想**: 只编译变化的文件，复用未变化的编译结果

**性能提升**: 单文件编译时间从 30-60 秒降低到 1-5 秒

### 4.2 热修复

**核心思想**: 无需重启 App 即可更新代码和资源

**实现方式**: JVMTI + Overlay + ClassLoader

### 4.3 版本兼容

**核心思想**: 支持多个 Android Studio 版本

**实现方式**: 继承链 + Proxy 模式 + 反射

### 4.4 Gradle 集成

**核心思想**: 读取 Gradle 项目信息，无需依赖 IDE

**实现方式**: init.gradle + 反射读取 + JSON 序列化

---

## 五、性能优化

| 优化项 | 优化前 | 优化后 | 提升 |
|--------|--------|--------|------|
| 单文件编译 | 30-60s | 1-5s | 90%+ |
| APK 修改 | 10-60s | 1-2s | 90%+ |
| AAPT2 调用 | 1-2s/次 | 0.1s/次 | 90%+ |

**关键优化**:
1. 增量编译
2. FileSystems API (JDK 14+)
3. AAPT2 守护进程

---

## 六、扩展性设计

### 6.1 自定义编译器

通过 `@AutoService(ICompilerCreator::class)` 注解实现

### 6.2 自定义平台 API

通过 `PlatformApi.impl = MyPlatformApi()` 注入

### 6.3 自定义 RPC 命令

在 `RpcCommand` 枚举中添加新命令

---

## 七、总结

### 7.1 架构优势

1. **分层清晰**: IDE 层、核心层、兼容层、平台层分离
2. **高度解耦**: 模块之间通过接口通信
3. **易于扩展**: 支持自定义编译器、平台 API、RPC 命令
4. **性能优异**: 增量编译、守护进程、缓存优化
5. **兼容性强**: 支持多版本 Android Studio、Kotlin、Gradle

### 7.2 技术栈

| 层次 | 技术 |
|------|------|
| **IDE 层** | IntelliJ Platform API |
| **编译层** | Kotlin Compiler, ASM, D8, AAPT2 |
| **部署层** | JVMTI, Android Deployer |
| **项目层** | Gradle API, JGit |
| **兼容层** | Reflection, Proxy |
| **运行时** | JVMTI, C++ |

---

**下一步**: 查看 [02_compile_core.md](02_compile_core.md) 了解编译系统详情