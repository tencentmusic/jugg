
# Where to find libs?

/Applications/Android Studio.app/Contents/plugins/android/lib 
## Compat API workflow

Compat modules compile against versioned Stub API JARs by default. Real Android Studio JARs are local-only inputs and are never detected automatically.

```bash
# 1. Create a new independent module, or pass a parent version as the second argument.
./deploy_compat/create_compat_module.sh quail_next
./deploy_compat/create_compat_module.sh giraffe_next giraffe

# 2. Temporarily compile against an explicitly selected Android Studio JAR directory.
./deploy_compat/switch_api.sh real /path/to/android-studio/jars

# 3. After the compat module compiles and passes its real-IDE checks, generate its Stub API.
./deploy_compat/generate_stub_api.sh quail_next \
  deploy_compat/v_quail_next/build/libs/quail_next.jar \
  /path/to/android-studio/jars

# 4. Return all compat modules to committed Stub API JARs and compile again.
./deploy_compat/switch_api.sh stub

# 5. From the Stub API checkout, clean-build and compare it with a checkout that uses real JARs.
./deploy_compat/verify_stub_api.sh /path/to/real-api-jugg-repo
```

`generate_stub_api.sh` scans symbolic class references in the compiled compat JAR, resolves those classes from the supplied JAR directory, retains their ABI declarations and replaces executable method bodies. It also writes `stubapi.properties` with generation inputs. Stub JARs are compile-only and must not be packaged into the plugin.

`verify_stub_api.sh` reports source-tree differences without treating them as the result because generated files and unused imports may differ. It clean-builds every `v_*` module, then compares class entries and normalized Android Studio/IntelliJ API bytecode references. Any product difference fails the command and leaves manifests and diffs under `build/stub-api-verify/`.
