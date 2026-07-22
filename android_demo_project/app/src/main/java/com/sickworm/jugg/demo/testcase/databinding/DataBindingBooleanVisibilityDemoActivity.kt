package com.sickworm.jugg.demo.testcase.databinding

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.example.myapplication.R
import com.example.myapplication.databinding.ActivityDataBindingBooleanVisibilityDemoBinding

/**
 * Exercises a boolean value bound to android:visibility through a custom BindingAdapter.
 */
class DataBindingBooleanVisibilityDemoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = DataBindingUtil.setContentView<ActivityDataBindingBooleanVisibilityDemoBinding>(
            this,
            R.layout.activity_data_binding_boolean_visibility_demo,
        )
        binding.visible = true
    }
}
