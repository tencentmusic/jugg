package com.sickworm.jugg.demo.testcase.hilt

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Activity fixture whose ordinary method body is updated by Jugg. */
@AndroidEntryPoint
class HiltTestActivity : AppCompatActivity() {
    @Inject lateinit var value: HiltValue

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("HiltFlow", "activity:updated:${value.text}")
    }
}
