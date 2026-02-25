# Example: UI Change Verify

## Scenario

Agent modifies a layout XML and Activity code to add a new button, then uses layout_dump to find coordinates, tap to interact, and screenshot to verify the result.

## Step-by-step Trace

### 1. Confirm project and device

**Call:** `list_projects` (no arguments) → picks `projectDir = "/Users/dev/MyApp"`

**Call:** `device_list` with `projectDir` → confirms `emulator-5554` is online.

### 2. Agent applies UI changes

Agent edits two files:
- `res/layout/activity_main.xml`: adds a `<Button android:id="@+id/btn_refresh" android:text="Refresh" />`
- `MainActivity.kt`: adds click listener that updates a `TextView`

### 3. Compile and deploy

**Call:** `compile_and_deploy` with `projectDir: "/Users/dev/MyApp"` → `status: "OK"`

### 4. App start

**Call:** `start_app` with `projectDir: "/Users/dev/MyApp"`

**Response:**
```json
{
  "status": "OK",
  "message": "start_app executed successfully. Using selected device: emulator-5554.",
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

### 5. Layout dump (discover button coordinates)

**Call:** `layout_dump` with `projectDir: "/Users/dev/MyApp"`

**Response:**
```json
{
  "status": "OK",
  "message": "layout_dump executed successfully. Using selected device: emulator-5554.",
  "data": {
    "device": {"serial": "emulator-5554", "name": "Pixel_6_API_34", "isOnline": true},
    "file": "/Users/dev/MyApp/build/jugg/mcp_fetch/layout_dump/layout_emulator-5554_1707500001000.xml"
  },
  "artifacts": [{"type": "xml", "path": "/Users/dev/MyApp/build/jugg/mcp_fetch/layout_dump/layout_emulator-5554_1707500001000.xml"}],
  "errorCode": null
}
```

Agent reads the XML dump and finds:
```xml
<node resource-id="com.example.myapp:id/btn_refresh" bounds="[160,850][920,950]" />
```

Agent calculates center coordinates: `x = (160+920)/2 = 540`, `y = (850+950)/2 = 900`.

### 6. Tap the button

**Call:** `tap` with `projectDir: "/Users/dev/MyApp"`, `x: 540`, `y: 900`

**Response:**
```json
{
  "status": "OK",
  "message": "tap executed successfully. Using selected device: emulator-5554.",
  "data": {
    "device": {"serial": "emulator-5554", "name": "Pixel_6_API_34", "isOnline": true},
    "x": 540,
    "y": 900
  },
  "artifacts": [],
  "errorCode": null
}
```

### 7. Screenshot (verify UI state after tap)

**Call:** `screenshot` with `projectDir: "/Users/dev/MyApp"`

**Response:**
```json
{
  "status": "OK",
  "message": "screenshot executed successfully. Using selected device: emulator-5554.",
  "data": {
    "device": {"serial": "emulator-5554", "name": "Pixel_6_API_34", "isOnline": true},
    "file": "/Users/dev/MyApp/build/jugg/mcp_fetch/screenshot/screenshot_emulator-5554_1707500002000.png"
  },
  "artifacts": [{"type": "image", "path": "/Users/dev/MyApp/build/jugg/mcp_fetch/screenshot/screenshot_emulator-5554_1707500002000.png"}],
  "errorCode": null
}
```

Agent inspects the screenshot and confirms the TextView now shows updated text after button tap.

### 8. Agent summary

```
Verdict: PASS
Project: /Users/dev/MyApp
Build path: compile_and_deploy
Changes: added Refresh button to activity_main.xml + click listener in MainActivity.kt
Verification:
  - layout_dump confirmed btn_refresh exists at bounds [160,850][920,950]
  - tap at (540, 900) succeeded
  - screenshot confirms updated text after interaction
Artifacts:
  - layout_dump: /Users/dev/MyApp/build/jugg/mcp_fetch/layout_dump/layout_emulator-5554_1707500001000.xml
  - screenshot: /Users/dev/MyApp/build/jugg/mcp_fetch/screenshot/screenshot_emulator-5554_1707500002000.png
```

## Record Decision Checklist

- Static end-state validation: prefer `screenshot + layout_dump`; skip recording by default.
- Time-based behavior validation (animation/navigation/async/transient UI/multi-step flow): recording is recommended.
- User explicitly asks for video: recording is required.
- Quick rule: prove process (**how**) -> record; prove final state (**what**) -> recording optional.
- Minimal recording template: return to start page -> locate target -> `start_record` -> perform one valid interaction (`start_app`/`tap`) -> `stop_record` -> post-record screenshot.
