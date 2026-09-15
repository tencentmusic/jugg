package io.flutter.embedding.engine;

import android.content.res.AssetManager;

import java.util.ArrayList;
import java.util.List;

/** Test double of the Flutter 3.47.2 FlutterJNI API surface used by FlutterAssetRefresh. */
public class FlutterJNI {

    public final List<AssetManager> refreshedAssetManagers = new ArrayList<>();
    public final List<String> refreshedBundlePaths = new ArrayList<>();

    /** When set, updateJavaAssetManager fails with this exception instead of recording a refresh. */
    public RuntimeException failure;

    /** Test helper: runs inside updateJavaAssetManager, e.g. to create an engine mid-batch. */
    public Runnable onRefresh;

    private boolean attached = true;

    public boolean isAttached() {
        return attached;
    }

    /** Test helper: makes isAttached() report an engine without a native shell. */
    public void setAttachedForTest(boolean attached) {
        this.attached = attached;
    }

    public void updateJavaAssetManager(AssetManager assetManager, String assetBundlePath) {
        if (failure != null) {
            throw failure;
        }
        refreshedAssetManagers.add(assetManager);
        refreshedBundlePaths.add(assetBundlePath);
        if (onRefresh != null) {
            onRefresh.run();
        }
    }
}
