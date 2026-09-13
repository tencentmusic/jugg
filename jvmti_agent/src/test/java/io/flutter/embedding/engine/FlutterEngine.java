package io.flutter.embedding.engine;

import android.content.res.AssetManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Test double mirroring the Flutter 3.47.2 embedding shape that FlutterAssetRefresh reflects on:
 * a static engine registry, a per engine FlutterJNI and the DartExecutor holding the AssetManager
 * captured at construction. Flutter also keeps FlutterEngineCache, which only covers engines
 * created through it, so the registry is the source of truth here.
 */
public class FlutterEngine {

    private static final Map<Long, FlutterEngine> idToEngine = new HashMap<>();
    private static long nextEngineId = 1;

    private final FlutterJNI flutterJNI;
    private final DartExecutor dartExecutor;

    public FlutterEngine(FlutterJNI flutterJNI, AssetManager assetManager) {
        this.flutterJNI = flutterJNI;
        this.dartExecutor = new DartExecutor(assetManager);
        idToEngine.put(nextEngineId++, this);
    }

    public DartExecutor dartExecutor() {
        return dartExecutor;
    }

    /** Test helper: drops every engine registered so far. */
    public static void resetForTest() {
        idToEngine.clear();
        nextEngineId = 1;
    }
}
