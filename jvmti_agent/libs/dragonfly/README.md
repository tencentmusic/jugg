# Dragonfly runtime binaries

The source-compiled Dragonfly JAR and its DEX runtime JAR are preprocessed with Jar Jar Abrams before they enter Jugg's app-side runtime artifacts. They are not used as desktop-side data sources.

| File | SHA-256 |
|------|---------|
| `dragonfly_0.jar` | `3bca604d4c55b90eff97a0fa85d3316151291a7ba6bba09c8fb894f4525af4c7` |
| `implementation_0.jar` | `3d7433d930ac5eac9486d76e0460490a4b10974fb8f5964a8f279e69cbb4dc9d` |
| `dragonfly_0-jugg.jar` | `cd21123db6b398997359ebeb5ee0ead3a3da399f03242dbb29141c0d2ee3b5bf` |
| `implementation_0-jugg.jar` | `8a71ca490682acce303ad8f951f295b156299b1b3d3c3955029cabdf27a5049a` |

Packaging notes:

- `dragonfly_0.jar` is the source-compiled `classes.jar` extracted from the supplied Dragonfly AAR. The AAR manifest, assets, and native library are not packaged into Jugg.
- Run `preprocess.sh` only when updating the Dragonfly binaries. It pins dex2jar and Jar Jar Abrams with SHA-256 checks before relocation.
- The script removes Dragonfly's unused Java 11 JVMTI bridge classes so old D8 versions can read the remaining source-compiled classes.
- `implementation_0.jar` remains a DEX JAR. After conversion and relocation, the script removes the optional dexlib2 subtree that triggers the AGP 8.8 D8 frame-analysis crash. Dragonfly catches failures from the affected Kuikly hook path.
- The partial dex2jar Kotlin runtime is replaced with the pinned source-compiled Kotlin stdlib 2.0.0 JAR. This preserves Dragonfly's private runtime namespace and supplies the Kotlin APIs used by the new AAR without depending on the host app.
- The remaining dex2jar-generated Java 8 classes are normalized to Java 6 because they do not contain `StackMapTable`; this removes D8 control-flow warnings without changing the generated DEX.
- Dragonfly classes move to `com.sickworm.intellij.jugg.internal.dragonfly.**`; bundled Kotlin, coroutines, and Guava classes move below `com.sickworm.intellij.jugg.internal.dragonfly.runtime.**`.
- The Gradle build consumes only the committed `*-jugg.jar` files. Jar Jar is not part of the normal build flow.
- Both relocated JARs are packaged into `jugg-instruments.jar` and `jugg-runtime.jar`.
- Dragonfly uses its bundled private runtime and no longer depends on the host app's Kotlin runtime.
- Compose compatibility failures are contained by Dragonfly and do not fall back to Jugg's legacy node extraction.
