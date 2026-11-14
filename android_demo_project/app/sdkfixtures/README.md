# ConstraintLayout SDK fixture

`../libs/constraint-layout-sdk.jar` contains only `SdkConstraintLayout`, with no resources or generated Binding classes. Its source is outside the app source sets so XML-only reproduction does not recompile the SDK.

To rebuild with JDK 17, set `CONSTRAINTLAYOUT_JAR` to the cached ConstraintLayout 1.1.3 `classes.jar`, then run from this directory:

```sh
mkdir -p build/classes
javac -source 8 -target 8 -cp "$ANDROID_HOME/platforms/android-34/android.jar:$CONSTRAINTLAYOUT_JAR" -d build/classes src/com/sickworm/jugg/demo/sdk/SdkConstraintLayout.java
jar cf ../libs/constraint-layout-sdk.jar -C build/classes .
```
