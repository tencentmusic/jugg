# SO 增量部署方案调研：Tinker 路径注入与 JVMTI Agent

> 归档日期：2026-09-10
>
> 状态：调研归档，未进入实现；本文不构成开发计划或兼容性承诺。
>
> 范围：比较当前 `update to apk`、Tinker 式启动路径注入，以及 JVMTI/native hook 组合方案。
>
> 证据边界：结论来自本次讨论中的 Jugg 工作区源码、AOSP 和开源项目源码核对，未执行设备 PoC 或兼容性矩阵测试。工作区可能继续演进，本文记录本次讨论时的实现事实与判断。

## 1. 调研问题与最终结论

目前 Jugg 的 SO 更新通过更新 APK、重新签名和安装完成。最初的问题是：能否像现有 DEX 启动补丁一样，在 App 启动时将补丁搜索路径放到列表头部，并获得相同或更好的兼容性？

最终结论：

1. **Tinker 式 SO 路径注入机制可行，且已有成熟实现可以复用。** 它不是只有理论依据的新方案。
2. **实现成熟度与加载入口覆盖范围必须分开评价。** 对实际使用已注入 ClassLoader 的普通 `System.loadLibrary()`，这是成熟的可行路径；但不能自动覆盖指定原包路径、native 显式加载、ELF 隐式依赖等所有加载方式。
3. **以 Jugg 对已有业务的透明覆盖范围为标准，SO 路径注入比现有 DEX 启动方案窄。** 本次没有设备对比证据证明“同一适用场景下 SO 注入的运行稳定性比 DEX 差”，不能混用这两个结论。
4. **当前 `update to apk` 更适合作为默认路径。** 它更新安装产物，业务沿正常安装路径加载时可获得新版库，不需要先约束业务如何加载 SO。它也不保证修复业务自行保留的旧解压缓存、错误 ABI、二进制接口不一致等独立问题。
5. **Agent 可以扩大覆盖范围，但单靠 JVMTI 无法统一替换所有 Java 和 native 加载路径。** Java 加载逻辑可以通过 JVMTI 插桩改写；native 显式加载需要额外 native hook；仅 hook `dlopen()` 仍不能自动覆盖 linker 内部的 `DT_NEEDED` 依赖加载。
6. 本次未决定实现新方案。若继续研究，最有价值的验证是：指定原包路径、仅更新被依赖的库，以及启动注入是否早于首次业务加载。

## 2. 比较口径与讨论中修正的判断

本文比较的是“进程启动时加载补丁”，不包含 DEX 的 JVMTI 在线方法替换能力。现有 DEX 方案也有已加载类、ClassLoader、启动时序和厂商实现等边界，并非无条件兼容所有应用。

讨论中曾先得出“SO 方案整体兼容性更差”，在考虑 Tinker 的长期大规模应用经验后，又将这一结论整体撤回。最终明确：两次表述都需要限定维度。

| 维度 | 归档结论 |
|---|---|
| Tinker 实现是否成熟 | 有成熟实现和长期应用经验，应该优先复用，不应仅因枚举潜在风险而重新设计一套方案 |
| 普通按名称加载是否可行 | 可以，前提是实际调用方使用已注入 ClassLoader，且注入早于首次相关加载 |
| 同一适用场景下是否比 DEX 更容易失败 | 本次没有设备矩阵或对比失败证据，不能确定 |
| 对已有业务的透明覆盖范围 | SO 存在多条不经过 Java 搜索列表的路径，因此比现有 DEX 启动方案窄 |
| 是否等价替代更新 APK | 不等价，需要额外边界说明、适配和验证 |

用户提出 Tinker 已在超大规模应用中使用。这一经验支持其适用范围内的成熟度，但本次没有独立审计具体用户量、部署版本或这些应用实际启用了哪些 SO 加载方式，也不能据此推导所有业务加载入口都受路径注入控制。

## 3. Jugg 当前实现与可复用基础

### 3.1 当前 SO 更新链路

