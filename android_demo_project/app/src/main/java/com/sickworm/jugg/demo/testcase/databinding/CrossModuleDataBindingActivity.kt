package com.sickworm.jugg.demo.testcase.databinding

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.example.library1.databinding.CommonToolbarBinding
import com.example.myapplication.R
import com.example.myapplication.databinding.ActivityCrossModuleDatabindingBinding

/**
 * Reproduces the multi-module DataBinding shape: an app layout with <layout> that
 * <include>s a library layout (common_toolbar in :library1), plus a direct
 * DataBindingUtil.inflate of that library layout.
 *
 * Both paths resolve the library layout through the merged mapper that the app root
 * mapper collects in collectDependencies(). A library mapper dropped from that
 * collection makes these calls return null and NPE on the next line.
 */
class CrossModuleDataBindingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrossModuleDatabindingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_cross_module_databinding)
        binding.pageTitle = "App layout includes library1/common_toolbar"
        binding.executePendingBindings()
        binding.includedToolbar.title = "Toolbar bound through cross-module include"

        val libraryBinding = DataBindingUtil.inflate<CommonToolbarBinding>(
            layoutInflater, R.layout.common_toolbar, null, false)
        libraryBinding.title = "Library layout inflated from app"
        libraryBinding.executePendingBindings()
    }
}
