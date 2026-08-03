package com.example.myapplication

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import com.sickworm.jugg.demo.testcase.databinding.DataBindingJavaDemoActivity
import com.sickworm.jugg.demo.testcase.databinding.DataBindingKotlinDemoActivity
import com.sickworm.jugg.demo.testcase.kmp.KmpComposeResourceDemoActivity
import com.sickworm.jugg.demo.testcase.ksp.MoshiDemoActivity
import com.sickworm.jugg.demo.testcase.mcp.McpTestActivity

class MainActivity : AppCompatActivity() {
    companion object {
        const val BENCHMARK_LOG_TAG = "jugg"
        const val BENCHMARK_LOG_MARKER = "[JUGG_BENCH] MAIN_ACTIVITY_READY"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.i(BENCHMARK_LOG_TAG, BENCHMARK_LOG_MARKER)

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

        findViewById<View>(R.id.btn_kmp_compose_resource_demo).setOnClickListener {
            startActivity(Intent(this, KmpComposeResourceDemoActivity::class.java))
        }
    }
}
