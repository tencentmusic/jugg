# L3 无设备场景（~14 条）

> 执行前 `adb emu kill` 确认无设备，执行后恢复。

---

## 不需设备（应正常返回）

### NODEV-1: devices - 空列表

`devices` → `status=OK`，设备列表为空。

---

### NODEV-2: compile

`compile` → `status=OK`（编译不需设备）。

---

## 需要设备（编译后部署失败）

### NODEV-3: deploy

`deploy` → 编译可能正常，但部署阶段失败 `status=ERROR`。

---

### NODEV-4: gradle-build

`gradle-build` → 编译可能正常，但后续步骤失败 `status=ERROR`。

---

### NODEV-5: reinstall

`reinstall` → 失败 `status=ERROR`。

---

## 需要设备（直接失败）

### NODEV-6: restart

`restart` → `status=ERROR`。

---

### NODEV-7: screenshot

`screenshot` → `status=ERROR`。

---

### NODEV-8: record-start

`record-start` → `status=ERROR`。

---

### NODEV-9: layout-dump

`layout-dump` → `status=ERROR`。

---

### NODEV-10: view-locate

`view-locate --text "Anything"` → `status=ERROR`。

---

### NODEV-11: view-inspect

`view-inspect --text "Anything" text` → `status=ERROR`。

---

### NODEV-12: activity-stack

`activity-stack` → `status=ERROR`。

---

### NODEV-13: tap

`tap --xp 50 --yp 50` → `status=ERROR`。
