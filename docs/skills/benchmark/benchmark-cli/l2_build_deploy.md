# L2 编译与部署（~12 条）

> CLI 构建命令阻塞等待完成，无需轮询。
> `isFinal=false` 时重运行同一命令即可。

---

## 正常场景

### BUILD-1: 仅编译

修改文件（加注释）→ `compile`，验证 `status=OK`，不部署。

---

### BUILD-2: 编译并部署

修改文件 → `deploy`，验证 `status=OK`，应用已更新。

---

### BUILD-3: deploy 中间态重运行

若 `deploy` 返回 `isFinal=false`，重新执行 `deploy` 直到 `isFinal=true`。

---

### BUILD-4: Gradle 回退编译

执行 `gradle-build`，验证 `status=OK`（或 `isFinal=false` 时重运行至完成）。

---

### BUILD-5: 卸载重装 APK

执行 `reinstall`，验证 `status=OK`，应用数据清空。

---

## 编译失败

### BUILDFAIL-1: compile - 语法错误

引入 `val x: String = 123` → `compile`，验证 `status=ERROR`，`message` 含文件名/行号/错误描述。

---

### BUILDFAIL-2: deploy - 语法错误

同上代码 → `deploy`，验证 `status=ERROR`，`message` 含错误信息。

---

### BUILDFAIL-3: 符号未解析

调用不存在方法 → `deploy`，验证 `message` 含 "unresolved reference"。还原代码。

---

### BUILDFAIL-4: Gradle 编译失败

引入错误 → `gradle-build`，验证 `status=ERROR`，`message` 含可定位错误。还原代码。

---

## build.gradle 降级

### DEGRADE-1: 修改 build.gradle 后降级

修改 `build.gradle`（加注释）→ `deploy`，验证自动走 Gradle 路径，最终成功。还原。

---

## 长耗时场景

> 在 build.gradle 增加 sleep 25s + 末尾空行触发变更识别。测试后回退。

### LONG-1: 长耗时 Gradle - 成功

`gradle-build` → 可能首次返回 `isFinal=false` → 重运行至 `status=OK`。

---

### LONG-2: 长耗时 Gradle - 失败

引入错误 + 修改 build.gradle → `gradle-build` → 重运行至 `status=ERROR`，`message` 含错误信息。还原。
