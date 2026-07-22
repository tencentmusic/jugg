package com.sickworm.jugg.demo.testcase.databinding

import android.view.View
import androidx.databinding.BindingAdapter

object IncrementalBindingAdapters {

    @JvmStatic
    @BindingAdapter("juggIncrementalVisibility")
    fun setIncrementalVisibility(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
