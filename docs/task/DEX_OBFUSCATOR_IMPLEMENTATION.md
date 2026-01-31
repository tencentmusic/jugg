# DEX Obfuscator 实现总结

## 概述

参考 `ClassObfuscator.kt` 的实现,成功实现了 DEX 文件的混淆逻辑 `DexObfuscator.kt` 及其对应的单元测试 `DexObfuscatorTest.kt`。

## 实现的文件

### 1. DexObfuscator.kt
**路径**: `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexObfuscator.kt`

**主要功能**:
- 使用 dex-reader/dex-writer 库处理 DEX 文件的混淆
- 基于 R8MappingReader 提供的映射信息重命名类名、方法名和字段名
- 支持文件、字节数组和输入流三种输入方式
- 实现了缓存机制以提高性能

**核心类**:
- `DexObfuscator`: 主类,负责 DEX 文件的混淆
- `ObfuscationDexRemapper`: 内部类,继承自 `DexFileVisitor`,实现具体的重命名逻辑

**主要方法**:
- `obfuscate(File, File)`: 混淆文件
- `obfuscate(ByteArray)`: 混淆字节数组
- `obfuscate(InputStream)`: 混淆输入流
- `getObfuscatedClassName(String)`: 获取混淆后的类名
- `getOriginClassSigName(String)`: 获取原始类签名名
- `hasClassMapping(String)`: 检查类是否在映射中
- `findInvocationsOf(String, String)`: 查找方法调用(用于内联检测)
- `getMappingStats()`: 获取映射统计信息

**特性**:
- ✅ 类名重映射
- ✅ 方法名重映射(保留构造函数和静态初始化器)
- ✅ 字段名重映射
- ✅ 超类和接口重映射
- ✅ 方法调用重映射
- ✅ 字段访问重映射
- ✅ 注解中的类型引用重映射
- ✅ 方法参数和返回类型重映射
- ✅ 文件缓存机制

### 2. DexObfuscatorTest.kt
**路径**: `main/src/test/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexObfuscatorTest.kt`

**测试覆盖**:

#### 基础混淆测试 (3个)
- `testObfuscateDexFromMappingString`: 测试从映射字符串创建混淆器
- `testObfuscateDexBytes`: 测试混淆 DEX 字节
- `testObfuscateDexWithNoMapping`: 测试未在映射中的类

#### 方法描述符测试 (2个)
- `testObfuscateMethodWithParameters`: 测试带参数的方法混淆
- `testObfuscateMethodWithArrayParameters`: 测试带数组参数的方法混淆

#### 引用重映射测试 (1个)
- `testObfuscateClassReference`: 测试类引用的重映射

#### 文件混淆测试 (2个)
- `testObfuscateFile`: 测试文件混淆
- `testObfuscateDexPath`: 测试 DEX 路径处理

#### 超类和接口测试 (4个)
- `testObfuscateSuperclass`: 测试超类重映射
- `testObfuscateInterfaces`: 测试接口重映射
- `testObfuscateSuperclassNotInMapping`: 测试未在映射中的超类
- `testObfuscateMixedInterfaces`: 测试混合接口(部分在映射中)

#### 特殊方法测试 (2个)
- `testConstructorNotRenamed`: 测试构造函数不被重命名
- `testStaticInitializerNotRenamed`: 测试静态初始化器不被重命名

#### 方法调用和字段访问测试 (2个)
- `testObfuscateMethodInvocation`: 测试方法调用的混淆
- `testObfuscateFieldAccess`: 测试字段访问的混淆

#### 缓存测试 (1个)
- `testObfuscatorCaching`: 测试缓存机制

**总计**: 17个测试,全部通过 ✅

## 与 ClassObfuscator 的对比

| 特性 | ClassObfuscator | DexObfuscator |
|------|----------------|---------------|
| 处理格式 | Java Class 文件 | DEX 文件 |
| 使用库 | ASM | dex-reader/dex-writer |
| 核心访问者 | ClassRemapper | DexFileVisitor |
| 类型描述符 | JVM 格式 (Ljava/lang/String;) | DEX 格式 (Ljava/lang/String;) |
| 方法描述符转换 | descriptorToParams | protoToParams |
| 缓存机制 | ✅ | ✅ |
| 特殊方法处理 | ✅ | ✅ |

## 技术要点

### 1. DEX 类型描述符处理
DEX 使用与 JVM 相同的类型描述符格式:
- 基本类型: `I` (int), `Z` (boolean), `V` (void) 等
- 对象类型: `Lcom/example/MyClass;`
- 数组类型: `[I` (int[]), `[Ljava/lang/String;` (String[])

### 2. Proto 转换
DEX 使用 `Proto` 对象表示方法签名,包含:
- `parameterTypes`: 参数类型数组
- `returnType`: 返回类型

需要将 Proto 转换为 R8 映射格式的参数列表,例如:
- `Proto(["Ljava/lang/String;", "I"], "V")` → `"java.lang.String,int"`

### 3. 访问者模式
使用 dex-reader/dex-writer 的访问者模式:
```kotlin
DexFileVisitor
  └─ DexClassVisitor
       ├─ DexFieldVisitor
       ├─ DexMethodVisitor
       │    └─ DexCodeVisitor
       └─ DexAnnotationVisitor
```

### 4. 特殊方法处理
构造函数 (`<init>`) 和静态初始化器 (`<clinit>`) 不能被重命名,但它们的所有者类和参数类型仍需要重映射。

## 运行测试

由于测试依赖 `ANDROID_HOME` 环境变量,需要使用以下命令运行:

```bash
cd main
ANDROID_HOME=$HOME/Library/Android/sdk ../gradlew test --tests "com.sickworm.intellij.jugg.compiler.obfuscation.DexObfuscatorTest"
```

## 测试结果

```
✅ 17 tests completed
✅ 0 tests failed
✅ 100% success rate
⏱️  Total time: 0.078s
```

## 使用示例

```kotlin
// 从映射文件创建混淆器
val obfuscator = DexObfuscator.fromMappingFile(File("mapping.txt"))

// 混淆 DEX 文件
val inputDex = File("classes.dex")
val outputDex = File("classes-obfuscated.dex")
obfuscator.obfuscate(inputDex, outputDex)

// 或者混淆字节数组
val dexBytes = inputDex.readBytes()
val obfuscatedBytes = obfuscator.obfuscate(dexBytes)

// 查询混淆后的类名
val obfuscatedName = obfuscator.getObfuscatedClassName("com.example.MyClass")
println("Obfuscated: $obfuscatedName") // 输出: a.b

// 获取统计信息
val stats = obfuscator.getMappingStats()
println("Classes: ${stats.classCount}, Methods: ${stats.methodCount}, Fields: ${stats.fieldCount}")
```

## 总结

成功实现了完整的 DEX 文件混淆功能,包括:
- ✅ 完整的混淆逻辑实现
- ✅ 17个全面的单元测试
- ✅ 与 ClassObfuscator 一致的 API 设计
- ✅ 缓存机制优化性能
- ✅ 完善的错误处理

实现完全参考了 `ClassObfuscator.kt` 的设计模式和测试结构,确保了代码的一致性和可维护性。
