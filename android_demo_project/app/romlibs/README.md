# ROM API compilation reproduction

Minimal Java stubs model a device framework through `compileOnly` dependencies and must not enter the APK. They model compiler interfaces only; do not instantiate or invoke the reproduction classes on ordinary devices.

## Scenarios

- `src/main/.../romhiddenapi/RomHiddenApiChild.kt`: indirect inheritance from a hidden nested ROM SDK parent, preserving the original case.
- `src/romApi/.../AccessoriesFragmentCase.java`: `BluetoothProfile.HID_HOST` from report `261e2001`.
- `src/romApi/.../WirelessNetworkManagerCase.kt`: six unresolved Wi-Fi methods from report `752724a0`.

The latter two require `-PjuggRomApiFixture=true` and are excluded from the default app source set. All app build templates share `fixture.gradle`. The recorded verification used Kotlin 1.9.22 / AGP 7.3.1; other versions were not verified.

## Regenerate stubs

From the demo project root, using JDK 9+:

```bash
python3 app/romlibs/build_fixtures.py
```

The script packages `src/android` into `rom_framework.jar` and `src/com` into `rom_sdk.jar`, keeping auxiliary SDK classes out of the framework JAR.

## Full-build baseline

```bash
./gradlew :app:assembleDebug -PjuggRomApiFixture=true
```

With the fixture enabled, `fixture.gradle` prepends framework stubs to Gradle Kotlin compiler dependencies and the javac bootstrap classpath so APIs absent from the public SDK can establish a baseline. Dependencies remain compile-only.

## Verify Jugg in Android Studio

1. Open the demo with a plugin containing the framework initialization detection fix.
2. Set the full-build command to `:app:assembleDebug -PjuggRomApiFixture=true`, establish the baseline, and refresh the project model. Set the same Gradle project property during Sync if IDE indexing needs the fixture sources.
3. Edit a reproduction method body and run Jugg incremental compilation.
4. Check `Framework classpath detection`: it should select `rom_framework.jar`. The actual javac/kotlinc classpath must place it before the SDK, without the old SDK-last retry.
5. Outputs must contain the reproduction classes. APK DEX must not define the stub classes `android.bluetooth.BluetoothProfile`, `android.net.wifi.WifiManager`, `WifiInfo`, `WifiConfiguration`, or `com.rom.RomSoftApCallback`.

The original investigation had no valid demo-specific Jugg Run Configuration, so the CLI did not complete IDE incremental compilation; verify this in a configured environment.

## Automated matrix

From the repository root:

```bash
ANDROID_HOME=/path/to/sdk ./gradlew :main:test --tests '*RomApiCompileTest'
```

The 24-case matrix covers Java/Kotlin fields, methods, nested types, overloads, constructors, and indirect parents using real SDK 35, javac, and Kotlin compiler. It first retains SDK-first failures, then verifies compile-only detection and successful prepending through the real `BaseCompileContext`, without automatic compiler retry. The two reported cases read demo sources directly; other cases use temporary sources. Activity overload stubs exist only in a temporary JAR and do not replace the demo's normal Activity API.
