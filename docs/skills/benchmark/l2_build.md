# L2 Unit: 编译与部署

> 覆盖 `compile_only`、`compile_and_deploy`、`force_gradle_compile`、`get_compile_status`、`clean_reinstall_apk` 五个工具。
> 包含正常场景、编译失败、build.gradle 降级、长耗时异步共 ~15 条。

---

## 六、编译与部署（正常场景）

**BUILD-1: 仅编译（不部署）**
修改项目中一个 Kotlin/Java 文件（如加一行注释），然后调用 `compile_only`，传入 `projectDir`，验证返回 `status` 为 `OK`，确认编译成功但不会部署到设备。

**BUILD-2: 编译并部署 - 同步完成**
修改项目中一个文件，调用 `compile_and_deploy`，传入 `projectDir`。如果返回 `isFinal=true`，验证 `status` 为 `OK`；如果返回 `isFinal=false`，验证 `data` 中包含 `jobId`。

**BUILD-3: 编译并部署 - 异步轮询**
调用 `compile_and_deploy` 后，如果返回 `isFinal=false`，取出 `jobId`，反复调用 `get_compile_status`（传入 `projectDir` 和 `jobId`），直到返回 `isFinal=true`，验证最终 `status` 为 `OK` 或包含编译错误信息。

**BUILD-4: 查询编译状态 - 无效 jobId**
调用 `get_compile_status`，传入有效 `projectDir` 和一个不存在的 `jobId`（如 `"fake-job-999"`），验证返回 `status` 为 `ERROR`，有合理的错误信息。

**BUILD-5: Gradle 回退编译**
调用 `force_gradle_compile`，传入 `projectDir`。由于 Gradle 编译较慢，验证可能返回 `isFinal=false` 和 `jobId`，使用 `get_compile_status` 轮询直到完成。

**BUILD-6: 卸载重装 APK**
调用 `clean_reinstall_apk`，传入 `projectDir`，验证返回 `status` 为 `OK`（或异步完成后为 `OK`），应用数据被清空，APK 被重新安装。注意：此操作会清空应用数据，测试时需预期。

---

## 七、编译失败 - 错误信息验证

> 本章验证编译失败时，各编译类工具能否正确返回可读的错误信息（文件名、行号、错误描述）。

**BUILDFAIL-1: compile_only - 语法错误**
在项目中故意引入一个 Kotlin 语法错误（如在某个 `.kt` 文件中写入 `val x: String = 123`），调用 `compile_only`。验证返回 `status` 为 `ERROR`，`message` 或 `data` 中包含：出错文件名、行号、具体错误描述（如类型不匹配）。错误信息应当足够让 agent 定位并修复问题。

**BUILDFAIL-2: compile_and_deploy - 语法错误**
同 BUILDFAIL-1 的错误代码不还原，调用 `compile_and_deploy`。如果是异步返回，使用 `get_compile_status` 轮询至终态。验证最终 `status` 为 `ERROR`，错误信息中包含出错文件名、行号和错误描述。

**BUILDFAIL-3: compile_and_deploy - 符号未解析**
在项目中调用一个不存在的方法（如 `nonExistentMethod()`），调用 `compile_and_deploy`。等待终态，验证返回 `status` 为 `ERROR`，错误信息中包含 "unresolved reference" 或类似未解析符号的描述。验证完毕后还原代码。

**BUILDFAIL-4: force_gradle_compile - 编译失败**
在项目中引入一个编译错误，调用 `force_gradle_compile`。使用 `get_compile_status` 轮询至终态。验证最终 `status` 为 `ERROR`，错误信息中包含可定位的文件和错误描述。验证完毕后还原代码。

---

## 八、编译降级 - build.gradle 修改触发 Gradle 编译

> 验证 `compile_and_deploy` 在检测到 `build.gradle` 文件变更时，自动降级到 Gradle 编译路径。

**DEGRADE-1: 修改 build.gradle 后 compile_and_deploy 降级**
1. 在项目的 `build.gradle`（或 `build.gradle.kts`）中做一个无害修改（如在文件末尾加一行注释 `// mcp test`）
2. 调用 `compile_and_deploy`，传入 `projectDir`
3. 验证行为：工具应自动降级走 Gradle 编译路径（而非 Jugg 增量编译），可通过返回的 `message` 或 `data` 中是否包含 Gradle 相关的描述来判断
4. 使用 `get_compile_status` 轮询至终态，验证最终编译成功
5. 还原 `build.gradle` 的修改

---

## 九、长耗时编译场景（>25s）

> 本章验证编译耗时超过 25 秒的情况下，异步机制是否正常工作，编译成功和失败的结果都能正常获取。
> 制造长耗时方式：在根目录 build.gradle 增加 sleep 25s，且在 build.gradle 末尾增加空行，保证触发 build.gradle 变更识别。
> 测试完成后，需回退改动。

**LONG-1: 长耗时编译 - 成功**
1. 通过 CLI 修改一个会触发大范围重编译的文件（如在 `build.gradle` 中添加一行无害注释，或修改 `buildSrc` 中的版本号常量，使整个工程需要重新编译）
2. 调用 `force_gradle_compile`，传入 `projectDir`
3. 验证立即返回 `isFinal=false` 和 `jobId`（因为耗时超过同步阈值）
4. 使用 `get_compile_status` 持续轮询（按返回的 `pollIntervalSuggestedMs` 字段执行），验证中间状态返回合理（如 `isFinal=false`，可能包含进度信息）
5. 等待编译完成（预期超过 25 秒），验证最终返回 `isFinal=true`、`status` 为 `OK`
6. 还原修改

**LONG-2: 长耗时编译 - 失败**
1. 通过 CLI 在一个公共基础模块的核心文件中引入编译错误（如在频繁被依赖的工具类中写入非法语法），同时修改 `build.gradle` 确保触发完整 Gradle 编译
2. 调用 `force_gradle_compile`，传入 `projectDir`
3. 验证立即返回 `isFinal=false` 和 `jobId`
4. 使用 `get_compile_status` 持续轮询，等待终态
5. 验证最终返回 `isFinal=true`、`status` 为 `ERROR`，且错误信息中包含出错文件名和错误描述
6. 还原修改

**LONG-3: 长耗时 compile_and_deploy - 成功**
1. 通过 CLI 修改 `build.gradle` 触发降级 + 长耗时
2. 调用 `compile_and_deploy`，传入 `projectDir`
3. 验证异步返回 `isFinal=false` 和 `jobId`
4. 使用 `get_compile_status` 轮询至终态，验证最终 `status` 为 `OK`，应用被成功部署
5. 还原修改

**LONG-4: 长耗时 compile_and_deploy - 失败**
1. 通过 CLI 修改 `build.gradle`（触发降级）+ 引入编译错误
2. 调用 `compile_and_deploy`，传入 `projectDir`
3. 验证异步返回 `isFinal=false` 和 `jobId`
4. 使用 `get_compile_status` 轮询至终态，验证最终 `status` 为 `ERROR`，包含可定位的错误信息
5. 还原所有修改
