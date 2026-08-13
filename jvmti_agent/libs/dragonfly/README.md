# Dragonfly runtime binaries

The source DEX JARs are converted to class JARs and preprocessed with Jar Jar Abrams before they enter Jugg's app-side runtime artifacts. They are not used as desktop-side data sources.

| File | SHA-256 |
|------|---------|
| `dragonfly_0.jar` | `320aa0bf78caef7fb586a96d511cf6f01f8b2a494e796f670b72b9cc9f508abd` |
| `implementation_0.jar` | `3d7433d930ac5eac9486d76e0460490a4b10974fb8f5964a8f279e69cbb4dc9d` |
| `dragonfly_0-jugg.jar` | `fcb68196b0e7ed44de83cace36dd1a0bbb1d7eeb447ea13486e833386d60d6d9` |
| `implementation_0-jugg.jar` | `d866518e4b4e7f7deb2e86b26bd104220cc834784d29efd01ab6ca44b4b70711` |

Packaging notes:

- Run `preprocess.sh` only when updating the source DEX JARs. It pins dex2jar and Jar Jar Abrams with SHA-256 checks before relocation.
- Dragonfly classes move to `com.sickworm.intellij.jugg.internal.dragonfly.**`; bundled Kotlin, coroutines, Guava, and dexlib2 classes move below `com.sickworm.intellij.jugg.internal.dragonfly.runtime.**`.
- The Gradle build consumes only the committed `*-jugg.jar` files. Jar Jar is not part of the normal build flow.
- Both relocated JARs are packaged into `jugg-instruments.jar` and `jugg-runtime.jar`.
- Dragonfly uses its bundled private runtime and no longer depends on the host app's Kotlin runtime.
- Compose compatibility failures are contained by Dragonfly and do not fall back to Jugg's legacy node extraction.
