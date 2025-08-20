package com.example.myapplication

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View

import com.sickworm.jugg.demo.testcase.ksp.MoshiDemoActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.button).setOnClickListener {
            startActivity(Intent(this, MainActivity2::class.java))
        }

        findViewById<View>(R.id.btn_moshi_demo).setOnClickListener {
            startActivity(Intent(this, MoshiDemoActivity::class.java))
        }
    }
}
