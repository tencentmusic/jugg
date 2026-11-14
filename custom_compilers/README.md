# How to build custom compilers

1. Create a new repo to store your custom compiler codes.
2. `git clone ${your repo url} custom_compilers/src/custom` to clone your repo to custom dir.
3. Create `custom_compilers/src/custom/config.properties`, and specify your compiler's name, version and path, e.g.:
   ```
   jugg.custom.compiler.name=demo_compilers
   jugg.custom.compiler.version=1.8
   jugg.custom.compiler.dir=.
   ```
4. Put your compiler's `java` and `resources` directories under `custom_compilers/src/custom/<jugg.custom.compiler.dir>`. Keep the `META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator` registration in `resources`.
5. Run `./gradlew custom_compilers:build`. If you see `Custom mode.` in log and build success, it means your custom compiler is built successfully.
6. Implement your compiler using the demo sources in `custom_compilers/src/main` as a reference.
7. Use the JAR in `custom_compilers/build/libs` and give it to your Jugg server manager, or manage it by `build/jugg/config/custom_config.json`.
8. Don't forget to push your codes to your repo after developing.
9. Enjoy it!

Before configuring the built JAR, verify that it contains the SPI registration and its implementation classes. Project-specific implementations stay in their own repository.

For source preprocessing, implement `IJuggAptProcessor` in addition to the custom compiler contract; this entry runs before Kotlin/Java compilation independently of hook order. A generator can require `failClosed` when incomplete output must stop the source round. See [the extension flow](../docs/ai_knowledge/02_compile_custom_ui.md) for execution and failure contracts.
