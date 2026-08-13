package com.sickworm.jugg.demo.testcase.splitclassloader;

import com.sickworm.jugg.demo.split.api.SplitGiftStorage;

import io.reactivex.rxjava3.core.Maybe;

/** Base APK implementation used by the split class loader fixture. */
public class BaseGiftStorage implements SplitGiftStorage {
    @Override
    public Maybe<String> get() {
        return Maybe.just("split classloader ready");
    }
}
