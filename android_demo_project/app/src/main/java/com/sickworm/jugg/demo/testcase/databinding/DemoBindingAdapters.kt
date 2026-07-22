package com.sickworm.jugg.demo.testcase.databinding

import android.view.View
import androidx.databinding.BindingAdapter

/**
 * Provides custom DataBinding adapters used by incremental compilation cases.
 */
object DemoBindingAdapters {

    @JvmStatic
    @BindingAdapter("android:visibility")
    fun setBooleanVisibility(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
