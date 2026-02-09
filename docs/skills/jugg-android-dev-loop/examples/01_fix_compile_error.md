# Example: Fix Compile Error

## Scenario

Agent modifies a Kotlin file to add a new feature, but introduces an unresolved reference. The agent diagnoses and fixes the error, then completes the full closed-loop.

## Step-by-step Trace

### 1. Confirm project

**Call:** `list_projects` (no arguments)

**Response:**
```json
{
  "status": "OK",
  "message": "list_projects executed successfully.",
  "data": {
    "projects": [
      {"projectDir": "/Users/dev/MyApp", "initialized": true}
    ]
  },
  "artifacts": [],
  "errorCode": null
}
```

Agent picks `projectDir = "/Users/dev/MyApp"`.

### 2. Confirm device

**Call:** `device_list` with `projectDir: "/Users/dev/MyApp"`

**Response:**
```json
{
  "status": "OK",
  "message": "device_list executed successfully.",
  "data": {
    "devices": [
      {"serial": "emulator-5554", "name": "Pixel_6_API_34", "isOnline": true, "api": 34, "isSelected": true}
    ]
  },
  "artifacts": [],
  "errorCode": null
}
```

### 3. Agent applies code change

Agent edits `MainActivity.kt` to call a new utility function `formatUserName()`, but forgets to add the import.

### 4. Compile and deploy (first attempt — fails at compile stage)

**Call:** `compile_and_deploy` with `projectDir: "/Users/dev/MyApp"`

**Response:**
```json
{
  "status": "ERROR",
  "message": "compile_and_deploy failed. Reason: Compilation error\ne: /Users/dev/MyApp/app/src/main/java/com/example/MainActivity.kt:42:15 Unresolved reference: formatUserName",
  "data": {
    "detail": "e: /Users/dev/MyApp/app/src/main/java/com/example/MainActivity.kt:42:15 Unresolved reference: formatUserName"
  },
  "artifacts": [],
  "errorCode": "MCP_INTERNAL_ERROR"
}
```

### 5. Agent diagnoses the error

Agent matches the error against pattern `gradle_unresolved_symbol`:
- **signature match**: message contains `"unresolved reference"`
- **confidence**: 0.90
- **auto_apply**: true (low-risk, local fix scope)

Agent reads the source file, identifies the missing import, and adds:
```kotlin
import com.example.utils.formatUserName
```

### 6. Compile and deploy (second attempt — succeeds)

**Call:** `compile_and_deploy` with `projectDir: "/Users/dev/MyApp"`

**Response:**
```json
{
  "status": "OK",
  "message": "compile_and_deploy executed successfully.",
  "data": {"runResult": {"success": true}},
  "artifacts": [],
  "errorCode": null
}
```

### 7. App start

**Call:** `app_start` with `projectDir: "/Users/dev/MyApp"`

**Response:**
```json
{
  "status": "OK",
  "message": "app_start executed successfully. Using selected device: emulator-5554.",
  "data": {
    "device": {"serial": "emulator-5554", "name": "Pixel_6_API_34", "isOnline": true},
    "packageName": "com.example.myapp",
    "activity": ".MainActivity",
    "component": "com.example.myapp/.MainActivity"
  },
  "artifacts": [],
  "errorCode": null
}
```

### 8. Screenshot (verification)

**Call:** `screenshot` with `projectDir: "/Users/dev/MyApp"`

**Response:**
```json
{
  "status": "OK",
  "message": "screenshot executed successfully. Using selected device: emulator-5554.",
  "data": {
    "device": {"serial": "emulator-5554", "name": "Pixel_6_API_34", "isOnline": true},
    "file": "/Users/dev/MyApp/build/jugg/mcp_fetch/screenshot/screenshot_emulator-5554_1707500000000.png"
  },
  "artifacts": [{"type": "image", "path": "/Users/dev/MyApp/build/jugg/mcp_fetch/screenshot/screenshot_emulator-5554_1707500000000.png"}],
  "errorCode": null
}
```

### 9. Agent summary

```
Verdict: PASS
Project: /Users/dev/MyApp
Build path: compile_and_deploy
Error pattern matched: gradle_unresolved_symbol (confidence 0.90, auto_applied: true, retry: 1)
Fix applied: added missing import for formatUserName
Artifacts:
  - screenshot: /Users/dev/MyApp/build/jugg/mcp_fetch/screenshot/screenshot_emulator-5554_1707500000000.png
```
