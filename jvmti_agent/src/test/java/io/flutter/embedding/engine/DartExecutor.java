package io.flutter.embedding.engine;

import android.content.res.AssetManager;

/**
 * Test double of the Flutter 3.47.2 DartExecutor. Flutter keeps the AssetManager it was built with
 * and hands it to FlutterJNI.runBundleAndSnapshotFromLibrary() when Dart starts.
 */
public class DartExecutor {

    private final AssetManager assetManager;
    private boolean executingDart = true;

    public DartExecutor(AssetManager assetManager) {
        this.assetManager = assetManager;
    }

    public boolean isExecutingDart() {
        return executingDart;
    }

    /** Test helper: mirrors an engine whose Dart entrypoint has not been launched yet. */
    public void setExecutingDartForTest(boolean executingDart) {
        this.executingDart = executingDart;
    }

    /** Test helper: the AssetManager the next Dart launch would hand to native. */
    public AssetManager assetManagerForTest() {
        return assetManager;
    }
}