本次读取的实现中：

1. `DeployDataGenerator` 将 `changedLibs` 加入 `updateApkFiles`。
2. `JuggDeployerHelper` 根据 `isNeedUpdateApk` 调用 `updateApk()`。
3. 更新 APK、重新签名后进入恢复/重装流程。

代码依据：

- [DeployDataGenerator.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt)
- [JuggDeployerHelper.kt](../../../idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt)

### 3.2 当前 DEX 启动补丁

兼容加载路径会创建 `AndroidNClassLoader`，重建 DexPathList，再将补丁 `dexElements` 放到原有元素前面。普通部署也可能由 Android Studio Apply Changes agent 完成 DEX 加载，并不保证每次都进入这条重建 ClassLoader 的路径。

`HotfixLoader` 的运行时支持从 API 26 开始。`BootstrapApplication.attachBaseContext()` 在创建原始 Application 前初始化并按条件安装补丁；原始 `AppComponentFactory` 的创建和 `instantiateClassLoader()` 可以更早发生。

代码依据：

- [DexPatchLoader.java](../../../jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/DexPatchLoader.java)
- [AndroidNClassLoader.java](../../../jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/AndroidNClassLoader.java)
- [HotfixLoader.java](../../../jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/HotfixLoader.java)
- [BootstrapApplication.java](../../../jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/BootstrapApplication.java)
- [BootstrapAppComponentFactory.java](../../../jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/BootstrapAppComponentFactory.java)

### 3.3 当前 Agent 基础

Jugg 已通过 JVMTI 的 ClassFileLoadHook、RetransformClasses 等机制修改 Framework 类的方法，包括 Application 创建、资源与 ClassLoader resource 相关逻辑。因此，Java 加载入口插桩有可复用的基础设施。

本次讨论时读取的实现没有包含本文设想的 SO 路径重定向功能。已有 JVMTI 可用标记只证明基础环境可取得，不证明新的 Runtime 插桩或 native hook 能成功安装。

- [native-lib.cpp](../../../jvmti_agent/src/main/cpp/native-lib.cpp)
- [instrumenter.cc](../../../jvmti_agent/src/main/cpp/instrumenter.cc)
- [InstrumentationHooks.java](../../../jvmti_agent/src/main/java/com/sickworm/intellij/jugg/instrument/InstrumentationHooks.java)

## 4. Tinker 的两种 SO 加载方式

