package com.sickworm.jugg.demo.testcase.databinding.library1

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.example.library1.R
import com.example.library1.databinding.ActivityDataBindingKotlinDemoLibrary1Binding

class DataBindingKotlinDemoActivityLibrary1 : AppCompatActivity() {

    private lateinit var binding: ActivityDataBindingKotlinDemoLibrary1Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_data_binding_kotlin_demo_library1)
        
        binding.user = User("John", 44)
        binding.executePendingBindings()
    }

    data class User(
        val name: String,
        val age: Int
    )
}
