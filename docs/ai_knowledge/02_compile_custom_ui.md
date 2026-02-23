# 编译系统：自定义编译器与编译交互

> 最后核对：2026-02-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述两个扩展面：
- 自定义编译器加载（SPI）
- 编译过程 UI/交互协议

---

## 2. 自定义编译器关键类

| 类 | 文件 | 作用 |
|----|------|------|
| `CustomCompilerManager` | `main/.../compiler/custom/CustomCompilerManager.kt` | 管理 jar 来源、下载、校验、ServiceLoader 装载 |
| `ICompilerCreator` | `main/.../compiler/custom/ICompilerCreator.kt` | SPI 接口，返回自定义 `ICompiler` |
| `Example*CustomCompiler` | `custom_compilers/src/main/java/.../demo/` | 官方示例实现 |

---

## 3. 装载规则（当前实现）

`CustomCompilerManager` 支持三类路径：
- 绝对路径
- 相对 `projectDir` 路径
- `http(s)` 远端地址（下载到 `customCompilerDir`，按 md5 校验）

加载方式：`ServiceLoader.load(ICompilerCreator::class.java, URLClassLoader(...))`。

---

## 4. 编译交互协议

| 对象 | 文件 | 用途 |
|------|------|------|
| `CompileUiHandler` | `main/.../compiler/CompileUiHandler.kt` | 编译侧交互抽象（取消、通知、过程输出） |
| `RunResult` | `main/.../compiler/ui/RunResult.kt` | 运行结果描述（编译/部署成功等） |
| `BuildChangesConfirmResult` | `main/.../compiler/ui/BuildChangesConfirmResult.kt` | 变更确认结果 |

---

## 5. 新增自定义编译器建议步骤

1. 在独立模块实现 `ICompilerCreator` 与自定义 `ICompiler`。  
2. 配置 `META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator`。  
3. 在配置中声明 jar 路径与 md5。  
4. 通过 `CustomCompilerManager` 更新并验证加载结果。

---

## 6. 常见问题定位

- “配置了 jar 但没生效”：检查 md5、路径解析、ServiceLoader 声明。  
- “远端下载后仍未加载”：检查下载目录是否存在，`resetCompilerJars()` 是否触发。  
- “编译器运行但 UI 不同步”：检查 `CompileUiHandler` 的实现与调用线程。

---

## 7. 关联文档

- 编译核心：`02_compile_core.md`
- IDE 执行链：`04_engineering_ide.md`
- 工程/配置：`04_engineering_project.md`
