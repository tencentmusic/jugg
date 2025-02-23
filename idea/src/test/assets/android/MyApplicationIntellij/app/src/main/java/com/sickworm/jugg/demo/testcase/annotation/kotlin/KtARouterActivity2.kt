package com.sickworm.jugg.demo.testcase.annotation.kotlin

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R

//@Route(path = "/app/activity_kt_2")
class KtARouterActivity2 : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)
    }
}