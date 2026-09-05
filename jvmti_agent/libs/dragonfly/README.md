# Dragonfly runtime binaries

The source DEX JARs are converted to class JARs and preprocessed with Jar Jar Abrams before they enter Jugg's app-side runtime artifacts. They are not used as desktop-side data sources.

| File | SHA-256 |
|------|---------|
| `dragonfly_0.jar` | `9c00443d2036c7478db6df826b75be9d9912183622b9dad7be57d355ec56fd80` |
| `implementation_0.jar` | `3d7433d930ac5eac9486d76e0460490a4b10974fb8f5964a8f279e69cbb4dc9d` |
| `dragonfly_0-jugg.jar` | `16c3830bdda0f98ebe0b33297078068916dd1c6f56baecaca10ea82cf5fc58c3` |
| `implementation_0-jugg.jar` | `4571e2535cbbdee6c5d49f9cb2d7ed69fe48ae716f8bc09c678cfd029e544340` |

Packaging notes:

- Run `preprocess.sh` only when updating the source DEX JARs. It pins dex2jar and Jar Jar Abrams with SHA-256 checks before relocation.
- After relocation, the script normalizes dex2jar's Java 8 class version to Java 6 because the generated classes do not contain `StackMapTable`; this removes D8 control-flow warnings without changing the generated DEX.
- Dragonfly classes move to `com.sickworm.intellij.jugg.internal.dragonfly.**`; bundled Kotlin, coroutines, Guava, and dexlib2 classes move below `com.sickworm.intellij.jugg.internal.dragonfly.runtime.**`.
- The Gradle build consumes only the committed `*-jugg.jar` files. Jar Jar is not part of the normal build flow.
- Both relocated JARs are packaged into `jugg-instruments.jar` and `jugg-runtime.jar`.
- Dragonfly uses its bundled private runtime and no longer depends on the host app's Kotlin runtime.
- Compose compatibility failures are contained by Dragonfly and do not fall back to Jugg's legacy node extraction.
