package com.sickworm.jugg.demo.testcase.databinding;

import android.view.View;

import androidx.databinding.BindingAdapter;

/**
 * Provides custom DataBinding adapters used by incremental compilation cases.
 */
public final class DemoBindingAdapters {

    private DemoBindingAdapters() {
    }

    @BindingAdapter("android:visibility")
    public static void setBooleanVisibility(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