依据：[TinkerLoadLibrary.java](https://github.com/Tencent/tinker/blob/master/tinker-android/tinker-android-lib/src/main/java/com/tencent/tinker/lib/library/TinkerLoadLibrary.java)。

### 4.1 路径注入

Tinker 的公开方法名为 `installNavitveLibraryABI()`，其中 `Navitve` 是源码中的实际拼写。它取得 `context.getClassLoader()`，选择当前 ABI 的补丁目录，再调用内部 `installNativeLibraryPath()`。

现代 Android 分支的主要操作是：

1. 移除重复补丁目录，将补丁目录放到 `nativeLibraryDirectories` 前面。
2. 合并原应用和系统库目录。
3. 调用对应版本的 `makePathElements()` 重建 `nativeLibraryPathElements`。

普通加载链为：

```text
System.loadLibrary("foo")
  -> 调用方 ClassLoader.findLibrary("foo")
  -> 按顺序遍历 nativeLibraryPathElements
  -> 返回补丁目录中的 libfoo.so
  -> ART/native linker 加载库与依赖
```

Tinker 包含历史 Android 版本的字段和方法签名适配。Jugg runtime 从 API 26 开始，所需历史范围更小，但厂商和 targetSdk 适配仍需要验证。

不能直接用 AOSP `addNativePath()` 代替前置逻辑：核对的实现是将新元素追加到尾部，不能覆盖原包同名库。

### 4.2 显式加载补丁

`loadLibraryFromTinker()` 根据补丁记录查找 SO，按配置校验文件后执行 `System.load(补丁绝对路径)`。这要求业务主动使用对应 API。

因此，“Tinker 支持通过绝对路径加载补丁”与“Tinker 自动拦截业务指定的原包绝对路径”是两件事。前者存在，后者不是路径注入方法提供的能力。

`TinkerSoLoader.checkComplete()` 负责检查 SO 补丁文件及记录，不应将它理解为自动预加载并替换进程里所有库。

依据：[TinkerSoLoader.java](https://github.com/Tencent/tinker/blob/master/tinker-android/tinker-android-loader/src/main/java/com/tencent/tinker/loader/TinkerSoLoader.java)。

## 5. Tinker 式路径注入的场景边界

本节“可以”表示有源码支持的加载路径，不表示已经完成目标设备实测。

### 5.1 Java 按名称与绝对路径加载

| 场景 | 路径注入的效果 |
|---|---|
| `System.loadLibrary("foo")` | 实际调用方使用已注入 ClassLoader 时，可优先命中补丁 |
| 通过已注入 ClassLoader 的 `findLibrary()` 获取路径，再 `System.load(path)` | 可以；路径已经指向补丁 |
| `System.load(applicationInfo.nativeLibraryDir + "/libfoo.so")` | 不重定向，仍指定安装目录中的文件 |
| 直接指定 `base.apk!/lib/<abi>/libfoo.so` | 不重定向，仍指定 APK 中的原条目 |
| 自行打开原库文件描述符，再调用 native 扩展加载 | Java 搜索路径不改变已打开的文件 |

绝对路径本身不是障碍，路径来源才是关键。`Runtime.load0()` 直接使用传入文件名，不经过 `findLibrary()`；linker 对包含路径的名称也直接打开指定文件。

提前加载同名补丁不能保证后续原路径调用改用补丁。显式路径可能继续加载旧文件，甚至形成新旧库并存，不能用同名预加载替代路径重定向。

### 5.2 ELF 隐式依赖：`A -> DT_NEEDED: B`

Java 搜索列表负责找到显式加载的 A，B 由 native linker 解析，不会再次调用 Java 的 `findLibrary("B")`。

| 变更 | 仅前置 Java 搜索路径是否足够 |
|---|---|
| 更新 A，B 不变 | 可以命中补丁 A；原包 B 在依赖关系兼容、搜索可达时可继续使用 |
| 仅更新 B，业务只加载 A | 不能保证补丁 B 生效，因为 B 没有经过 Java 搜索 |
| 同时更新 A、B | 不能只因两者位于同一补丁目录就保证配套加载 |
| A 新增依赖 B，原包没有 B | native 搜索找不到补丁 B 时会失败 |

依赖加载可以在特定条件下正确工作，例如：

- 业务本来就先通过正确 ClassLoader 加载补丁 B，再加载 A，并且 B 对 A 所在 namespace 可见。
- native namespace 的搜索路径已包含补丁目录。
- A 配置了合适的 `DT_RUNPATH`，例如 `$ORIGIN`，且对应路径可访问、未被更高优先级查找或已加载库抢先命中。

但 Tinker 的路径注入没有直接更新已存在的 linker namespace。不能把“Java 列表已修改”视为“native 依赖搜索路径也已修改”。

仅更新 B 尤其重要：可能没有崩溃或加载异常，只是静默使用旧 B。只捕获异常无法识别这种失败。

### 5.3 Native 显式加载与相对位置依赖

`dlopen("libB.so")` 使用调用环境对应的 native namespace 和 linker 搜索规则，不直接使用 Java 搜索列表。它可以在搜索路径或已加载库等条件满足时使用补丁，但不是路径注入自动保证的结果。

`dlopen("/原安装目录/libB.so")` 则属于指定原路径的情况。

如果库通过 `dladdr()` 获取自身位置，再寻找旁边的库或数据文件，移到补丁目录会改变这个“旁边”。仅复制被修改的 SO 可能缺少配套文件。`$ORIGIN` 相关依赖也应考虑同一位置变化。

### 5.4 第三方加载器与 Flutter

不能只按框架名称判断支持性，应检查最终选择文件的具体分支。

- **ReLinker**：核对的默认实现先尝试普通按名称加载，该分支可以命中补丁。失败后可能从 APK 提取库，再按文件路径加载，从而使用旧库。不能把整个 ReLinker 一概归为不支持，也不能因正常分支成功就忽略提取分支。
- **Flutter AOT**：`FlutterLoader` 将 AOT 库名称及安装目录完整路径传给 engine，native snapshot 加载逻辑负责打开对应库。证明 `System.loadLibrary("flutter")` 命中补丁，不能推出 `libapp.so` 也会命中。普通 C++ JNI 的验证不能替代 Flutter AOT 验证。

依据：[ReLinkerInstance](https://github.com/KeepSafe/ReLinker/blob/master/relinker/src/main/java/com/getkeepsafe/relinker/ReLinkerInstance.java)、[FlutterLoader](https://github.com/flutter/flutter/blob/master/engine/src/flutter/shell/platform/android/io/flutter/embedding/engine/loader/FlutterLoader.java)、[FlutterJNI](https://github.com/flutter/flutter/blob/master/engine/src/flutter/shell/platform/android/io/flutter/embedding/engine/FlutterJNI.java)、[dart_snapshot.cc](https://github.com/flutter/flutter/blob/master/engine/src/flutter/runtime/dart_snapshot.cc)。

### 5.5 加载时机与已加载库

路径前置不能撤销 ELF constructors、`JNI_OnLoad()`、JNI 注册、native 全局状态和已经完成的链接。Tinker 路径注入不是进程内替换已加载 native 库的机制。

进程重启解决上一轮已加载的问题，但仍须保证本轮注入早于业务首次加载。Jugg bootstrap 的位置对原始 Application 初始化有利，但早期 AppComponentFactory、自定义 ClassLoader 创建或其他提前加载逻辑仍需核对。

若库已经部分初始化或加载失败，不能默认在同一进程安全切换到旧库。兼容回退应以新进程为边界。

### 5.6 多 ClassLoader 与 JNI 归属

`System.loadLibrary()` 使用调用类所属的 ClassLoader，不是简单使用线程 context ClassLoader。

Tinker 公开注入方法修改 `context.getClassLoader()`，不会自动覆盖所有现存或未来创建的 loader。需要考虑插件化、自定义 `findLibrary()`、isolated split，以及 Jugg 新旧 ClassLoader 并存的情况。

JNI 库与加载它的 ClassLoader 有关联。不能由 Jugg bootstrap 的 loader 统一预加载所有 SO，再假设所有业务 loader 都能正常使用。同一库由不同 loader 加载可能受到 ART 的归属检查限制。

### 5.7 多进程、isolated process 与权限

普通多进程可以支持，但相关进程必须在启动时分别注入；修改主进程的搜索路径不会修改其他进程。更新后应重启相关旧进程，避免版本混用。

isolated process 可能无法访问普通 app 私有目录。系统应用等特殊 SELinux 域也需要检查补丁位置的映射执行权限。原 APK 中的库可执行，不代表复制到 app data 后仍可执行。

### 5.8 split APK、未解压库与变更类型

`extractNativeLibs=false` 本身不是障碍：补丁可以是独立文件，搜索列表后面仍保留原 APK 内的库元素。但重建路径时必须保留原有 APK/split 范围与隔离关系，不能遗漏 split 或无条件混合不同环境中的同名库。

| 变更类型 | 路径前置能力 |
|---|---|
| 替换已有库 | 核心适用场景 |
| 新增库 | Java 按名称加载有可行路径；作为 native 依赖时仍需满足依赖查找条件 |
| 删除原包中的库 | 单靠前置不能表达删除，补丁目录未命中后仍会找到原包库 |
| 重命名库 | 新名字可以加入，但旧名字不会自动消失 |

删除语义与 DEX overlay 回退原定义存在相似性，不应作为 SO 特有的不稳定因素。

### 5.9 通用产物约束与部署状态

以下要求不能全部归因于 Tinker 的缺陷：

- ABI 应匹配实际进程，而不是仅按设备首选 ABI 选择。
- ELF 应满足目标设备要求，包括 16 KB 页设备的段对齐。
- JNI 接口、C++ ABI 或共享数据布局变化时，应同步相关 Java/native 调用方。

这些问题在更新 APK 时也可能存在。路径注入额外引入了补丁位置、文件权限和补丁状态管理：连续覆盖、历史清理、恢复安装基线、多库同批发布及进程版本一致性都需要正确处理。

## 6. Native namespace 对 Jugg 的具体意义

Framework 创建应用 ClassLoader 时，通常已经通过 `ClassLoaderFactory` 创建对应 namespace。到 `Application.attachBaseContext()` 再修改 Java 路径，并不会自动更新它。

但 AOSP 对尚未关联 namespace 的自建 ClassLoader，可以在首次 native 加载时，根据它的 library path 创建 namespace。`BaseDexClassLoader.getLdLibraryPath()` 读取 native library directories。因此，Jugg 现有 `AndroidNClassLoader` 提供一个值得验证的条件：如果在首次 native 加载前就准备好补丁目录，依赖搜索也有机会使用正确路径。

这不是所有 Jugg 部署路径的既有保证：

- 普通部署不一定重建 ClassLoader。
- 原 loader、split 或自定义 loader 定义的类不一定使用新 loader。
- 已存在 namespace、已加载库、平台库可见范围等仍会影响结果。
- 不能为了假设的兼容问题，未经验证就强制所有 SO 更新重建 ClassLoader 或修改 linker namespace。

依据：[ClassLoaderFactory](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/com/android/internal/os/ClassLoaderFactory.java)、[ApplicationLoaders](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/app/ApplicationLoaders.java)、[BaseDexClassLoader](https://android.googlesource.com/platform/libcore/+/refs/heads/main/dalvik/src/main/java/dalvik/system/BaseDexClassLoader.java)、[native_loader.cpp](https://android.googlesource.com/platform/art/+/refs/heads/main/libnativeloader/native_loader.cpp)、[library_namespaces.cpp](https://android.googlesource.com/platform/art/+/refs/heads/main/libnativeloader/library_namespaces.cpp)。

## 7. JVMTI Agent 能覆盖什么

### 7.1 能力总表

同一个 agent 可以承载 Java 插桩和 native hook，但“agent 能执行 native 代码”不等于“JVMTI 提供统一 SO 加载拦截接口”。

| 加载场景 | JVMTI/Agent 能力边界 |
|---|---|
| Java `System.loadLibrary()` | 可通过修改 Runtime 等 Java 加载逻辑实现，须验证目标类可重转换及版本签名 |
| Java `System.load(绝对路径)` | 可改写传入路径，补上 Tinker 路径注入的这一缺口 |
| Native `dlopen()` | JVMTI 没有对应加载拦截接口，需要额外 native hook |
| Native `android_dlopen_ext()` | 同上，还需处理 FD、偏移和 namespace 等扩展参数 |
| ELF `DT_NEEDED` | 不经过 Java，也不为每个依赖重走公开 `dlopen()`；入口 hook 不足以覆盖 |
| 已加载并链接的旧库 | 改路径不替换已有状态，仍需可靠的进程重启边界 |

### 7.2 Java 层改写路径

可研究通过 ClassFileLoadHook/RetransformClasses 修改 `Runtime.load0()`、`loadLibrary0()` 等方法，在原调用链进入 native 加载前执行映射：

```text
业务指定原安装目录/libfoo.so
  -> agent 插入的路径映射
  -> 补丁目录/libfoo.so
  -> 原有 ART/native 加载流程
```

实现应保留原始 ClassLoader、调用类和错误语义，优先改写原调用链中的参数，不能简单转到 Jugg helper 中再次调用 `System.load()`。

新增库还有额外位置要求：如果原 `findLibrary()` 已经失败并抛出异常，就到不了最终 `nativeLoad()`。因此只在最终 native 调用前修改路径，不一定覆盖新增按名称加载的库。

Jugg 有 Framework 插桩基础，但尚未验证这些 Runtime 方法在全部目标设备上可修改。Agent attach 的系统支持、debuggable 等前提也仍然存在；不能用 Agent 方案扩大对 JVMTI 不可用设备的承诺。

### 7.3 NativeMethodBind 不是 SO 加载事件

JVMTI `NativeMethodBind` 通知的是 Java native 方法与实现函数地址发生绑定，可以将绑定指向代理函数。它不是每次打开 SO 的通知，也不会因此拦截任意 C/C++ `dlopen()`。

针对某个 Java native 加载方法做绑定代理是一种可能的专门适配，但仍需处理绑定时机、已发生绑定和方法签名，不能将其视为统一 native 库 hook。

依据：[JVMTI NativeMethodBind](https://docs.oracle.com/en/java/javase/17/docs/specs/jvmti.html#NativeMethodBind)、[ART TI](https://source.android.com/docs/core/runtime/art-ti)。

### 7.4 额外 native hook

Agent 可以集成 PLT/inline hook，研究拦截 `dlopen()`、`android_dlopen_ext()` 或更底层入口。ByteHook、ShadowHook 提供可复用基础设施；本次只核对了项目说明，没有完成选型、接口验证或集成试验。

从足够底层拦截，有机会同时接住 Java 最终发起的加载和 native 直接加载，但不能仅替换文件名：

- Android 根据调用者地址确定 namespace，需要保留原始调用来源。
- FD/offset 加载中，修改字符串不会改变已打开的库文件。
- 显式 namespace、不同 APK/ABI/loader 的同名库需要保持原有身份与可见范围。
- hook 的覆盖范围取决于具体机制，不能把修改部分调用点等同于拦截所有调用。

依据：[ByteHook](https://github.com/bytedance/bhook)、[ShadowHook](https://github.com/bytedance/android-inline-hook)、[AOSP libdl](https://android.googlesource.com/platform/bionic/+/android-16.0.0_r1/libdl/libdl.cpp)。

### 7.5 仅 hook dlopen 仍漏掉隐式依赖

```text
业务 dlopen("libA.so")
  -> 入口 hook 可以改成补丁 A
  -> linker 读取 A 的 DT_NEEDED
  -> linker 内部创建并解析 B 的加载任务
  -> 不为 B 再调用公开 dlopen()
```

要补齐“仅更新 B，业务加载 A”，需要进一步控制依赖搜索或解析，例如正确的 namespace 搜索路径，或更深层 linker 适配。这超出 JVMTI 和公开 `dlopen()` hook 的能力范围，并增加平台适配负担。

因此，本次不将“JVMTI + dlopen hook”认定为更新 APK 的等价替代。

## 8. 若继续研究，优先验证什么

以下是后续研究候选，不表示本次已获准或已执行实现。

| 优先级 | 验证场景 | 必须观察的结果 |
|---|---|---|
| P0 | 单库普通按名称加载、连续两次更新 | 实际执行新版库；不是只有注入成功 |
| P0 | 原安装目录绝对路径、APK 内原路径 | 是否真实重定向；未覆盖时是否明确保留 APK 更新 |
| P0 | 仅更新 B，业务只加载依赖 B 的 A | B 的版本、映射来源和行为确实更新；覆盖静默旧库情况 |
| P0 | Application/AppComponentFactory 等早期加载 | 注入发生在目标库首次加载之前 |
| P1 | 同时更新 A/B、新增依赖 B、native dlopen | 依赖配套，未命中的原库保持正确行为 |
| P1 | 多 ClassLoader、split、JNI 注册 | 作用于实际调用方，库归属和隔离关系正确 |
| P1 | 多进程及已运行子进程 | 各相关进程使用一致版本 |
| P1 | Flutter AOT、ReLinker 正常与提取分支 | 实际业务库更新，不能只验证 Java 入口库 |
| P1 | 回滚、清理、恢复安装基线、多库发布失败 | 不残留错误补丁，不将混合版本当作成功 |
| P2 | 目标范围内的 API/ROM、32/64 位、16 KB 页、特殊权限域 | 记录明确支持边界和失败条件 |

验证应结合库的可观察行为、版本标识/Build ID 和实际映射来源。仅看到反射成功、hook 安装成功或 `loadLibrary()` 无异常，都不能证明更新生效。

不应先为测试在生产代码增加专用抽象。若进入实现，应按 `06_testing.md` 重新确定行为 owner、失败证据、测试价值及设备侧替代验证；本文没有新增自动化测试。

## 9. 对 Jugg 部署方案的阶段性建议

继续保留 `update to apk` 作为默认方案。其代价是 APK 更新、签名和安装耗时；其优势是沿原安装产物更新，不必额外约束常见业务加载入口。

若未来引入路径注入作为优化路径，应直接利用 Tinker 的成熟实现，并以实测确定边界，不先为假设问题添加 namespace 修改、依赖预加载或通用 hook 框架。

若未来研究 Agent 扩展，应先把 Java 绝对路径、native 显式加载和隐式依赖分别验证，不能以两层入口均可 hook 就推导完整覆盖。

Jugg 集成还需独立识别 SO 补丁：不能复用“DEX 已由 agent 正确加载就跳过”的判断，否则仅 SO 更新可能根本不安装补丁。补丁下发、ABI 目录、首次加载前注入、进程重启、清理和失败回退都属于必要集成工作。

## 10. 调研依据与证据边界

### 10.1 仓库文档

- [00_overview.md](../../ai_knowledge/00_overview.md)
- [99_index.md](../../ai_knowledge/99_index.md)
- [98_code_map.md](../../ai_knowledge/98_code_map.md)
- [03_deploy_core.md](../../ai_knowledge/03_deploy_core.md) 相关小节
- [03_runtime_jvmti.md](../../ai_knowledge/03_runtime_jvmti.md)

### 10.2 主要平台依据

除前文就近引用的来源外，本次还核对了：

- [Android 8 DexPathList](https://android.googlesource.com/platform/libcore/+/android-8.0.0_r1/dalvik/src/main/java/dalvik/system/DexPathList.java)
- [Android 9 DexPathList](https://android.googlesource.com/platform/libcore/+/android-9.0.0_r1/dalvik/src/main/java/dalvik/system/DexPathList.java)
- [Android 16 DexPathList](https://android.googlesource.com/platform/libcore/+/android-16.0.0_r1/dalvik/src/main/java/dalvik/system/DexPathList.java)
- [AOSP Runtime](https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/main/java/java/lang/Runtime.java)
- [ART JNI 库加载与 ClassLoader 归属](https://android.googlesource.com/platform/art/+/refs/heads/main/runtime/jni/java_vm_ext.cc)
- [AOSP linker：路径、SONAME、namespace 与 DT_NEEDED](https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker.cpp)
- [NDK linker 行为与 DT_RUNPATH](https://android.googlesource.com/platform/bionic/+/refs/heads/main/android-changes-for-ndk-developers.md)
- [非 SDK 接口限制](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces)
- [16 KB 页支持](https://developer.android.com/guide/practices/page-sizes)
- [AOSP app SELinux 规则](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app.te)

Android 8、9、16 的 Java 搜索结构核对证明这些版本存在相应机制，不等于所有中间版本、厂商 ROM、targetSdk 组合均已验证。Android 9 起的非 SDK 接口限制同时影响反射与 JNI 访问；字段存在也不等于任意应用一定可访问。

开源链接中 `main`/`master` 为可变分支，本文记录的是归档日前本次调研读取的内容；后续实现应重新核对目标版本。未执行设备 PoC、性能测量或实际应用兼容矩阵，也没有给出覆盖率或成功率承诺。
