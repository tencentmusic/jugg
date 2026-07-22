package com.sickworm.jugg.demo.testcase.databinding;

import android.view.View;

import androidx.databinding.BindingAdapter;

public final class IncrementalJavaBindingAdapters {

    private IncrementalJavaBindingAdapters() {
    }

    @BindingAdapter("juggIncrementalJavaVisibility")
    public static void setIncrementalJavaVisibility(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
