# Example: Deploy Fallback

## Scenario

`compile_and_deploy` fails at the deploy stage due to signature mismatch (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Agent matches the error pattern, triggers `clean_reinstall_apk` as automatic downgrade, and completes verification.

## Step-by-step Trace

### 1. Confirm project and device

**Call:** `list_projects` → picks `projectDir = "/Users/dev/MyApp"`

**Call:** `device_list` with `projectDir` → confirms `emulator-5554` is online.

### 2. Compile and deploy (fails at deploy stage — signature mismatch)

**Call:** `compile_and_deploy` with `projectDir: "/Users/dev/MyApp"`

**Response:**
```json
{
  "status": "ERROR",
  "message": "compile_and_deploy failed. Reason: deploy stage not successful.\nINSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.example.myapp signatures do not match newer version",
  "data": {
    "detail": "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.example.myapp signatures do not match newer version"
  },
  "artifacts": [],
  "errorCode": "MCP_INTERNAL_ERROR"
}
```

### 3. Agent diagnoses the error

Agent matches against pattern `install_signature_mismatch`:
- **signature match**: message contains `"INSTALL_FAILED_UPDATE_INCOMPATIBLE"` and `"signatures do not match"`
- **confidence**: 0.94
- **auto_apply**: true (clean_reinstall_apk is the documented fix strategy)

Since confidence >= 0.8 and the fix scope is `medium` with `auto_apply: true`, agent proceeds to automatic downgrade.

### 4. Clean reinstall APK (fallback)

**Call:** `clean_reinstall_apk` with `projectDir: "/Users/dev/MyApp"`

**Response:**
```json
{
  "status": "OK",
  "message": "clean_reinstall_apk executed successfully.",
  "data": {"runResult": {"success": true}},
  "artifacts": [],
  "errorCode": null
}
```

### 5. App restart

**Call:** `restart_app` with `projectDir: "/Users/dev/MyApp"`

**Response:**
```json
{
  "status": "OK",
  "message": "restart_app executed successfully.",
  "data": {},
  "artifacts": [],
  "errorCode": null
}
```

### 6. Screenshot (verification)

**Call:** `screenshot` with `projectDir: "/Users/dev/MyApp"`

**Response:**
```json
{
  "status": "OK",
  "message": "screenshot executed successfully. Using selected device: emulator-5554.",
  "data": {
    "device": {"serial": "emulator-5554", "name": "Pixel_6_API_34", "isOnline": true},
    "file": "/Users/dev/MyApp/build/jugg/mcp_fetch/screenshot/screenshot_emulator-5554_1707500003000.png"
  },
  "artifacts": [{"type": "image", "path": "/Users/dev/MyApp/build/jugg/mcp_fetch/screenshot/screenshot_emulator-5554_1707500003000.png"}],
  "errorCode": null
}
```

### 7. Agent summary

```
Verdict: PASS
Project: /Users/dev/MyApp
Build path: compile_and_deploy (FAILED) → clean_reinstall_apk (OK)
Error pattern matched: install_signature_mismatch (confidence 0.94, auto_applied: true)
Downgrade chain: compile_and_deploy failed at deploy stage → clean_reinstall_apk succeeded
Artifacts:
  - screenshot: /Users/dev/MyApp/build/jugg/mcp_fetch/screenshot/screenshot_emulator-5554_1707500003000.png
```
