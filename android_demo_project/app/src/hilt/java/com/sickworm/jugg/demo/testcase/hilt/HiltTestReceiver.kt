package com.sickworm.jugg.demo.testcase.hilt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Receiver fixture that requires Hilt's generated onReceive super call. */
@AndroidEntryPoint
class HiltTestReceiver : BroadcastReceiver() {
    @Inject lateinit var value: HiltValue

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("HiltFlow", "receiver:baseline:${value.text}")
    }
}
