# Dragonfly runtime binaries

These AARs are merged into Jugg's app-side `jugg-runtime.jar`; they are not used as desktop-side data sources.

| File | SHA-256 |
|------|---------|
| `dragonfly-v0.0.0.S.aar` | `b0be4870ce4a7595fc594a5d9873e38031876c608eafdefaf90b0cb21eab5080` |
| `dragonfly-compose-v0.0.0.S.aar` | `38be968899285b4dfc6fa0f9fb6b8789d2c7ab7568f621fa5a86f5bbf5abff15` |

Packaging notes:

- Core and Compose extraction are split into separate AARs and both are merged into `jugg-runtime.jar`.
- Dragonfly core still requires the host app's Kotlin runtime. Java-only apps receive an explicit unsupported-feature error.
- Compose compatibility failures are contained by Dragonfly and do not fall back to Jugg's legacy node extraction.
