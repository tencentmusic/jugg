# How to build custom compilers

1. Create a new repo to store your custom compiler codes.
2. `git clone ${your repo url} custom_compilers/src/custom` to clone your repo to custom dir.
3. Create `custom_compilers/src/custom/config.properties`, and specify your compiler's name, version and path, e.g.:
   ```
   jugg.custom.compiler.name=demo_compilers
   jugg.custom.compiler.version=1.8
   jugg.custom.compiler.dir=.
   ```
4. Copy `custom_compilers/src/main` to `jugg.custom.compiler.dir`, e.g. `custom_compliers/src/custom/java/blablabla`.
5. Run `./gradlew custom_compilers:build`. If you see `Custom mode.` in log and build success, it means your custom compiler is built successfully.
6. Modify the demo code copied in step 4 and implement your own custom compiler.
7. Copy you built jars in `custom_compilers/build/libs` and give it to your Jugg server manager, or manage it by `build/jugg/config/custom_config.json`.
8. Don't forget to push your codes to your repo after developing.
9. Enjoy it!