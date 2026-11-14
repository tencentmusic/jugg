# Dragonfly runtime binaries

The source-compiled Dragonfly JAR and its DEX runtime JAR are preprocessed with Jar Jar Abrams before they enter Jugg's app-side runtime artifacts. They are not used as desktop-side data sources.

| File | SHA-256 |
|------|---------|
| `dragonfly_0.jar` | `3bca604d4c55b90eff97a0fa85d3316151291a7ba6bba09c8fb894f4525af4c7` |
| `implementation_0.jar` | `3d7433d930ac5eac9486d76e0460490a4b10974fb8f5964a8f279e69cbb4dc9d` |
| `dragonfly_0-jugg.jar` | `015aa0f57df1e84492a43d9b3b9b228de100105c0dac8cefe61365ad49e01988` |
| `implementation_0-jugg.jar` | `8a71ca490682acce303ad8f951f295b156299b1b3d3c3955029cabdf27a5049a` |

Packaging notes:

- `dragonfly_0.jar` is the source-compiled `classes.jar` extracted from the supplied Dragonfly AAR. The AAR manifest, assets, and native library are not packaged into Jugg.
- Run `preprocess.sh` only when updating the Dragonfly binaries. It pins dex2jar and Jar Jar Abrams with SHA-256 checks before relocation.
- The script removes Dragonfly's unused Java 11 JVMTI bridge classes so old D8 versions can read the remaining source-compiled classes.
- `implementation_0.jar` remains a DEX JAR. After conversion and relocation, the script removes the optional dexlib2 subtree that triggers the AGP 8.8 D8 frame-analysis crash. Dragonfly catches failures from the affected Kuikly hook path.
- The partial dex2jar Kotlin runtime is replaced with the pinned source-compiled Kotlin stdlib 2.0.0 JAR. This preserves Dragonfly's private runtime namespace and supplies the Kotlin APIs used by the new AAR without depending on the host app.
- The remaining dex2jar-generated Java 8 classes are normalized to Java 6 because they do not contain `StackMapTable`; this removes D8 control-flow warnings without changing the generated DEX.
- Dragonfly classes move to `com.sickworm.intellij.jugg.internal.dragonfly.**`; bundled Kotlin, coroutines, and Guava classes move below `com.sickworm.intellij.jugg.internal.dragonfly.runtime.**`.
- Only the source-compiled Dragonfly JAR prepends `jarjar-host-api-rules.txt` to the common rules. Its exact `kotlin.jvm.functions.Function3` identity rule preserves both the `SlotTreeKt.mapTree$default` descriptor and the `invokedynamic` callback interface. The current source JAR uses this type only in `ComposeExtractorKt`; no other host Compose call exposes Kotlin types in its descriptor. Runtime dependencies use only the common rules, keeping their own `Function3` definitions and references private. Preserving only the host method descriptor would leave an incompatible callback.
- The Gradle build consumes only the committed `*-jugg.jar` files. Jar Jar is not part of the normal build flow.
- Both relocated JARs and the ViewHierarchy package are packaged only into the APK-injected `jugg-runtime.jar`, so the App ClassLoader can resolve host Compose types. Bootstrap `jugg-instruments.jar` must not define these classes.
- Ordinary View collection uses the bundled private runtime without requiring host Kotlin. Compose collection uses the host's Compose APIs and their Kotlin callback interfaces; no unrelocated Kotlin definitions are bundled.
- Compose compatibility failures are contained by Dragonfly and do not fall back to Jugg's legacy node extraction.
