package com.sickworm.jugg.demo.testcase.annotation.kotlin

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R

//@Route(path = "/app_kt/activity_kt_3")
class KtARouterActivity3 : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)
    }
}