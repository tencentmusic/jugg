package com.sickworm.jugg.demo.testcase.databinding.library1;

import android.view.View;

import androidx.databinding.BindingAdapter;

/**
 * Provides DataBinding adapters consumed by modules that depend on library1.
 */
public final class LibraryBindingAdapters {

    private LibraryBindingAdapters() {
    }

    @BindingAdapter("projectVisible")
    public static void setProjectVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
