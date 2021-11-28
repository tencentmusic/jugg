package com.example.myapplication

import java.lang.Runnable;
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View

class MainActivity : AppCompatActivity(), Runnable {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.button).setOnClickListener {
            startActivity(Intent(this, MainActivity2::class.java))
        }
    }

    override fun run() {

    }
}
