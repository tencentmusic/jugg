package com.sickworm.jugg.demo.split.api;

import io.reactivex.rxjava3.core.Maybe;

/** Contract packaged in the install-time split APK fixture. */
public interface SplitGiftStorage {
    Maybe<String> get();
}
