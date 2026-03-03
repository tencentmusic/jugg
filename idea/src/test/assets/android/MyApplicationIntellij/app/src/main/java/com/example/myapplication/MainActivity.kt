package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.sickworm.jugg.demo.testcase.databinding.DataBindingJavaDemoActivity
import com.sickworm.jugg.demo.testcase.databinding.DataBindingKotlinDemoActivity
import com.sickworm.jugg.demo.testcase.ksp.MoshiDemoActivity
import com.sickworm.jugg.demo.testcase.mcp.McpTestActivity

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

        findViewById<View>(R.id.btn_java_databinding_demo).setOnClickListener {
            startActivity(Intent(this, DataBindingJavaDemoActivity::class.java))
        }

        findViewById<View>(R.id.btn_kotlin_databinding_demo).setOnClickListener {
            startActivity(Intent(this, DataBindingKotlinDemoActivity::class.java))
        }

        findViewById<View>(R.id.btn_mcp_test_page).setOnClickListener {
            startActivity(Intent(this, McpTestActivity::class.java))
        }
    }
}
