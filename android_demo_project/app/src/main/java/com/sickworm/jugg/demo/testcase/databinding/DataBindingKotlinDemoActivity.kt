package com.sickworm.jugg.demo.testcase.databinding

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.example.myapplication.R
import com.example.myapplication.databinding.ActivityDataBindingKotlinDemoBinding

class DataBindingKotlinDemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDataBindingKotlinDemoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_data_binding_kotlin_demo)
        
        binding.user = User("John", 44)
        binding.executePendingBindings()
    }

    data class User(
        val name: String,
        val age: Int
    )
}
